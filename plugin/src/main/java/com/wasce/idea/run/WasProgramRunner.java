package com.wasce.idea.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.runners.RunContentBuilder;
import com.intellij.execution.ui.RunContentDescriptor;

/**
 * Run 执行器：运行配置不是 Java 模块类配置，平台默认 Runner 不接管，需要自带。
 */
public final class WasProgramRunner extends GenericProgramRunner<RunnerSettings> {

    @Override
    public String getRunnerId() {
        return "WasCeRunner";
    }

    @Override
    public boolean canRun(String executorId, RunProfile profile) {
        return DefaultRunExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof WasRunConfiguration;
    }

    @Override
    protected RunContentDescriptor doExecute(RunProfileState state, ExecutionEnvironment environment) throws ExecutionException {
        ExecutionResult result = state.execute(environment.getExecutor(), this);
        return result == null ? null : new RunContentBuilder(result, environment).showRunContent(environment.getContentToReuse());
    }
}
