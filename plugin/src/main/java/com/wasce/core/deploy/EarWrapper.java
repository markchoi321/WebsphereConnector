package com.wasce.core.deploy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把独立 WAR 或 EJB jar 包装成 EAR。WAS Full profile 的 AppManagement 以 EAR 为单位安装，
 * 包装后 context root 写在 application.xml 中，不依赖 WAR 安装专用的选项。
 */
public final class EarWrapper {
    private EarWrapper() {
    }

    public static void wrap(Path module, String appName, String contextRoot, Path ear) throws IOException {
        String name = module.getFileName().toString();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(ear))) {
            zip.putNextEntry(new ZipEntry("META-INF/application.xml"));
            zip.write(applicationXml(name, appName, contextRoot).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(name));
            Files.copy(module, (OutputStream) zip);
            zip.closeEntry();
        }
    }

    static String applicationXml(String moduleName, String appName, String contextRoot) {
        String module = moduleName.endsWith(".war")
                ? "    <web>\n      <web-uri>" + xml(moduleName) + "</web-uri>\n      <context-root>"
                  + xml(contextRoot == null || contextRoot.isEmpty() ? "/" : contextRoot) + "</context-root>\n    </web>\n"
                : "    <ejb>" + xml(moduleName) + "</ejb>\n";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
               + "<application xmlns=\"http://java.sun.com/xml/ns/javaee\" version=\"5\">\n"
               + "  <display-name>" + xml(appName) + "</display-name>\n"
               + "  <module>\n" + module + "  </module>\n"
               + "</application>\n";
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
