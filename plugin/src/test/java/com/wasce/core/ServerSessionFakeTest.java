package com.wasce.core;

import com.wasce.core.deploy.DeployHistory;
import com.wasce.core.process.Ports;
import com.wasce.core.process.ProcessTree;
import com.wasce.core.profile.ProfileReader;
import com.wasce.core.server.ServerListener;
import com.wasce.core.server.ServerSession;
import com.wasce.core.server.ServerSpec;
import com.wasce.core.server.ServerState;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 端到端测试：ServerSession + agent(fake 后端) + 模拟 WAS，走与真实环境相同的脚本生成、注入、日志跟踪、三级停止路径。
 */
public class ServerSessionFakeTest {
    static final Path PROFILE = ProfileReaderTest.fakeWasHome().resolve("profiles/AppSrv01");
    static final Path PID = PROFILE.resolve("logs/server1/server1.pid");

    @After
    public void killLeftovers() {
        ProcessTree.fromPidFile(PID).ifPresent(p -> ProcessTree.terminate(p, 3000));
        Ports.waitClosed("localhost", 18880, 5000);
    }

    @Test
    public void startInDebugModeThenStop() throws Exception {
        int debugPort = freePort();
        Recorder r = new Recorder(false);
        ServerSession s = new ServerSession(spec(debugPort, List.of(), Duration.ofSeconds(30), ServerSpec.RunningPolicy.FAIL), r);
        s.start();
        r.awaitState(ServerState.STARTED, 60);
        assertTrue("调试端口应已监听", Ports.isOpen("127.0.0.1", debugPort, 1000));
        assertTrue(r.text().contains("已通过 JMX 连接"));
        assertTrue("应跟踪到 SystemOut.log", r.waitText("WSVR0001I", 10));
        assertFalse(s.isExternal());

        s.stop();
        assertTrue(r.ended.await(60, TimeUnit.SECONDS));
        assertEquals(ServerState.STOPPED, s.state());
        assertFalse(r.detached);
        assertTrue(r.text().contains("已通过 JMX 发送停止请求"));
        assertFalse(Files.exists(PID));
        assertFalse(Ports.isOpen("localhost", 18880, 500));
    }

    @Test
    public void attachToRunningServerAndDetach() throws Exception {
        Recorder owner = new Recorder(false);
        ServerSession first = new ServerSession(spec(0, List.of(), Duration.ofSeconds(30), ServerSpec.RunningPolicy.FAIL), owner);
        first.start();
        owner.awaitState(ServerState.STARTED, 60);

        // 策略为 FAIL 时拒绝启动
        Recorder refused = new Recorder(false);
        new ServerSession(spec(0, List.of(), Duration.ofSeconds(30), ServerSpec.RunningPolicy.FAIL), refused).start();
        assertTrue(refused.ended.await(30, TimeUnit.SECONDS));
        assertTrue(refused.text().contains("服务器已在运行"));

        // 策略为 ASK 且用户同意时 attach，断开后服务器保持运行
        Recorder attached = new Recorder(true);
        ServerSession second = new ServerSession(spec(0, List.of(), Duration.ofSeconds(30), ServerSpec.RunningPolicy.ASK), attached);
        second.start();
        attached.awaitState(ServerState.STARTED, 30);
        assertTrue(second.isExternal());
        second.detach();
        assertTrue(attached.ended.await(30, TimeUnit.SECONDS));
        assertTrue(attached.detached);
        assertTrue("断开后服务器应仍在运行", Ports.isOpen("localhost", 18880, 1000));

        first.stop();
        assertTrue(owner.ended.await(60, TimeUnit.SECONDS));
        assertEquals(ServerState.STOPPED, first.state());
    }

    @Test
    public void startupFailureIsReported() throws Exception {
        Recorder r = new Recorder(false);
        ServerSession s = new ServerSession(spec(0, List.of("-Dfakewas.startFail=true"), Duration.ofSeconds(30), ServerSpec.RunningPolicy.FAIL), r);
        s.start();
        assertTrue(r.ended.await(60, TimeUnit.SECONDS));
        assertEquals(ServerState.FAILED, s.state());
        assertEquals(1, r.exitCode);
        assertTrue(r.text().contains("WSVR0009E"));
    }

