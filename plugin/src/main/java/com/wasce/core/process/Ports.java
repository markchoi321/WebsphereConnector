package com.wasce.core.process;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 端口探测。
 */
public final class Ports {
    private Ports() {
    }

    public static boolean isOpen(String host, int port, int timeoutMillis) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMillis);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 等待端口关闭，返回是否在超时前关闭 */
    public static boolean waitClosed(String host, int port, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!isOpen(host, port, 300)) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isOpen(host, port, 300);
    }
}
