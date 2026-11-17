package com.wasce.agent;

import com.wasce.protocol.Protocol;

/**
 * 把 IBM 客户端异常按设计方案第 11 节归类。遍历异常链的消息和类名，因为有效原因常被多层包装。
 */
final class ErrorClassifier {
    private ErrorClassifier() {
    }

    static AgentException classify(String action, Throwable t) {
        StringBuilder all = new StringBuilder();
        String root = String.valueOf(t);
        int depth = 0;
        for (Throwable c = t; c != null && depth < 20; c = c.getCause(), depth++) {
            all.append(c.getClass().getName()).append(": ").append(c.getMessage()).append('\n');
            root = String.valueOf(c);
        }
        return new AgentException(code(all.toString()), action + "失败: " + root, t);
    }

    static String code(String text) {
        if (text.contains("Error parsing HTTP status line") || text.contains("Invalid HTTP")) {
            return Protocol.ERR_BAD_PORT;
        }
        if (text.contains("SSLHandshake") || text.contains("PKIX") || text.contains("CWPKI")
                || text.contains("CertPathValidator") || text.contains("SSLException")) {
            return Protocol.ERR_SSL;
        }
        if (text.contains("ADMN0022E") || text.contains("SECJ") || text.contains("Unauthorized")
                || text.contains("LoginException") || text.contains("authentication")
                || text.contains("faultCode=SOAP-ENV:Client")) {
            return Protocol.ERR_AUTH;
        }
        if (text.contains("SocketTimeout") || text.contains("Read timed out")) {
            return Protocol.ERR_TIMEOUT;
        }
        if (text.contains("ConnectException") || text.contains("Connection refused")
                || text.contains("UnknownHostException") || text.contains("NoRouteToHost")
                || text.contains("ADMC0016E") || text.contains("ConnectorNotAvailable")) {
            return Protocol.ERR_CONNECT_REFUSED;
        }
        if (text.contains("ADMA") || text.contains("AppDeployment")) {
            return Protocol.ERR_DEPLOY_FAILED;
        }
        return Protocol.ERR_INTERNAL;
    }
}
