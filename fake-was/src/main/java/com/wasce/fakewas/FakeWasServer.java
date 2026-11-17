package com.wasce.fakewas;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 模拟 WAS 服务器进程，仅用于插件开发与测试。
 * 参数与真实启动脚本的尾部一致：WsServer标记 configRoot cell node server。
 * 行为：写 pid 文件、按 WAS 格式输出 SystemOut.log、在 SOAP 端口上应答 STATE/STOP/PING。
 * 故障注入：-Dfakewas.startFail=true 启动失败；-Dfakewas.ignoreStop=true 不响应停止。
 */
public final class FakeWasServer {
    private static volatile String state = "STARTING";
    private static PrintStream systemOut;
    private static File pidFile;
    private static FakeApps apps;

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("用法: FakeWasServer com.ibm.ws.runtime.WsServer <configRoot> <cell> <node> <server>");
            System.exit(1);
        }
        final String cell = args[2];
        final String node = args[3];
        final String server = args[4];
        File configRoot = new File(args[1]);
        File profileDir = configRoot.getParentFile();
        int soapPort = readSoapPort(new File(configRoot, "cells/" + cell + "/nodes/" + node + "/serverindex.xml"), server);

        apps = new FakeApps(profileDir, cell);
        File logDir = new File(profileDir, "logs/" + server);
        logDir.mkdirs();
        systemOut = new PrintStream(new FileOutputStream(new File(logDir, "SystemOut.log"), true), true, "UTF-8");
        pidFile = new File(logDir, server + ".pid");
        writePid();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                pidFile.delete();
            }
        }));

        System.out.println("FakeWasServer 模拟服务器启动中: " + cell + "/" + node + "/" + server + "，SOAP 端口 " + soapPort);
        log("WsServerImpl", "A", "WSVR0000I: Server " + server + " 正在启动（模拟）");
        log("ManagerAdmin", "I", "TRAS0017I: The startup trace state is *=info.");

        if (Boolean.getBoolean("fakewas.startFail")) {
            log("WsServerImpl", "E", "WSVR0009E: Error occurred during startup（模拟启动失败）");
            System.exit(1);
        }

        final ServerSocket soap = new ServerSocket(soapPort, 50, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        handle(soap.accept(), cell, node, server);
                    } catch (IOException e) {
                        return;
                    }
                }
            }
        }, "fake-soap");
        acceptor.setDaemon(true);
        acceptor.start();
        log("SOAPConnector", "A", "ADMC0026I: The SOAP connector is available at port " + soapPort);

        Thread.sleep(Long.getLong("fakewas.startDelayMs", 3000L));
        state = "STARTED";
        log("WsServerImpl", "A", "WSVR0001I: Server " + server + " open for e-business");

        // 周期输出日志，用于验证日志跟踪
        int tick = 0;
        while (true) {
            Thread.sleep(15000);
            if ("STARTED".equals(state)) {
                log("FakeApp", "I", "SRVE0242I: 模拟应用心跳 " + (++tick));
            }
        }
    }

    private static void handle(final Socket s, final String cell, final String node, final String server) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                    Writer w = new OutputStreamWriter(s.getOutputStream(), "UTF-8");
                    String cmd = r.readLine();
                    if ("STATE".equals(cmd)) {
                        w.write(state + " " + cell + " " + node + " " + server + "\n");
                    } else if ("STOP".equals(cmd)) {
                        w.write("OK\n");
                        if (!Boolean.getBoolean("fakewas.ignoreStop")) {
                            stopAsync(server);
                        } else {
                            log("WsServerImpl", "W", "收到停止请求，但已配置 fakewas.ignoreStop，忽略");
                        }
                    } else if (cmd != null && cmd.startsWith("APP\t")) {
                        w.write(appCommand(cmd.split("\t")) + "\n");
                    } else {
                        w.write("PONG\n");
                    }
                    w.flush();
                    s.close();
                } catch (IOException ignored) {
                    // 忽略单个连接的异常
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** 应用命令：APP 动作 应用名 [路径]，应答 OK[ 数据] 或 ERR 原因 */
    private static String appCommand(String[] a) {
        String action = a[1];
        String app = a[2];
        try {
            if ("STATUS".equals(action)) {
                return "OK\t" + apps.exists(app) + "\t" + apps.isRunning(app);
            } else if ("INSTALL".equals(action)) {
                apps.install(app, a[3]);
            } else if ("PARTIAL".equals(action)) {
                apps.partial(app, a[3]);
            } else if ("UNINSTALL".equals(action)) {
                apps.uninstall(app);
            } else if ("START".equals(action)) {
                apps.start(app);
            } else if ("STOP".equals(action)) {
                apps.stop(app);
            } else {
                return "ERR\t未知动作 " + action;
            }
            return "OK";
        } catch (IOException e) {
            return "ERR\t" + e.getMessage();
        }
    }

    private static synchronized void stopAsync(final String server) {
        if (!"STARTED".equals(state) && !"STARTING".equals(state)) {
            return;
        }
        state = "STOPPING";
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                log("ServerCollabo", "A", "WSVR0024I: Server " + server + " stopping");
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    // 忽略
                }
                log("ServerCollabo", "A", "WSVR0024I: Server " + server + " stopped");
                System.exit(0);
            }
        });
        t.start();
    }

    private static void writePid() throws IOException {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        Writer w = new OutputStreamWriter(new FileOutputStream(pidFile), "UTF-8");
        w.write(pid);
        w.close();
    }

    static synchronized void log(String component, String level, String msg) {
        String ts = new SimpleDateFormat("M/d/yy H:mm:ss:SSS z").format(new Date());
        String line = String.format("[%s] %08x %-13s %s   %s", ts, Thread.currentThread().getId(), component, level, msg);
        systemOut.println(line);
    }

    /** 从 serverindex.xml 读取指定服务器的 SOAP_CONNECTOR_ADDRESS 端口 */
    static int readSoapPort(File serverIndex, String server) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(serverIndex);
        NodeList entries = doc.getElementsByTagName("serverEntries");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            if (!server.equals(entry.getAttribute("serverName"))) {
                continue;
            }
            NodeList eps = entry.getElementsByTagName("specialEndpoints");
            for (int j = 0; j < eps.getLength(); j++) {
                Element ep = (Element) eps.item(j);
                if ("SOAP_CONNECTOR_ADDRESS".equals(ep.getAttribute("endPointName"))) {
                    Element endPoint = (Element) ep.getElementsByTagName("endPoint").item(0);
                    return Integer.parseInt(endPoint.getAttribute("port"));
                }
            }
        }
        throw new IllegalStateException("serverindex.xml 中找不到 " + server + " 的 SOAP 端口");
    }
}
