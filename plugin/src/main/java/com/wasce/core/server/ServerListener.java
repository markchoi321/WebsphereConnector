package com.wasce.core.server;

import com.wasce.core.deploy.AppState;

/**
 * 会话回调。除 confirmAttach 外都不应阻塞；所有回调都在非 EDT 线程上发生。
 */
public interface ServerListener {
    enum Output {SYSTEM, STDOUT, STDERR}

    /** 文本已包含换行符 */
    void onText(String text, Output kind);

    void onStateChanged(ServerState state);

    /** 本地服务器已在外部运行、策略为询问时调用，可阻塞等待用户选择；返回 true 表示 attach */
    boolean confirmAttach(String message);

    default void onAppState(String app, AppState state) {
    }

    /** 应用部署并启动成功；url 可能为 null */
    default void onAppDeployed(String app, String url) {
    }

    /** 不致使会话结束的操作失败，用于提示用户 */
    default void onOperationFailed(String message) {
    }

    /**
     * 请求调试器 HotSwap 已编译的类，可阻塞。
     *
     * @return 成功返回 true；失败时会话改为重启应用
     */
    default boolean hotSwap() {
        return false;
    }

    /**
     * 会话结束，只会调用一次。
     *
     * @param detached true 表示只断开、服务器保持运行
     */
    void onEnded(boolean detached, int exitCode);
}
