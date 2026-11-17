package com.wasce.core;

import com.wasce.core.deploy.AppLayout;
import com.wasce.core.deploy.ChangeTracker;
import com.wasce.core.deploy.DeployItem;
import com.wasce.core.deploy.EarWrapper;
import com.wasce.core.deploy.FileSync;
import com.wasce.core.deploy.LayoutScanner;
import com.wasce.core.deploy.LogLine;
import com.wasce.core.deploy.Staging;
import com.wasce.core.deploy.SyncRoot;
import com.wasce.core.deploy.UpdatePlanner;
import com.wasce.core.deploy.UpdatePlanner.Action;
import com.wasce.core.deploy.UpdatePlanner.Mode;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DeployCoreTest {
    private final DeployFixtures f;

    public DeployCoreTest() throws IOException {
        f = new DeployFixtures();
    }

    private static List<String> targets(AppLayout l) {
        return l.roots().stream().map(r -> r.kind() + ":" + r.target()).collect(Collectors.toList());
    }

    @Test
    public void scansEarFromActualArchive() throws Exception {
        AppLayout l = LayoutScanner.scan(f.ear(), null, f.modules());
        assertEquals("/demo", l.contextRoot());
        assertEquals(List.of("CLASSES:web-1.0.war/WEB-INF/classes", "WEB:web-1.0.war",
                "JAR:web-1.0.war/WEB-INF/lib/lib-1.0.jar", "EJB:ejb-1.0.jar"), targets(l));
        assertTrue(l.warnings().isEmpty());
    }

    @Test
    public void scansStandaloneWarAndReportsUnmatched() throws Exception {
        AppLayout l = LayoutScanner.scan(f.war(), "/ctx", f.modules());
        assertEquals("/ctx", l.contextRoot());
        assertTrue(targets(l).contains("CLASSES:web-1.0.war/WEB-INF/classes"));
        AppLayout none = LayoutScanner.scan(f.war(), "/ctx", List.of());
        assertTrue(none.roots().isEmpty());
        assertEquals(1, none.warnings().size());
    }

    @Test
    public void wrapsWarWithContextRoot() throws Exception {
        Path ear = f.dir.resolve("wrapped.ear");
        EarWrapper.wrap(f.war(), "demo", "/my&ctx", ear);
        try (ZipFile z = new ZipFile(ear.toFile())) {
            String xml = new String(z.getInputStream(z.getEntry("META-INF/application.xml")).readAllBytes());
            assertTrue(xml, xml.contains("<web-uri>web-1.0.war</web-uri>"));
            assertTrue(xml, xml.contains("<context-root>/my&amp;ctx</context-root>"));
            assertTrue(z.getEntry("web-1.0.war") != null);
        }
    }

    @Test
    public void fingerprintDependsOnContextRoot() throws Exception {
        Path war = f.war();
        DeployItem a = new DeployItem("a", war, "/a", false, null, List.of(), null);
        DeployItem b = new DeployItem("a", war, "/b", false, null, List.of(), null);
        assertNotEquals(Staging.fingerprint(a), Staging.fingerprint(b));
        assertEquals(Staging.fingerprint(a), Staging.fingerprint(a));
    }

    @Test
    public void stagingReplacesPreviousCopy() throws Exception {
        Path stage = f.dir.resolve("stage");
        DeployItem item = new DeployItem("a", f.ear(), null, false, null, List.of(), null);
        Path first = Staging.stage(item, "aaaaaaaaaaaaaaaa", stage);
        Path second = Staging.stage(item, "bbbbbbbbbbbbbbbb", stage);
        assertFalse(Files.exists(first));
        assertTrue(Files.exists(second));
    }

    private static void touch(Path p, String content) throws IOException {
        Files.writeString(p, content);
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() + 5000));
    }

    @Test
    public void tracksChangesAndCommitsOnlyApplied() throws Exception {
        List<SyncRoot> roots = LayoutScanner.scan(f.ear(), null, f.modules()).roots();
        Map<String, ChangeTracker.Entry> base = ChangeTracker.snapshot(roots);
        assertTrue(base.containsKey("web-1.0.war/index.jsp"));
        touch(f.web.webappDir().resolve("index.jsp"), "<p>2</p>");
        touch(f.web.classesDir().resolve("com/a/Hello.class"), "v2");
        Files.delete(f.web.classesDir().resolve("app.properties"));

        ChangeTracker.Changes c = ChangeTracker.diff(base, ChangeTracker.snapshot(roots));
        assertEquals(2, c.changed().size());
        assertEquals(1, c.deleted().size());

        UpdatePlanner.Plan resources = UpdatePlanner.plan(c, Mode.RESOURCES, false, null);
        assertEquals(Action.SYNC_ONLY, resources.action());
        assertEquals(1, resources.apply().changed().size());
        Map<String, ChangeTracker.Entry> after = ChangeTracker.commit(base, resources.apply());
        ChangeTracker.Changes left = ChangeTracker.diff(after, ChangeTracker.snapshot(roots));
        assertEquals("类改动应留待下次", 1, left.changed().size());
        assertEquals(1, left.deleted().size());
    }

    private ChangeTracker.Changes changeOf(String path, SyncRoot.Kind kind) {
        SyncRoot root = new SyncRoot(Path.of("/s"), "w.war", kind);
        return new ChangeTracker.Changes(List.of(new ChangeTracker.Entry(path, Path.of("/s/x"), root, 1)), List.of());
    }

    @Test
    public void plansCheapestAction() {
        assertEquals(Action.SYNC_ONLY, UpdatePlanner.plan(changeOf("w.war/a.jsp", SyncRoot.Kind.WEB), Mode.CLASSES_AND_RESOURCES, false, null).action());
        assertEquals(Action.RESTART_APP, UpdatePlanner.plan(changeOf("w.war/WEB-INF/classes/A.class", SyncRoot.Kind.CLASSES), Mode.CLASSES_AND_RESOURCES, false, null).action());
        assertEquals(Action.HOTSWAP, UpdatePlanner.plan(changeOf("w.war/WEB-INF/classes/A.class", SyncRoot.Kind.CLASSES), Mode.CLASSES_AND_RESOURCES, true, null).action());
        assertEquals(Action.RESTART_APP, UpdatePlanner.plan(changeOf("w.war/WEB-INF/classes/a.properties", SyncRoot.Kind.CLASSES), Mode.CLASSES_AND_RESOURCES, true, null).action());
        assertEquals(Action.RESTART_APP, UpdatePlanner.plan(changeOf("w.war/WEB-INF/lib/x.jar", SyncRoot.Kind.JAR), Mode.CLASSES_AND_RESOURCES, true, null).action());
        assertEquals(Action.REDEPLOY, UpdatePlanner.plan(changeOf("w.war/WEB-INF/web.xml", SyncRoot.Kind.WEB), Mode.CLASSES_AND_RESOURCES, true, null).action());
        assertEquals(Action.REDEPLOY, UpdatePlanner.plan(changeOf("e.jar/META-INF/ibm-ejb-jar-bnd.xml", SyncRoot.Kind.EJB), Mode.CLASSES_AND_RESOURCES, true, null).action());
        assertEquals(Action.REDEPLOY, UpdatePlanner.plan(changeOf("w.war/a.jsp", SyncRoot.Kind.WEB), Mode.CLASSES_AND_RESOURCES, false, "filtering").action());
        ChangeTracker.Changes none = new ChangeTracker.Changes(List.of(), List.of());
        assertEquals(Action.NOTHING, UpdatePlanner.plan(none, Mode.CLASSES_AND_RESOURCES, false, null).action());
        assertEquals(Action.REDEPLOY, UpdatePlanner.plan(none, Mode.REDEPLOY, false, null).action());
    }

    @Test
    public void syncAppliesFilesJarsAndDeletes() throws Exception {
        List<SyncRoot> roots = LayoutScanner.scan(f.ear(), null, f.modules()).roots();
        Path bin = f.dir.resolve("bin");
        Files.createDirectories(bin.resolve("web-1.0.war/WEB-INF/classes"));
        Files.createDirectories(bin.resolve("web-1.0.war/WEB-INF/lib"));
        Files.createDirectories(bin.resolve("ejb-1.0.jar"));
        Files.writeString(bin.resolve("web-1.0.war/old.jsp"), "x");
        Map<String, ChangeTracker.Entry> now = ChangeTracker.snapshot(roots);
        SyncRoot web = roots.stream().filter(r -> r.kind() == SyncRoot.Kind.WEB).findFirst().orElseThrow();
        ChangeTracker.Entry deleted = new ChangeTracker.Entry("web-1.0.war/old.jsp", Path.of("/none"), web, 1);
        FileSync.apply(new ChangeTracker.Changes(List.copyOf(now.values()), List.of(deleted)), bin);

        assertEquals("<p>1</p>", Files.readString(bin.resolve("web-1.0.war/index.jsp")));
        assertFalse(Files.exists(bin.resolve("web-1.0.war/old.jsp")));
        try (ZipFile jar = new ZipFile(bin.resolve("web-1.0.war/WEB-INF/lib/lib-1.0.jar").toFile())) {
            assertTrue(jar.getEntry("com/l/Lib.class") != null);
        }
        try (var s = Files.walk(bin)) {
            assertTrue("不应残留临时文件", s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test(expected = FileSync.LayoutMismatchException.class)
    public void syncRejectsUnexpandedModule() throws Exception {
        List<SyncRoot> roots = LayoutScanner.scan(f.ear(), null, f.modules()).roots();
        Path bin = f.dir.resolve("bin2");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("web-1.0.war"), "archive");
        FileSync.apply(new ChangeTracker.Changes(List.copyOf(ChangeTracker.snapshot(roots).values()), List.of()), bin);
    }

    @Test
    public void deltaContainsChangesAndDeleteList() throws Exception {
        List<SyncRoot> roots = LayoutScanner.scan(f.ear(), null, f.modules()).roots();
        Map<String, ChangeTracker.Entry> now = ChangeTracker.snapshot(roots);
        Path zip = f.dir.resolve("delta.zip");
        ChangeTracker.Entry jsp = now.get("web-1.0.war/index.jsp");
        FileSync.writeDelta(new ChangeTracker.Changes(List.of(jsp), List.of(now.get("web-1.0.war/WEB-INF/classes/app.properties"))), zip);
        try (ZipFile z = new ZipFile(zip.toFile())) {
            assertTrue(z.getEntry("web-1.0.war/index.jsp") != null);
            String del = new String(z.getInputStream(z.getEntry(FileSync.DELETE_LIST)).readAllBytes());
            assertEquals("web-1.0.war/WEB-INF/classes/app.properties\n", del);
        }
    }

    @Test
    public void parsesLogLines() {
        LogLine.Parsed p = LogLine.parse("[9/27/26 13:06:17:513 AWST] 00000001 WsServerImpl  A   WSVR0001I: Server server1 open");
        assertEquals('A', p.level());
        assertEquals("WSVR0001I", p.messageId());
        LogLine.Parsed noId = LogLine.parse("[9/27/26 13:06:17:513 AWST] 00000013 SystemOut     O   hello");
        assertEquals('O', noId.level());
        assertNull(noId.messageId());
        assertNull(LogLine.parse("at com.foo.Bar(Bar.java:1)"));
    }
}
