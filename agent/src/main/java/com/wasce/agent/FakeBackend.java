package com.wasce.agent;

import com.wasce.protocol.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对接模拟 WAS（fake-was 模块）的后端：在"SOAP 端口"上用行协议查询状态和请求停止。
 */
final class FakeBackend implements Backend {
    private volatile String host;
    private volatile int port;
    private volatile EventSink events;

    @Override
    public Map<String, String> handle(String op, Map<String, String> params) throws AgentException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (Protocol.OP_CONNECT.equals(op)) {
            String h = params.get(Protocol.KEY_HOST);
            int p = parsePort(params.get(Protocol.KEY_PORT));
            send(h, p, "PING");
            host = h;
            port = p;
        } else if (Protocol.OP_SERVER_INFO.equals(op)) {
            requireConnected();
            String[] parts = send(host, port, "STATE").split(" ");
            result.put(Protocol.KEY_STATE, parts[0]);
            if (parts.length >= 4) {
                result.put(Protocol.KEY_CELL, parts[1]);
                result.put(Protocol.KEY_NODE, parts[2]);
                result.put(Protocol.KEY_SERVER, parts[3]);
            }
            result.put(Protocol.KEY_WAS_VERSION, "8.5.5.99-fake");
        } else if (Protocol.OP_STOP_SERVER.equals(op)) {
            requireConnected();
            send(host, port, "STOP");
        } else if (Protocol.OP_APP_STATUS.equals(op)) {
            requireConnected();
            String[] r = app("STATUS", params.get(Protocol.KEY_APP), null);
            result.put(Protocol.KEY_EXISTS, r[1]);
            result.put(Protocol.KEY_RUNNING, r[2]);
        } else if (Protocol.OP_INSTALL.equals(op) || Protocol.OP_REDEPLOY.equals(op)) {
            requireConnected();
            progress(params.get(Protocol.KEY_APP), op + " 开始");
            app("INSTALL", params.get(Protocol.KEY_APP), params.get(Protocol.KEY_PATH));
        } else if (Protocol.OP_PARTIAL_UPDATE.equals(op)) {
            requireConnected();
            app("PARTIAL", params.get(Protocol.KEY_APP), params.get(Protocol.KEY_PATH));
        } else if (Protocol.OP_UNINSTALL.equals(op)) {
            requireConnected();
            app("UNINSTALL", params.get(Protocol.KEY_APP), null);
        } else if (Protocol.OP_START_APP.equals(op)) {
            requireConnected();
            app("START", params.get(Protocol.KEY_APP), null);
        } else if (Protocol.OP_STOP_APP.equals(op)) {
            requireConnected();
            app("STOP", params.get(Protocol.KEY_APP), null);
        } else {
            throw new AgentException(Protocol.ERR_BAD_REQUEST, "未知操作: " + op);
        }
        return result;
    }

    private String[] app(String action, String name, String path) throws AgentException {
        String[] r = send(host, port, "APP\t" + action + "\t" + name + (path == null ? "" : "\t" + path)).split("\t");
        if (!"OK".equals(r[0])) {
            throw new AgentException(Protocol.ERR_DEPLOY_FAILED, r.length > 1 ? r[1] : "应用操作失败");
        }
        return r;
    }

    private void progress(String app, String message) {
        EventSink sink = events;
        if (sink != null) {
            Map<String, String> m = new LinkedHashMap<String, String>();
            m.put(Protocol.KEY_APP, app);
            m.put(Protocol.KEY_MESSAGE, message);
            sink.emit(Protocol.EV_DEPLOY_PROGRESS, m);
        }
    }

    @Override
    public void setEventSink(EventSink sink) {
        this.events = sink;
    }

    @Override
    public void close() {
        // 无常驻连接，无需释放
    }

    private void requireConnected() throws AgentException {
        if (host == null) {
            throw new AgentException(Protocol.ERR_NOT_CONNECTED, "尚未连接服务器");
        }
    }

    private static int parsePort(String s) throws AgentException {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new AgentException(Protocol.ERR_BAD_REQUEST, "非法端口: " + s);
        }
    }

    private static String send(String host, int port, String command) throws AgentException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(60000);
            Writer w = new OutputStreamWriter(s.getOutputStream(), "UTF-8");
            w.write(command + "\n");
            w.flush();
            String line = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8")).readLine();
            if (line == null) {
                throw new AgentException(Protocol.ERR_INTERNAL, "模拟服务器未应答: " + command);
            }
            return line.trim();
        } catch (ConnectException e) {
            throw new AgentException(Protocol.ERR_CONNECT_REFUSED, "无法连接 " + host + ":" + port, e);
        } catch (SocketTimeoutException e) {
            throw new AgentException(Protocol.ERR_TIMEOUT, "连接或应答超时 " + host + ":" + port, e);
        } catch (IOException e) {
            throw new AgentException(Protocol.ERR_CONNECT_REFUSED, "通信失败 " + host + ":" + port + ": " + e.getMessage(), e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
