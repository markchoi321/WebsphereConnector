package com.wasce.core.server;

/**
 * 服务器状态，状态迁移见设计方案 5.1。
 */
public enum ServerState {
    UNKNOWN("未知"),
    STOPPED("已停止"),
    STARTING("启动中"),
    STARTED("已启动"),
    STOPPING("停止中"),
    FAILED("失败");

    private final String label;

    ServerState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 把 agent 返回的状态字符串映射为枚举，无法识别时返回 UNKNOWN */
    public static ServerState fromAgent(String s) {
        if (s == null) {
            return UNKNOWN;
        }
        return switch (s) {
            case "STARTED" -> STARTED;
            case "STARTING" -> STARTING;
            case "STOPPING" -> STOPPING;
            case "STOPPED" -> STOPPED;
            default -> UNKNOWN;
        };
    }
}
