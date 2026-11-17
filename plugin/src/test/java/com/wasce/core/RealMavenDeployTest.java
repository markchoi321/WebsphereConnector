package com.wasce.core;

import com.wasce.core.deploy.AppState;
import com.wasce.core.deploy.BuildCommand;
import com.wasce.core.deploy.DeployItem;
import com.wasce.core.deploy.ReactorModule;
import com.wasce.core.deploy.UpdatePlanner;
import com.wasce.core.process.Ports;
import com.wasce.core.process.ProcessTree;
import com.wasce.core.server.ServerSession;
import com.wasce.core.server.ServerSpec;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 用真实 Maven 构建 devtools/demo-project，验证真实 EAR 的布局匹配、部署和增量更新。
 * 模块别名与 MavenDeployResolver 的规则一致。本机没有 mvn 时跳过。
 */
public class RealMavenDeployTest {
    private static final Path BIN = ServerSessionFakeTest.PROFILE.resolve("installedApps/Cell01/mvndemo.ear");

    @After
    public void tearDown() {
        ProcessTree.fromPidFile(ServerSessionFakeTest.PID).ifPresent(p -> ProcessTree.terminate(p, 3000));
        Ports.waitClosed("localhost", 18880, 5000);
    }

    private static String findMvn() {
        for (String dir : (System.getenv("PATH") + File.pathSeparator + "/opt/homebrew/bin").split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, "mvn"))) {
                return Path.of(dir, "mvn").toString();
            }
        }
        return null;
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    @Test
    public void buildsDeploysAndUpdatesRealEar() throws Exception {
        String mvn = findMvn();
        Assume.assumeTrue("本机没有 mvn", mvn != null);
        Path project = Files.createTempDirectory("wasce-maven");
        copy(Path.of(System.getProperty("wasce.demo.project")), project);
        Path web = project.resolve("demo-web");
        ReactorModule webModule = new ReactorModule("demo-web",
                List.of("demo-web-1.0", "demo-web", "demo-web-1.0", "demo-demo-web-1.0"),
                web.resolve("target/classes"), web.resolve("src/main/webapp"));
        BuildCommand build = new BuildCommand(List.of(mvn, "-B", "-q", "-o", "-f", project.resolve("pom.xml").toString(),
                "-pl", ":demo-ear", "-am", "package", "-DskipTests"), project, Map.of("JAVA_HOME", System.getProperty("java.home")));
        DeployItem item = new DeployItem("mvndemo", project.resolve("demo-ear/target/demo-ear-1.0.ear"), null, false, build,
                List.of(webModule), null);

        ServerSpec b = ServerSessionFakeTest.spec(0, List.of(), Duration.ofSeconds(30), ServerSpec.RunningPolicy.ATTACH);
        ServerSpec spec = new ServerSpec(b.mode(), b.wasHome(), b.profileDir(), b.serverId(), null, 0, null, b.password(), 0,
                List.of(), b.whenRunning(), b.startTimeout(), b.stopTimeout(), b.agentClasspath(), b.workDir(),
                List.of(item), new ServerSessionFakeTest.MemoryHistory(), null, b.logCharset());
        ServerSessionFakeTest.Recorder r = new ServerSessionFakeTest.Recorder(true);
        ServerSession session = new ServerSession(spec, r);
        session.start();
        long deadline = System.currentTimeMillis() + 120000;
        while (session.appStates().get("mvndemo") != AppState.STARTED && System.currentTimeMillis() < deadline && !session.isEnded()) {
            Thread.sleep(200);
        }
        assertEquals(r.text(), AppState.STARTED, session.appStates().get("mvndemo"));
        assertTrue(r.text(), !r.text().contains("未匹配到工程模块"));
        assertTrue(Files.isRegularFile(BIN.resolve("demo-demo-web-1.0.war/WEB-INF/classes/demo/Greeting.class")));

        Path jsp = web.resolve("src/main/webapp/index.jsp");
        Files.writeString(jsp, "<html>updated</html>");
        Files.setLastModifiedTime(jsp, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        session.update(UpdatePlanner.Mode.CLASSES_AND_RESOURCES);
        Path deployed = BIN.resolve("demo-demo-web-1.0.war/index.jsp");
        deadline = System.currentTimeMillis() + 30000;
        while (!Files.readString(deployed).contains("updated") && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertEquals("<html>updated</html>", Files.readString(deployed));
    }
}
