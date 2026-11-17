package com.wasce.core.profile;

import java.nio.file.Path;
import java.util.Map;

/**
 * 从 profile 配置中解析出的单个服务器信息。
 */
public final class ServerConfig {
    public final Path profileDir;
    public final String cell;
    public final String node;
    public final String server;
    public final String soapHost;
    public final int soapPort;
    /** WC_defaulthost 端口，未配置时为 0 */
    public final int httpPort;
    public final boolean securityEnabled;
    public final Path systemOut;
    public final Path systemErr;
    public final Path pidFile;
    /** server.xml 中 jvmEntries 的 debugMode */
    public final boolean debugMode;
    public final String debugArgs;
    /** 合并后的 WebSphere 变量，用于展开 binariesURL */
    public final Map<String, String> variables;

    public ServerConfig(Path profileDir, String cell, String node, String server, String soapHost, int soapPort,
                        int httpPort, boolean securityEnabled, Path systemOut, Path systemErr, Path pidFile,
                        boolean debugMode, String debugArgs, Map<String, String> variables) {
        this.profileDir = profileDir;
        this.cell = cell;
        this.node = node;
        this.server = server;
        this.soapHost = soapHost;
        this.soapPort = soapPort;
        this.httpPort = httpPort;
        this.securityEnabled = securityEnabled;
        this.systemOut = systemOut;
        this.systemErr = systemErr;
        this.pidFile = pidFile;
        this.debugMode = debugMode;
        this.debugArgs = debugArgs;
        this.variables = variables;
    }

    public String displayName() {
        return cell + "/" + node + "/" + server;
    }
}
