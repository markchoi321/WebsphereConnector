package com.wasce.agent;

import com.wasce.protocol.Message;
import com.wasce.protocol.MessageIO;
import com.wasce.protocol.Protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * agent 入口。参数：IDE 端口、一次性 token、后端类型（fake / jmx）。
 * 生命周期完全跟随与 IDE 的连接：连接断开或长时间无消息即退出，不留孤儿进程。
 */
public final class AgentMain {
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Backend backend;
    private final AtomicLong lastSeen = new AtomicLong(System.currentTimeMillis());
    private final ExecutorService workers = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "wasce-agent-worker");
            t.setDaemon(true);
            return t;
        }
    });

    private AgentMain(Socket socket, Backend backend) throws IOException {
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.backend = backend;
        backend.setEventSink(new Backend.EventSink() {
            @Override
            public void emit(String type, Map<String, String> attrs) {
                try {
                    send(Message.event(type, attrs));
                } catch (IOException e) {
                    log("事件发送失败: " + e);
                }
            }
        });
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("用法: AgentMain <ide端口> <token> <fake|jmx>");
            System.exit(1);
        }
        try {
            Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), Integer.parseInt(args[0]));
            socket.setTcpNoDelay(true);
            AgentMain agent = new AgentMain(socket, createBackend(args[2]));
            agent.handshake(args[1], args[2]);
            agent.startWatchdog();
            agent.loop();
        } catch (Throwable t) {
            log("agent 异常退出: " + stackTrace(t));
            System.exit(2);
        }
        System.exit(0);
    }

    private static Backend createBackend(String name) {
        return "fake".equals(name) ? new FakeBackend() : new JmxBackend();
    }

    private void handshake(String token, String backendName) throws IOException {
        Map<String, String> hello = new LinkedHashMap<String, String>();
        hello.put(Protocol.KEY_TOKEN, token);
        hello.put(Protocol.KEY_VERSION, String.valueOf(Protocol.VERSION));
        hello.put(Protocol.KEY_PID, ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        hello.put(Protocol.KEY_JAVA, System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        hello.put(Protocol.KEY_BACKEND, backendName);
        send(Message.hello(hello));
        Message ack = MessageIO.read(in);
        if (ack.kind != Message.HELLO || !ack.ok) {
            throw new IOException("握手被拒绝: " + ack);
        }
        log("握手完成，后端=" + backendName);
    }

    private void startWatchdog() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (System.currentTimeMillis() - lastSeen.get() > Protocol.AGENT_IDLE_EXIT_MILLIS) {
                        log("长时间未收到 IDE 消息，agent 退出");
                        System.exit(3);
                    }
                }
            }
        }, "wasce-agent-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private void loop() throws IOException {
        while (true) {
            final Message m;
            try {
                m = MessageIO.read(in);
            } catch (EOFException e) {
                log("IDE 已断开连接，agent 退出");
                backend.close();
                return;
            }
            lastSeen.set(System.currentTimeMillis());
            if (m.kind != Message.REQUEST) {
                continue;
            }
            if (Protocol.OP_PING.equals(m.name)) {
                send(Message.success(m.id, null));
            } else if (Protocol.OP_SHUTDOWN.equals(m.name)) {
                send(Message.success(m.id, null));
                backend.close();
                return;
            } else {
                // 业务操作可能较慢，放到工作线程，读循环保持对心跳的响应
                workers.submit(new Runnable() {
                    @Override
                    public void run() {
                        dispatch(m);
                    }
                });
            }
        }
    }

    private void dispatch(Message m) {
        Message reply;
        try {
            reply = Message.success(m.id, backend.handle(m.name, m.attrs));
        } catch (AgentException e) {
            reply = Message.failure(m.id, e.getCode(), e.getMessage(), e.getCause() == null ? null : stackTrace(e.getCause()));
        } catch (Throwable t) {
            reply = Message.failure(m.id, Protocol.ERR_INTERNAL, String.valueOf(t), stackTrace(t));
        }
        try {
            send(reply);
        } catch (IOException e) {
            log("回复失败: " + e);
        }
    }

    private void send(Message m) throws IOException {
        synchronized (out) {
            MessageIO.write(out, m);
        }
    }

    static void log(String s) {
        System.out.println("[wasce-agent] " + s);
        System.out.flush();
    }

    static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
