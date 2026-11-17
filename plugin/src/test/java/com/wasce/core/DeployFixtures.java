package com.wasce.core;

import com.wasce.core.deploy.ReactorModule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 在临时目录中构造一个 web + lib + ejb 的工程，以及对应的 EAR/WAR。
 */
final class DeployFixtures {
    final Path dir;
    final ReactorModule web;
    final ReactorModule lib;
    final ReactorModule ejb;

    DeployFixtures() throws IOException {
        dir = Files.createTempDirectory("wasce-deploy");
        web = new ReactorModule("web", List.of("web-1.0", "web"), dir.resolve("web/target/classes"), dir.resolve("web/src/main/webapp"));
        lib = new ReactorModule("lib", List.of("lib-1.0"), dir.resolve("lib/target/classes"), null);
        ejb = new ReactorModule("ejb", List.of("ejb-1.0"), dir.resolve("ejb/target/classes"), null);
        write(web.classesDir().resolve("com/a/Hello.class"), "v1");
        write(web.classesDir().resolve("app.properties"), "k=v");
        write(web.webappDir().resolve("index.jsp"), "<p>1</p>");
        write(web.webappDir().resolve("WEB-INF/web.xml"), "<web-app/>");
        write(lib.classesDir().resolve("com/l/Lib.class"), "lib1");
        write(ejb.classesDir().resolve("com/e/Bean.class"), "ejb1");
    }

    static void write(Path p, String s) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s);
    }

    List<ReactorModule> modules() {
        return List.of(web, lib, ejb);
    }

    Path war() throws IOException {
        Path war = dir.resolve("web-1.0.war");
        Files.write(war, warBytes());
        return war;
    }

    private byte[] warBytes() throws IOException {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("index.jsp", "<p>1</p>".getBytes(StandardCharsets.UTF_8));
        e.put("WEB-INF/web.xml", "<web-app/>".getBytes(StandardCharsets.UTF_8));
        e.put("WEB-INF/classes/com/a/Hello.class", "v1".getBytes(StandardCharsets.UTF_8));
        e.put("WEB-INF/classes/app.properties", "k=v".getBytes(StandardCharsets.UTF_8));
        e.put("WEB-INF/lib/lib-1.0.jar", zip(Map.of("com/l/Lib.class", "lib1".getBytes(StandardCharsets.UTF_8))));
        e.put("WEB-INF/lib/third-2.jar", zip(Map.of("x.class", new byte[]{1})));
        return zip(e);
    }

    Path ear() throws IOException {
        Path ear = dir.resolve("app-1.0.ear");
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("META-INF/application.xml", ("<application><module><web><web-uri>web-1.0.war</web-uri>"
                + "<context-root>/demo</context-root></web></module>"
                + "<module><ejb>ejb-1.0.jar</ejb></module></application>").getBytes(StandardCharsets.UTF_8));
        e.put("web-1.0.war", warBytes());
        e.put("ejb-1.0.jar", zip(Map.of("com/e/Bean.class", "ejb1".getBytes(StandardCharsets.UTF_8))));
        e.put("lib/third-3.jar", zip(Map.of("y.class", new byte[]{2})));
        Files.write(ear, zip(e));
        return ear;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