    @Test
    public void unresponsiveServerIsKilled() throws Exception {
        Recorder r = new Recorder(false);
        ServerSession s = new ServerSession(spec(0, List.of("-Dfakewas.ignoreStop=true"), Duration.ofSeconds(3), ServerSpec.RunningPolicy.FAIL), r);
        s.start();
        r.awaitState(ServerState.STARTED, 60);
        s.stop();
        assertTrue(r.ended.await(90, TimeUnit.SECONDS));
        assertTrue(r.text(), r.text().contains("强制结束服务器进程树"));
        assertEquals(ServerState.STOPPED, s.state());
        assertFalse(Ports.isOpen("localhost", 18880, 500));
    }

    @Test
    public void stopWhileStartingCancelsWait() throws Exception {
        Recorder r = new Recorder(false);
        ServerSession s = new ServerSession(spec(0, List.of("-Dfakewas.startDelayMs=20000"), Duration.ofSeconds(30), ServerSpec.RunningPolicy.FAIL), r);
        s.start();
        r.awaitState(ServerState.STARTING, 30);
        s.stop();
        assertTrue(r.ended.await(60, TimeUnit.SECONDS));
        assertTrue(r.text().contains("启动等待已取消"));
        assertEquals(ServerState.STOPPED, s.state());
    }

    static ServerSpec spec(int debugPort, List<String> jvmArgs, Duration stopTimeout, ServerSpec.RunningPolicy policy) throws Exception {
        List<Path> agentCp = Arrays.stream(System.getProperty("wasce.agent.classpath").split(File.pathSeparator))
                .map(Path::of).collect(Collectors.toList());
        return new ServerSpec(ServerSpec.Mode.LOCAL, ProfileReaderTest.fakeWasHome(), PROFILE,
                new ProfileReader.ServerId("Cell01", "Node01", "server1"), null, 0, null, () -> null,
                debugPort, jvmArgs, policy, Duration.ofSeconds(60), stopTimeout, agentCp,
                Files.createTempDirectory("wasce-work"), List.of(), new MemoryHistory(), null, StandardCharsets.UTF_8);
    }

    static final class MemoryHistory implements DeployHistory {
        private final Map<String, String> map = new ConcurrentHashMap<>();

        @Override
        public String fingerprint(String app) {
            return map.get(app);
        }

        @Override
        public void record(String app, String fingerprint) {
            if (fingerprint == null) {
                map.remove(app);
            } else {
                map.put(app, fingerprint);
            }
        }
    }

    static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    /** 记录会话回调 */
    static class Recorder implements ServerListener {
        private final StringBuffer text = new StringBuffer();
        private final List<ServerState> states = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final boolean attach;
        final CountDownLatch ended = new CountDownLatch(1);
        volatile boolean detached;
        volatile int exitCode = -1;

        Recorder(boolean attach) {
            this.attach = attach;
        }

        @Override
        public void onText(String t, Output kind) {
            text.append(t);
        }

        @Override
        public void onStateChanged(ServerState state) {
            states.add(state);
        }

        @Override
        public boolean confirmAttach(String message) {
            return attach;
        }

        @Override
        public void onEnded(boolean detached, int exitCode) {
            this.detached = detached;
            this.exitCode = exitCode;
            ended.countDown();
        }

        String text() {
            return text.toString();
        }

        boolean waitText(String s, int seconds) throws InterruptedException {
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            while (!text().contains(s) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            return text().contains(s);
        }

        void awaitState(ServerState s, int seconds) throws InterruptedException {
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            while (!states.contains(s) && System.currentTimeMillis() < deadline) {
                if (ended.getCount() == 0) {
                    break;
                }
                Thread.sleep(100);
            }
            assertTrue("未进入状态 " + s + "，状态序列 " + states + "\n输出:\n" + text(), states.contains(s));
        }
    }
}
