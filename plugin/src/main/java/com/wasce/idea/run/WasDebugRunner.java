package com.wasce.idea.run;

import com.intellij.debugger.impl.GenericDebuggerRunner;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;

/**
 * Debug 执行器：启动（或连接）服务器的同时，调试器轮询调试端口，服务器 JVM 开始监听后自动 attach。
 * 轮询随会话结束而停止；HotSwap 由平台调试器的 "Reload classes after compilation" 提供。
 */
public final class WasDebugRunner extends GenericDebuggerRunner {

    @Override
    public String getRunnerId() {
        return "WasCeDebugRunner";
    }

    @Override
    public boolean canRun(String executorId, RunProfile profile) {
        return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof WasRunConfiguration;
    }

    @Override
    protected RunContentDescriptor createContentDescriptor(RunProfileState state, ExecutionEnvironment environment) throws ExecutionException {
        if (!(state instanceof WasRunProfileState s)) {
            return super.createContentDescriptor(state, environment);
        }
        RemoteConnection connection = new RemoteConnection(true, s.debugHost(), String.valueOf(s.debugPort()), false);
        return attachVirtualMachine(state, environment, connection, s.debugPollMillis());
    }
}
