package com.wasce.idea;

import com.intellij.execution.ExecutionResult;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.wasce.core.deploy.AppState;
import com.wasce.core.server.ServerSpec;
import com.wasce.idea.run.WasConfigurationType;
import com.wasce.idea.run.WasDeployment;
import com.wasce.idea.run.WasProgramRunner;
import com.wasce.idea.run.WasRunConfiguration;
import com.wasce.idea.run.WasSessionProcessHandler;
import com.wasce.idea.settings.WasInstallationManager;
import org.jdom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 在 IDE 平台环境中验证运行配置接线：配置校验、状态创建、ProcessHandler 生命周期（对接模拟 WAS）。
 */
public class WasRunConfigurationPlatformTest extends BasePlatformTestCase {
    private static final Path FAKE_HOME = Path.of(System.getProperty("wasce.fakeWas.home"));

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        WasInstallationManager.getInstance().setAll(List.of(new WasInstallationManager.Entry("fake", FAKE_HOME.toString())));
    }

    public void testCheckConfigurationReportsMissingFields() {
        WasRunConfiguration c = create("Local").getConfiguration() instanceof WasRunConfiguration w ? w : null;
        assertNotNull(c);
        try {
            c.checkConfiguration();
            fail("未选择安装时应校验失败");
        } catch (RuntimeConfigurationException expected) {
            assertTrue(expected.getMessage().contains("WAS 安装"));
        }
    }

    public void testRunAndStopLocalServer() throws Exception {
        RunnerAndConfigurationSettings settings = create("Local");
        WasRunConfiguration c = (WasRunConfiguration) settings.getConfiguration();
        c.getOptions().setInstallation("fake");
        c.getOptions().setProfilePath(FAKE_HOME.resolve("profiles/AppSrv01").toString());
        c.getOptions().setServerId("Cell01/Node01/server1");
        c.getOptions().setWhenRunning("FAIL");
        c.checkConfiguration();

        ExecutionEnvironment env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build();
        ExecutionResult result = c.getState(env.getExecutor(), env).execute(env.getExecutor(), new WasProgramRunner());
        assertNotNull(result);
        ProcessHandler handler = result.getProcessHandler();
        StringBuffer out = new StringBuffer();
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(ProcessEvent event, Key outputType) {
                out.append(event.getText());
            }
        });
        try {
            handler.startNotify();
            waitFor(out, "服务器已启动", 60);
            // 状态以 JMX 为准，日志跟踪按 300ms 轮询，启动日志可能稍后才到
            waitFor(out, "WSVR0001I", 5);
            assertFalse(handler.isProcessTerminated());

            handler.destroyProcess();
            assertTrue("停止后应在超时内终止", handler.waitFor(60000));
            assertTrue(out.toString(), out.toString().contains("服务器已停止"));
            assertEquals(Integer.valueOf(0), handler.getExitCode());
        } finally {
            if (!handler.isProcessTerminated()) {
                handler.destroyProcess();
                handler.waitFor(60000);
            }
            Disposer.dispose(result.getExecutionConsole());
        }
    }

    /** 部署项是已构建的归档时不需要 Maven，走完整的部署链路 */
    public void testDeployArchiveAndUpdateActionTarget() throws Exception {
        Path ear = Files.createTempDirectory("wasce-ear").resolve("demo.ear");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(ear))) {
            z.putNextEntry(new ZipEntry("META-INF/application.xml"));
            z.write("<application><module><web><web-uri>w.war</web-uri><context-root>/w</context-root></web></module></application>".getBytes());
            z.closeEntry();
        }
        RunnerAndConfigurationSettings settings = create("Local");
        WasRunConfiguration c = (WasRunConfiguration) settings.getConfiguration();
        c.getOptions().setInstallation("fake");
        c.getOptions().setProfilePath(FAKE_HOME.resolve("profiles/AppSrv01").toString());
        c.getOptions().setServerId("Cell01/Node01/server1");
        c.getOptions().setWhenRunning("FAIL");
        c.getOptions().setDeployments(WasDeployment.encode(List.of(new WasDeployment(ear.toString(), "platformdemo", "", "", false))));

        ExecutionEnvironment env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build();
        ExecutionResult result = c.getState(env.getExecutor(), env).execute(env.getExecutor(), new WasProgramRunner());
        WasSessionProcessHandler handler = (WasSessionProcessHandler) result.getProcessHandler();
        StringBuffer out = new StringBuffer();
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(ProcessEvent event, Key outputType) {
                out.append(event.getText());
            }
        });
        try {
            handler.startNotify();
            waitFor(out, "应用 platformdemo 已启动", 60);
            assertEquals(AppState.STARTED, handler.session().appStates().get("platformdemo"));
            assertEquals("http://localhost:19080/w", handler.appUrl("platformdemo"));
            handler.session().uninstallApp("platformdemo");
            long deadline = System.currentTimeMillis() + 30000;
            while (handler.session().appStates().get("platformdemo") != AppState.NOT_DEPLOYED && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(AppState.NOT_DEPLOYED, handler.session().appStates().get("platformdemo"));
        } finally {
            handler.destroyProcess();
            handler.waitFor(60000);
            Disposer.dispose(result.getExecutionConsole());
        }
    }

    public void testOptionsSurviveSerialization() throws Exception {
        WasRunConfiguration c = (WasRunConfiguration) create("Remote").getConfiguration();
        assertEquals(ServerSpec.Mode.REMOTE, c.mode());
        c.getOptions().setHost("was.example");
        c.getOptions().setFrameUpdate("CLASSES");
        c.getOptions().setDeployments(WasDeployment.encode(List.of(new WasDeployment("web", "app", "/x", "package", true))));
        Element xml = new Element("configuration");
        c.writeExternal(xml);

        WasRunConfiguration loaded = (WasRunConfiguration) create("Remote").getConfiguration();
        loaded.readExternal(xml);
        assertEquals("was.example", loaded.getOptions().getHost());
        assertEquals("CLASSES", loaded.getOptions().getFrameUpdate());
        WasDeployment d = WasDeployment.decode(loaded.getOptions().getDeployments()).get(0);
        assertEquals("/x", d.contextRoot);
        assertTrue(d.deployEjb);
    }

    public void testRemoteValidation() {
        WasRunConfiguration c = (WasRunConfiguration) create("Remote").getConfiguration();
        c.getOptions().setInstallation("fake");
        try {
            c.checkConfiguration();
            fail("未填写主机应校验失败");
        } catch (RuntimeConfigurationException expected) {
            assertTrue(expected.getMessage().contains("主机"));
        }
    }

    private RunnerAndConfigurationSettings create(String factoryId) {
        WasConfigurationType type = WasConfigurationType.getInstance();
        for (var f : type.getConfigurationFactories()) {
            if (f.getId().equals(factoryId)) {
                return RunManager.getInstance(getProject()).createConfiguration("test", f);
            }
        }
        throw new IllegalArgumentException(factoryId);
    }

    private static void waitFor(StringBuffer out, String text, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (!out.toString().contains(text) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertTrue("未等到输出: " + text + "\n" + out, out.toString().contains(text));
    }
}
