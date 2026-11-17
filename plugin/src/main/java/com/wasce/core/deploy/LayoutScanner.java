package com.wasce.core.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 读取实际构建出的归档来确定布局，不解析 maven-ear-plugin 配置：归档本身才是事实。
 */
public final class LayoutScanner {
    private static final Pattern EJB = Pattern.compile("<ejb>\\s*([^<\\s]+)\\s*</ejb>");
    private static final Pattern CONTEXT = Pattern.compile("<context-root>\\s*([^<\\s]*)\\s*</context-root>");

    private LayoutScanner() {
    }

    public static AppLayout scan(Path archive, String contextRoot, List<ReactorModule> modules) throws IOException {
        List<SyncRoot> roots = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String name = archive.getFileName().toString();
        if (!name.endsWith(".ear")) {
            ReactorModule m = match(strip(name), modules);
            if (m == null) {
                warnings.add("归档 " + name + " 未匹配到工程模块，增量更新不可用");
            } else if (name.endsWith(".war")) {
                addWar(archive, name, m, modules, roots, warnings);
            } else {
                roots.add(new SyncRoot(m.classesDir(), name, SyncRoot.Kind.EJB));
            }
            return new AppLayout(roots, warnings, contextRoot);
        }
        try (ZipFile ear = new ZipFile(archive.toFile())) {
            ZipEntry appXml = ear.getEntry("META-INF/application.xml");
            String xml = "";
            if (appXml != null) {
                try (InputStream in = ear.getInputStream(appXml)) {
                    xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            List<String> ejbs = new ArrayList<>();
            Matcher em = EJB.matcher(xml);
            while (em.find()) {
                ejbs.add(em.group(1));
            }
            Matcher cm = CONTEXT.matcher(xml);
            String context = cm.find() ? cm.group(1) : null;
            for (Enumeration<? extends ZipEntry> e = ear.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String path = entry.getName();
                boolean war = path.endsWith(".war");
                if (entry.isDirectory() || (!war && !path.endsWith(".jar"))) {
                    continue;
                }
                ReactorModule m = match(strip(fileName(path)), modules);
                if (m == null) {
                    if (war || ejbs.contains(path)) {
                        warnings.add("模块 " + path + " 未匹配到工程模块，其改动不会增量同步");
                    }
                    continue;
                }
                if (war) {
                    try (InputStream in = ear.getInputStream(entry)) {
                        addWebRoots(m, path, roots);
                        addWarLibs(in, path, m, modules, roots);
                    }
                } else if (ejbs.contains(path)) {
                    roots.add(new SyncRoot(m.classesDir(), path, SyncRoot.Kind.EJB));
                } else {
                    roots.add(new SyncRoot(m.classesDir(), path, SyncRoot.Kind.JAR));
                }
            }
            return new AppLayout(roots, warnings, context);
        }
    }

    private static void addWar(Path war, String target, ReactorModule m, List<ReactorModule> modules,
                               List<SyncRoot> roots, List<String> warnings) throws IOException {
        addWebRoots(m, target, roots);
        try (InputStream in = java.nio.file.Files.newInputStream(war)) {
            addWarLibs(in, target, m, modules, roots);
        }
    }

    private static void addWebRoots(ReactorModule m, String target, List<SyncRoot> roots) {
        roots.add(new SyncRoot(m.classesDir(), target + "/WEB-INF/classes", SyncRoot.Kind.CLASSES));
        if (m.webappDir() != null) {
            roots.add(new SyncRoot(m.webappDir(), target, SyncRoot.Kind.WEB));
        }
    }

    private static void addWarLibs(InputStream war, String target, ReactorModule self, List<ReactorModule> modules,
                                   List<SyncRoot> roots) throws IOException {
        ZipInputStream zip = new ZipInputStream(war);
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
            String n = e.getName();
            if (n.startsWith("WEB-INF/lib/") && n.endsWith(".jar")) {
                ReactorModule m = match(strip(fileName(n)), modules);
                if (m != null && m != self) {
                    roots.add(new SyncRoot(m.classesDir(), target + "/" + n, SyncRoot.Kind.JAR));
                }
            }
        }
    }

    private static ReactorModule match(String baseName, List<ReactorModule> modules) {
        for (ReactorModule m : modules) {
            if (m.archiveNames().contains(baseName)) {
                return m;
            }
        }
        return null;
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String strip(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }
}
