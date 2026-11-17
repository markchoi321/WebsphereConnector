package com.wasce.core.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WAS 安装目录：识别版本、自带 JRE、管理客户端 classpath、profile 列表，以及是否为模拟环境。
 */
public final class WasHome {
    private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");
    /** 按优先级排列的 JRE 候选目录（相对 WAS_HOME） */
    private static final String[] JRE_CANDIDATES = {"java_1.8_64/jre", "java_1.8/jre", "java/8.0/jre", "java/jre", "java"};

    private final Path home;

    public WasHome(Path home) {
        this.home = home.toAbsolutePath().normalize();
    }

    public Path path() {
        return home;
    }

    /** 返回 null 表示有效，否则返回原因 */
    public String validate() {
        if (!Files.isDirectory(home)) {
            return "目录不存在: " + home;
        }
        if (!Files.isRegularFile(home.resolve("properties/version/WAS.product"))) {
            return "不是 WAS 安装目录（缺少 properties/version/WAS.product）: " + home;
        }
        if (findJre() == null) {
            return "找不到 WAS 自带 JRE: " + home;
        }
        return null;
    }

    public String version() {
        try {
            String xml = Files.readString(home.resolve("properties/version/WAS.product"), StandardCharsets.UTF_8);
            Matcher m = VERSION.matcher(xml);
            return m.find() ? m.group(1) : "unknown";
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** 模拟环境（fake-was 生成）：agent 使用 fake 后端 */
    public boolean isFake() {
        return Files.isRegularFile(home.resolve("properties/wasce-fake.properties"));
    }

    public Path findJre() {
        for (String c : JRE_CANDIDATES) {
            Path jre = home.resolve(c);
            if (Files.isRegularFile(javaExecutable(jre))) {
                return jre;
            }
        }
        return null;
    }

    public static Path javaExecutable(Path jreHome) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        return jreHome.resolve(windows ? "bin/java.exe" : "bin/java");
    }

    /** 管理客户端 classpath：优先使用 runtimes 下的瘦客户端（待在真实环境验证是否足够） */
    public List<Path> adminClientClasspath() {
        List<Path> result = new ArrayList<>();
        if (isFake()) {
            return result;
        }
        Path runtimes = home.resolve("runtimes");
        if (Files.isDirectory(runtimes)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(runtimes,
                    "{com.ibm.ws.admin.client_*.jar,com.ibm.ws.orb_*.jar}")) {
                for (Path p : ds) {
                    result.add(p);
                }
            } catch (IOException ignored) {
                // 列目录失败时返回已收集部分，由 agent 启动时报告缺失
            }
        }
        return result;
    }

    public List<Path> profiles() {
        List<Path> result = new ArrayList<>();
        Path dir = home.resolve("profiles");
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p.resolve("config/cells"))) {
                    result.add(p);
                }
            }
        } catch (IOException ignored) {
            // 返回已收集部分
        }
        result.sort(null);
        return result;
    }
}
