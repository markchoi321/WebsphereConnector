package com.wasce.idea.run;

import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.util.execution.ParametersListUtil;
import com.wasce.core.profile.ProfileReader;
import com.wasce.core.server.ServerSpec;
import com.wasce.idea.settings.WasInstallationManager;
import com.wasce.idea.util.WasCredentials;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 把运行配置转换为 ServerSpec，创建 Console 与会话 ProcessHandler。
 * execute 在 EDT 上调用，这里只组装对象，不做耗时操作；启动在 ProcessHandler.startNotify 后进入会话队列。
 */
public final class WasRunProfileState implements RunProfileState {
    public static final String PLUGIN_ID = "com.wasce.websphere";

    private final WasRunConfiguration configuration;
    private final boolean debug;

    WasRunProfileState(WasRunConfiguration configuration, boolean debug) {
        this.configuration = configuration;
        this.debug = debug;
    }

    public int debugPort() {
        return configuration.getOptions().getDebugPort();
    }

    /** 调试器连接的主机：本地为 127.0.0.1，远程为远程主机 */
    public String debugHost() {
        return configuration.mode() == ServerSpec.Mode.REMOTE ? configuration.getOptions().getHost() : "127.0.0.1";
    }

    /** 调试器轮询连接的最长时间，覆盖整个启动过程 */
    public long debugPollMillis() {
        return (configuration.getOptions().getStartTimeout() + 60L) * 1000L;
    }

    @Override
    public ExecutionResult execute(Executor executor, ProgramRunner<?> runner) throws ExecutionException {
        WasRunConfigurationOptions o = configuration.getOptions();
        ServerSpec spec = buildSpec(true, null);
        TextConsoleBuilder builder = TextConsoleBuilderFactory.getInstance().createBuilder(configuration.getProject());
        builder.addFilter(new WasLogFilter());
        ConsoleView console = builder.getConsole();
        WasSessionProcessHandler handler = new WasSessionProcessHandler(spec, configuration.getProject(),
                configuration.getName(), o.getFrameUpdate(), o.isOpenBrowser());
        console.attachToProcess(handler);
        return new DefaultExecutionResult(console, handler, new WasUpdateAction(handler));
    }

    /**
     * @param withDeployments false 时不解析部署项（测试连接用）
     * @param password        覆盖密码来源，null 表示读取 PasswordSafe
     */
    ServerSpec buildSpec(boolean withDeployments, Supplier<String> password) throws ExecutionException {
        WasRunConfigurationOptions o = configuration.getOptions();
        WasInstallationManager.Entry entry = WasInstallationManager.getInstance().find(o.getInstallation());
        if (entry == null) {
            throw new ExecutionException("找不到 WAS 安装: " + o.getInstallation());
        }
        Project project = configuration.getProject();
        String credentialKey = o.getCredentialKey();
        Path workDir = PathManager.getSystemDir().resolve("wasce")
                .resolve(project.getLocationHash())
                .resolve(configuration.getName().replaceAll("[^A-Za-z0-9._-]", "_"));
        boolean remote = configuration.mode() == ServerSpec.Mode.REMOTE;
        Charset charset = Charset.defaultCharset();
        if (!o.getLogEncoding().isBlank()) {
            try {
                charset = Charset.forName(o.getLogEncoding().trim());
            } catch (IllegalArgumentException e) {
                throw new ExecutionException("无效的日志编码: " + o.getLogEncoding());
            }
        }
        return new ServerSpec(
                configuration.mode(),
                Path.of(entry.home),
                o.getProfilePath().isBlank() ? null : Path.of(o.getProfilePath()),
                ProfileReader.ServerId.parse(o.getServerId()),
                o.getHost(),
                o.getSoapPort(),
                o.getUser(),
                password != null ? password : () -> WasCredentials.load(credentialKey),
                debug ? o.getDebugPort() : 0,
                ParametersListUtil.parse(o.getVmOptions()),
                ServerSpec.RunningPolicy.valueOf(o.getWhenRunning()),
                Duration.ofSeconds(o.getStartTimeout()),
                Duration.ofSeconds(o.getStopTimeout()),
                agentClasspath(),
                workDir,
                withDeployments ? MavenDeployResolver.resolve(project, o) : List.of(),
                WasDeployHistory.getInstance(project),
                remote ? trustStorePath() : null,
                charset);
    }

    static Path trustStorePath() {
        return PathManager.getConfigDir().resolve("wasce/truststore.jks");
    }

    /** 插件目录 lib/agent 下的 agent 与 protocol jar */
    static List<Path> agentClasspath() throws ExecutionException {
        IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
        if (plugin == null) {
            throw new ExecutionException("找不到插件 " + PLUGIN_ID);
        }
        Path dir = plugin.getPluginPath().resolve("lib/agent");
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> jars = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
            if (jars.isEmpty()) {
                throw new ExecutionException("插件安装不完整，缺少 agent: " + dir);
            }
            return jars;
        } catch (IOException e) {
            throw new ExecutionException("插件安装不完整，缺少 agent 目录: " + dir, e);
        }
    }
}
