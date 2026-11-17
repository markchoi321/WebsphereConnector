package com.wasce.idea.run;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.wasce.core.install.WasHome;
import com.wasce.core.profile.ProfileReader;
import com.wasce.core.server.ServerSpec;
import com.wasce.idea.settings.WasInstallationManager;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WebSphere 运行配置。
 */
public final class WasRunConfiguration extends RunConfigurationBase<WasRunConfigurationOptions> {

    WasRunConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
    }

    @Override
    public WasRunConfigurationOptions getOptions() {
        return (WasRunConfigurationOptions) super.getOptions();
    }

    public ServerSpec.Mode mode() {
        return "REMOTE".equals(getOptions().getMode()) ? ServerSpec.Mode.REMOTE : ServerSpec.Mode.LOCAL;
    }

    @Override
    public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new WasSettingsEditor(getProject());
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        WasRunConfigurationOptions o = getOptions();
        WasInstallationManager.Entry entry = WasInstallationManager.getInstance().find(o.getInstallation());
        if (entry == null) {
            throw new RuntimeConfigurationError("请选择 WAS 安装（在 Settings | Tools | WebSphere Servers 中登记）");
        }
        String error = new WasHome(Path.of(entry.home)).validate();
        if (error != null) {
            throw new RuntimeConfigurationError(error);
        }
        if (mode() == ServerSpec.Mode.LOCAL) {
            if (o.getProfilePath().isBlank() || !Files.isDirectory(Path.of(o.getProfilePath(), "config", "cells"))) {
                throw new RuntimeConfigurationError("请选择有效的 profile 目录");
            }
            if (ProfileReader.ServerId.parse(o.getServerId()) == null) {
                throw new RuntimeConfigurationError("请选择服务器（cell/node/server）");
            }
        } else {
            if (o.getHost().isBlank()) {
                throw new RuntimeConfigurationError("请填写远程主机");
            }
            if (o.getSoapPort() <= 0) {
                throw new RuntimeConfigurationError("请填写 SOAP 端口");
            }
        }
        if (o.getDebugPort() <= 0 || o.getDebugPort() > 65535) {
            throw new RuntimeConfigurationError("调试端口无效");
        }
    }

    @Override
    public RunProfileState getState(Executor executor, ExecutionEnvironment environment) {
        return new WasRunProfileState(this, DefaultDebugExecutor.EXECUTOR_ID.equals(executor.getId()));
    }
}
