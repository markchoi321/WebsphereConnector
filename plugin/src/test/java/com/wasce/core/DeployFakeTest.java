package com.wasce.core;

import com.wasce.core.deploy.AppState;
import com.wasce.core.deploy.BuildCommand;
import com.wasce.core.deploy.DeployHistory;
import com.wasce.core.deploy.DeployItem;
import com.wasce.core.deploy.UpdatePlanner;
import com.wasce.core.process.Ports;
import com.wasce.core.process.ProcessTree;
import com.wasce.core.server.ServerSession;
import com.wasce.core.server.ServerSpec;
import com.wasce.core.server.ServerState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 端到端：会话 + agent + 模拟 WAS 的完整部署与增量更新。
 */
public class DeployFakeTest {
    private static final Path PROFILE = ServerSessionFakeTest.PROFILE;
    private static final Path BIN = PROFILE.resolve("installedApps/Cell01/demo.ear");
    private static final Path LOG = PROFILE.resolve("logs/server1/SystemOut.log");

    private DeployFixtures f;
    private final DeployHistory history = new ServerSessionFakeTest.MemoryHistory();
    private ServerSession session;

    @Before
    public void setUp() throws Exception {
        f = new DeployFixtures();
        clean(PROFILE.resolve("installedApps"));
        clean(PROFILE.resolve("config/cells/Cell01/applications"));
        Files.deleteIfExists(LOG);
    }

    @After
    public void tearDown() {
        ProcessTree.fromPidFile(ServerSessionFakeTest.PID).ifPresent(p -> ProcessTree.terminate(p, 3000));
        Ports.waitClosed("localhost", 18880, 5000);
    }

