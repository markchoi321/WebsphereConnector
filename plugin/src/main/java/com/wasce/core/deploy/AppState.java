package com.wasce.core.deploy;

/**
 * 应用状态。
 */
public enum AppState {
    NOT_DEPLOYED("未部署"),
    BUILDING("构建中"),
    DEPLOYING("部署中"),
    STARTED("已启动"),
    STOPPED("已停止"),
    UPDATING("更新中"),
    FAILED("失败");

    private final String label;

    AppState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
