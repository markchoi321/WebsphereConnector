package com.wasce.fakewas;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模拟应用管理：把 EAR 展开到 installedApps（模块展开为目录，lib 下的 jar 保持为文件），
 * 写入 deployment.xml，并维护应用运行状态。行为按预期的 WAS 布局模拟（设计方案 9.1）。
 */
final class FakeApps {
    private static final Pattern MODULE = Pattern.compile("<(?:web-uri|ejb)>\\s*([^<\\s]+)\\s*</");

    private final File profile;
    private final String cell;
    private final Set<String> running = new HashSet<String>();

    FakeApps(File profile, String cell) {
        this.profile = profile;
        this.cell = cell;
        File[] dirs = new File(profile, "installedApps/" + cell).listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                running.add(d.getName().replaceAll("\\.ear$", ""));
            }
        }
    }

    private File binaries(String app) {
        return new File(profile, "installedApps/" + cell + "/" + app + ".ear");
    }

    private File configDir(String app) {
        return new File(profile, "config/cells/" + cell + "/applications/" + app + ".ear");
    }

    synchronized boolean exists(String app) {
        return binaries(app).isDirectory();
    }

    synchronized boolean isRunning(String app) {
        return running.contains(app);
    }

    synchronized void install(String app, String path) throws IOException {
        FakeWasServer.log("AppManagement", "A", "ADMA5016I: Installation of " + app + " started.");
        if (Boolean.getBoolean("fakewas.installFail")) {
            throw new IOException("ADMA0063E: 模拟安装失败");
        }
        File dir = binaries(app);
        delete(dir);
        delete(configDir(app));
        expand(new File(path), dir);
        expandModules(dir);
        writeDeployment(app);
        FakeWasServer.log("AppManagement", "A", "ADMA5013I: Application " + app + " installed successfully.");
    }

    synchronized void uninstall(String app) throws IOException {
        running.remove(app);
        delete(binaries(app));
        delete(configDir(app));
        FakeWasServer.log("AppManagement", "A", "ADMA5106I: Application " + app + " uninstalled successfully.");
    }

    synchronized void start(String app) throws IOException {
        if (!exists(app)) {
            throw new IOException("ADMA0002E: 应用不存在 " + app);
        }
        FakeWasServer.log("ApplicationMg", "A", "WSVR0200I: Starting application: " + app);
        running.add(app);
        FakeWasServer.log("ApplicationMg", "A", "WSVR0221I: Application started: " + app);
    }

    synchronized void stop(String app) {
        FakeWasServer.log("ApplicationMg", "A", "WSVR0217I: Stopping application: " + app);
        running.remove(app);
        FakeWasServer.log("ApplicationMg", "A", "WSVR0220I: Application stopped: " + app);
    }

    /** 部分更新：覆盖 delta 中的文件，按删除清单删除文件，并重启应用 */
    synchronized void partial(String app, String zip) throws IOException {
        File dir = binaries(app);
        boolean wasRunning = running.contains(app);
        expand(new File(zip), dir);
        File props = new File(dir, "META-INF/ibm-partialapp-delete.props");
        if (props.isFile()) {
            Scanner sc = new Scanner(props, "UTF-8");
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (!line.isEmpty()) {
                    new File(dir, line).delete();
                }
            }
            sc.close();
            props.delete();
        }
        FakeWasServer.log("AppManagement", "A", "ADMA5013I: Partial update of " + app + " completed.");
        if (wasRunning) {
            stop(app);
            start(app);
        }
    }

    private void writeDeployment(String app) throws IOException {
        File f = new File(configDir(app), "deployments/" + app + "/deployment.xml");
        f.getParentFile().mkdirs();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<appdeployment:Deployment xmlns:appdeployment=\"http://www.ibm.com/websphere/appserver/schemas/5.0/appdeployment.xmi\">\n"
                + "  <deployedObject binariesURL=\"$(APP_INSTALL_ROOT)/" + cell + "/" + app + ".ear\" startingWeight=\"1\"/>\n"
                + "</appdeployment:Deployment>\n");
        w.close();
    }

    private static void expandModules(File dir) throws IOException {
        File appXml = new File(dir, "META-INF/application.xml");
        if (!appXml.isFile()) {
            return;
        }
        String xml = new String(Files.readAllBytes(appXml.toPath()), "UTF-8");
        Matcher m = MODULE.matcher(xml);
        while (m.find()) {
            File module = new File(dir, m.group(1));
            if (module.isFile()) {
                File tmp = new File(dir, m.group(1) + ".tmp");
                expand(module, tmp);
                module.delete();
                if (!tmp.renameTo(module)) {
                    throw new IOException("无法展开模块 " + module);
                }
            }
        }
    }

    private static void expand(File zip, File target) throws IOException {
        target.mkdirs();
        ZipFile z = new ZipFile(zip);
        try {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                File out = new File(target, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                InputStream in = z.getInputStream(entry);
                OutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.close();
                in.close();
            }
        } finally {
            z.close();
        }
    }

    private static void delete(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                delete(c);
            }
        }
        f.delete();
    }
}
