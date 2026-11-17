package com.wasce.core.agent;

import com.wasce.protocol.Message;
import com.wasce.protocol.MessageIO;
import com.wasce.protocol.Protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 与单个 agent 进程的连接：请求/响应按 id 关联，事件分发给监听器，定时心跳。
 * 连接断开后所有未完成请求立即以 AGENT_DIED 失败，调用方不会无限等待。
 */
public final class AgentClient implements AutoCloseable {
    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wasce-agent-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final Process process;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final String backend;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<Map<String, String>>> pending = new ConcurrentHashMap<>();
    private volatile ScheduledFuture<?> heartbeat;
    private volatile Consumer<Message> eventListener = m -> { };
    private volatile boolean alive = true;

    AgentClient(Process process, Socket socket, String backend) throws IOException {
        this.process = process;
        this.socket = socket;
        this.backend = backend;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    /** 握手完成后由 AgentLauncher 调用，开始接收消息和发送心跳 */
    void start() {
        Thread reader = new Thread(this::readLoop, "wasce-agent-reader");
        reader.setDaemon(true);
        reader.start();
        heartbeat = HEARTBEAT.scheduleWithFixedDelay(
                () -> request(Protocol.OP_PING, Map.of(), Duration.ofSeconds(10)),
                Protocol.HEARTBEAT_MILLIS, Protocol.HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS);
    }

    DataInputStream input() {
        return in;
    }

    void sendRaw(Message m) throws IOException {
        synchronized (out) {
            MessageIO.write(out, m);
        }
    }

    public String backend() {
        return backend;
    }

    public boolean isAlive() {
        return alive && process.isAlive();
    }

    public void setEventListener(Consumer<Message> listener) {
        this.eventListener = listener;
    }

    public CompletableFuture<Map<String, String>> request(String op, Map<String, String> params, Duration timeout) {
        CompletableFuture<Map<String, String>> f = new CompletableFuture<>();
        if (!alive) {
            f.completeExceptionally(new AgentCallException(Protocol.ERR_AGENT_DIED, "agent 连接已断开", null));
            return f;
        }
        long id = ids.incrementAndGet();
        pending.put(id, f);
        f.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((r, e) -> pending.remove(id));
        try {
            sendRaw(Message.request(id, op, params));
        } catch (IOException e) {
            f.completeExceptionally(new AgentCallException(Protocol.ERR_AGENT_DIED, "发送请求失败: " + e.getMessage(), null));
        }
        return f;
    }

    /** 同步调用，统一把超时、中断、连接断开转换为 AgentCallException */
    public Map<String, String> call(String op, Map<String, String> params, Duration timeout) throws AgentCallException {
        try {
            return request(op, params, timeout).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCallException(Protocol.ERR_TIMEOUT, "调用被中断: " + op, null);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof AgentCallException ace) {
                throw ace;
            }
            if (c instanceof TimeoutException) {
                throw new AgentCallException(Protocol.ERR_TIMEOUT, "调用超时（" + timeout.toSeconds() + " 秒）: " + op, null);
            }
            throw new AgentCallException(Protocol.ERR_INTERNAL, String.valueOf(c), null);
        }
    }

    private void readLoop() {
        try {
            while (true) {
                Message m = MessageIO.read(in);
                if (m.kind == Message.RESPONSE) {
                    CompletableFuture<Map<String, String>> f = pending.remove(m.id);
                    if (f == null) {
                        continue;
                    }
                    if (m.ok) {
                        f.complete(m.attrs);
                    } else {
                        f.completeExceptionally(new AgentCallException(m.get(Protocol.KEY_ERROR_CODE),
                                m.get(Protocol.KEY_ERROR_MESSAGE), m.get(Protocol.KEY_ERROR_DETAIL)));
                    }
                } else if (m.kind == Message.EVENT) {
                    eventListener.accept(m);
                }
            }
        } catch (IOException e) {
            // 连接断开，统一在下方处理
        } finally {
            alive = false;
            cancelHeartbeat();
            AgentCallException died = new AgentCallException(Protocol.ERR_AGENT_DIED, "agent 连接已断开", null);
            pending.values().forEach(f -> f.completeExceptionally(died));
            pending.clear();
        }
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> h = heartbeat;
        if (h != null) {
            h.cancel(false);
        }
    }

    /** 请求 agent 正常退出，超时后强制结束进程 */
    @Override
    public void close() {
        if (alive) {
            try {
                request(Protocol.OP_SHUTDOWN, Map.of(), Duration.ofSeconds(2)).get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 退出请求失败时直接关闭连接
            }
        }
        alive = false;
        cancelHeartbeat();
        try {
            socket.close();
        } catch (IOException ignored) {
            // 忽略
        }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
