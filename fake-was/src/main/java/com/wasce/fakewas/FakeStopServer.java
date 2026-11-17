package com.wasce.fakewas;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 模拟 stopServer：向 SOAP 端口发送 STOP，并等待端口关闭。
 * 参数：configRoot cell node server
 */
public final class FakeStopServer {
    public static void main(String[] args) throws Exception {
        File configRoot = new File(args[0]);
        String server = args[3];
        int port = FakeWasServer.readSoapPort(new File(configRoot, "cells/" + args[1] + "/nodes/" + args[2] + "/serverindex.xml"), server);
        System.out.println("ADMU3100I: Reading configuration for server: " + server);
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            Writer w = new OutputStreamWriter(s.getOutputStream(), "UTF-8");
            w.write("STOP\n");
            w.flush();
            s.getInputStream().read();
            s.close();
        } catch (IOException e) {
            System.out.println("ADMU0509I: The Application Server \"" + server + "\" cannot be reached. It appears to be stopped.");
            System.exit(0);
        }
        System.out.println("ADMU3201I: Server stop request issued. Waiting for stop status.");
        String waitEnv = System.getenv("FAKEWAS_STOP_WAIT_MS");
        long deadline = System.currentTimeMillis() + (waitEnv != null ? Long.parseLong(waitEnv) : 60000L);
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", port), 500);
                s.close();
            } catch (IOException closed) {
                System.out.println("ADMU4000I: Server " + server + " stop completed.");
                System.exit(0);
            }
        }
        System.out.println("ADMU3060E: Timed out waiting for server shutdown.");
        System.exit(1);
    }
}