    private static void clean(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> s = Files.walk(dir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private ServerSpec spec(DeployItem item, int debugPort, List<String> jvmArgs) throws Exception {
        ServerSpec b = ServerSessionFakeTest.spec(debugPort, jvmArgs, Duration.ofSeconds(30), ServerSpec.RunningPolicy.ATTACH);
        return new ServerSpec(b.mode(), b.wasHome(), b.profileDir(), b.serverId(), null, 0, null, b.password(), debugPort,
                jvmArgs, b.whenRunning(), b.startTimeout(), b.stopTimeout(), b.agentClasspath(), b.workDir(),
                item == null ? List.of() : List.of(item), history, null, b.logCharset());
    }

    private ServerSpec remote(DeployItem item) throws Exception {
        ServerSpec b = ServerSessionFakeTest.spec(0, List.of(), Duration.ofSeconds(30), ServerSpec.RunningPolicy.ATTACH);
        return new ServerSpec(ServerSpec.Mode.REMOTE, b.wasHome(), null, null, "localhost", 18880, null, b.password(), 0,
                List.of(), b.whenRunning(), b.startTimeout(), b.stopTimeout(), b.agentClasspath(), b.workDir(),
                List.of(item), history, null, b.logCharset());
    }

    private DeployItem earItem() throws IOException {
        return new DeployItem("demo", f.ear(), null, false, null, f.modules(), null);
    }

    private static void awaitApp(ServerSession s, ServerSessionFakeTest.Recorder r, AppState expected) throws Exception {
        long deadline = System.currentTimeMillis() + 60000;
        while (s.appStates().get("demo") != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(r.text(), expected, s.appStates().get("demo"));
    }

    private ServerSessionFakeTest.Recorder start(ServerSpec spec) throws Exception {
        ServerSessionFakeTest.Recorder r = new ServerSessionFakeTest.Recorder(true);
        session = new ServerSession(spec, r);
        session.start();
        return r;
    }

    private static void touch(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() + 5000));
    }

    private static String logs() throws IOException {
        return Files.readString(LOG);
    }

    private static int count(String text, String s) {
        return (text.length() - text.replace(s, "").length()) / s.length();
    }

    private static boolean waitFor(java.util.function.BooleanSupplier c, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (!c.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        return c.getAsBoolean();
    }

    @Test
    public void deployThenUpdateByChangeKind() throws Exception {
        ServerSessionFakeTest.Recorder r = start(spec(earItem(), 0, List.of()));
        awaitApp(session, r, AppState.STARTED);
        assertTrue(Files.isDirectory(BIN.resolve("web-1.0.war")));
        assertTrue("EJB 模块应展开", Files.isDirectory(BIN.resolve("ejb-1.0.jar")));
        assertTrue(r.text(), r.text().contains("安装应用 demo"));

        // 静态资源：只同步，不重启
        touch(f.web.webappDir().resolve("index.jsp"), "<p>2</p>");
        session.update(UpdatePlanner.Mode.CLASSES_AND_RESOURCES);
        assertTrue(waitFor(() -> contentIs(BIN.resolve("web-1.0.war/index.jsp"), "<p>2</p>"), 30));
        assertEquals(1, count(logs(), "WSVR0221I"));

        // 类：同步并重启应用
        touch(f.web.classesDir().resolve("com/a/Hello.class"), "v2");
        session.update(UpdatePlanner.Mode.CLASSES_AND_RESOURCES);
        assertTrue(r.waitText("重启应用 demo", 30));
        assertTrue(waitFor(() -> countLog("WSVR0221I") == 2, 30));
        assertEquals("v2", Files.readString(BIN.resolve("web-1.0.war/WEB-INF/classes/com/a/Hello.class")));

        // 再次更新：没有改动
        session.update(UpdatePlanner.Mode.CLASSES_AND_RESOURCES);
        assertTrue(r.waitText("没有改动", 30));

        // 描述符：重新部署（归档不变也强制）
        touch(f.web.webappDir().resolve("WEB-INF/web.xml"), "<web-app version='3'/>");
        session.update(UpdatePlanner.Mode.CLASSES_AND_RESOURCES);
        assertTrue(r.text(), r.waitText("更新应用 demo", 30));
        awaitApp(session, r, AppState.STARTED);

        // 应用操作
        session.stopApp("demo");
        awaitApp(session, r, AppState.STOPPED);
        session.startApp("demo");
        awaitApp(session, r, AppState.STARTED);
        session.uninstallApp("demo");
        awaitApp(session, r, AppState.NOT_DEPLOYED);
        assertFalse(Files.exists(BIN));

        session.stop();
        assertTrue(r.ended.await(60, TimeUnit.SECONDS));
    }

    private static boolean contentIs(Path p, String s) {
        try {
            return Files.isRegularFile(p) && Files.readString(p).equals(s);
        } catch (IOException e) {
            return false;
        }
    }

    private static int countLog(String s) {
        try {
            return count(logs(), s);
        } catch (IOException e) {
            return -1;
        }
    }

    @Test
    public void unchangedArchiveIsSkippedOnNextSession() throws Exception {
        DeployItem item = earItem();
        ServerSessionFakeTest.Recorder r = start(spec(item, 0, List.of()));
        awaitApp(session, r, AppState.STARTED);
        session.detach();
        assertTrue(r.ended.await(30, TimeUnit.SECONDS));

        ServerSessionFakeTest.Recorder again = start(spec(item, 0, List.of()));
        awaitApp(session, again, AppState.STARTED);
        assertTrue(again.text(), again.text().contains("未变化且正在运行，跳过部署"));
        assertEquals(1, count(logs(), "ADMA5016I"));

        // 增量映射在跳过部署时同样建立
        touch(f.web.webappDir().resolve("index.jsp"), "<p>3</p>");
        session.update(UpdatePlanner.Mode.RESOURCES);
        assertTrue(waitFor(() -> contentIs(BIN.resolve("web-1.0.war/index.jsp"), "<p>3</p>"), 30));
    }

    @Test
    public void standaloneWarGetsWrappedWithContextRoot() throws Exception {
        ServerSessionFakeTest.Recorder r = start(spec(new DeployItem("demo", f.war(), "/ctx", false, null, f.modules(), null), 0, List.of()));
        awaitApp(session, r, AppState.STARTED);
        assertTrue(Files.isDirectory(BIN.resolve("web-1.0.war/WEB-INF")));
        assertTrue(Files.readString(BIN.resolve("META-INF/application.xml")).contains("<context-root>/ctx</context-root>"));
    }

    @Test
    public void debugSessionHotSwapsAndFallsBackToRestart() throws Exception {
        AtomicBoolean swapOk = new AtomicBoolean(true);
        ServerSessionFakeTest.Recorder r = new ServerSessionFakeTest.Recorder(true) {
            @Override
            public boolean hotSwap() {
                return swapOk.get();
            }
        };
        session = new ServerSession(spec(earItem(), ServerSessionFakeTest.freePort(), List.of()), r);
        session.start();
        awaitApp(session, r, AppState.STARTED);

        touch(f.web.classesDir().resolve("com/a/Hello.class"), "v2");
        session.update(UpdatePlanner.Mode.CLASSES_AND_RESOURCES);
        assertTrue(waitFor(() -> contentIs(BIN.resolve("web-1.0.war/WEB-INF/classes/com/a/Hello.class"), "v2"), 30));
        assertTrue(r.waitText("尝试 HotSwap", 10));
        assertEquals("HotSwap 成功不应重启", 1, count(logs(), "WSVR0221I"));

        swapOk.set(false);
        touch(f.web.classesDir().resolve("com/a/Hello.class"), "v3");
        session.update(UpdatePlanner.Mode.CLASSES_AND_RESOURCES);
        assertTrue(r.waitText("HotSwap 未成功", 30));
        assertTrue(waitFor(() -> countLog("WSVR0221I") == 2, 30));
    }

    @Test
    public void remoteDeployAndPartialUpdate() throws Exception {
        ServerSessionFakeTest.Recorder owner = start(spec(null, 0, List.of()));
        owner.awaitState(ServerState.STARTED, 60);
        ServerSession ownerSession = session;

        ServerSessionFakeTest.Recorder r = start(remote(earItem()));
        awaitApp(session, r, AppState.STARTED);
        touch(f.web.webappDir().resolve("index.jsp"), "<p>remote</p>");
        Files.delete(f.web.classesDir().resolve("app.properties"));
        session.update(UpdatePlanner.Mode.CLASSES_AND_RESOURCES);
        assertTrue(waitFor(() -> contentIs(BIN.resolve("web-1.0.war/index.jsp"), "<p>remote</p>"), 30));
        assertTrue(waitFor(() -> !Files.exists(BIN.resolve("web-1.0.war/WEB-INF/classes/app.properties")), 30));
        assertFalse(Files.exists(BIN.resolve("META-INF/ibm-partialapp-delete.props")));

        // 远程会话的停止只是断开
        session.stop();
        assertTrue(r.ended.await(30, TimeUnit.SECONDS));
        assertTrue(r.detached);
        assertTrue(Ports.isOpen("localhost", 18880, 1000));
        ownerSession.stop();
        assertTrue(owner.ended.await(60, TimeUnit.SECONDS));
    }

    @Test
    public void installFailureKeepsServerRunning() throws Exception {
        ServerSessionFakeTest.Recorder r = start(spec(earItem(), 0, List.of("-Dfakewas.installFail=true")));
        awaitApp(session, r, AppState.FAILED);
        assertEquals(ServerState.STARTED, session.state());
        assertTrue(r.text(), r.text().contains("ADMA0063E"));
        assertFalse(session.isEnded());
    }

    @Test
    public void buildFailureStopsBeforeServerStarts() throws Exception {
        BuildCommand bad = new BuildCommand(List.of("/bin/sh", "-c", "echo 编译错误; exit 3"), f.dir, Map.of());
        ServerSessionFakeTest.Recorder r = start(spec(new DeployItem("demo", f.ear(), null, false, bad, f.modules(), null), 0, List.of()));
        assertTrue(r.ended.await(30, TimeUnit.SECONDS));
        assertTrue(r.text(), r.text().contains("构建失败，退出码 3"));
        assertEquals(ServerState.FAILED, session.state());
        assertFalse("不应启动服务器", Ports.isOpen("localhost", 18880, 300));
    }

    @Test
    public void buildRunsBeforeDeploy() throws Exception {
        Path ear = f.ear();
        Path built = f.dir.resolve("built.ear");
        BuildCommand cmd = new BuildCommand(List.of("/bin/sh", "-c", "cp \"$SRC\" built.ear"), f.dir, Map.of("SRC", ear.toString()));
        ServerSessionFakeTest.Recorder r = start(spec(new DeployItem("demo", built, null, false, cmd, f.modules(), null), 0, List.of()));
        awaitApp(session, r, AppState.STARTED);
        assertTrue(r.text().contains("构建完成"));
    }

    @Test
    public void testConnectionReportsServerInfoAndRefusal() throws Exception {
        ServerSpec spec = spec(null, 0, List.of());
        try {
            ServerSession.testConnection(spec);
            throw new AssertionError("服务器未启动时应失败");
        } catch (com.wasce.core.agent.AgentCallException e) {
            assertEquals(com.wasce.protocol.Protocol.ERR_CONNECT_REFUSED, e.getCode());
        }
        ServerSessionFakeTest.Recorder r = start(spec);
        r.awaitState(ServerState.STARTED, 60);
        assertEquals("server1", ServerSession.testConnection(spec).get(com.wasce.protocol.Protocol.KEY_SERVER));
    }
}
