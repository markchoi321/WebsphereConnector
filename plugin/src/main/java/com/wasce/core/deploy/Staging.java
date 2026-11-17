package com.wasce.core.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 部署前的归档快照：复制到暂存目录后再安装，Maven 下一次构建覆盖原文件不会影响进行中的安装。
 */
public final class Staging {
    private Staging() {
    }

    /** 归档与 context root 共同决定指纹：只改 context root 也必须重新部署 */
    public static String fingerprint(DeployItem item) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(item.archive()), MessageDigest.getInstance("SHA-256"))) {
            byte[] buf = new byte[65536];
            while (in.read(buf) > 0) {
                // 读取过程中累积摘要
            }
            MessageDigest d = ((DigestInputStream) in).getMessageDigest();
            d.update(String.valueOf(item.contextRoot()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** 产出待安装的 EAR，并清理同一应用之前的暂存文件 */
    public static Path stage(DeployItem item, String fingerprint, Path dir) throws IOException {
        Files.createDirectories(dir);
        String prefix = item.appName().replaceAll("[^A-Za-z0-9._-]", "_") + "-";
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, prefix + "*.ear")) {
            for (Path old : ds) {
                Files.deleteIfExists(old);
            }
        }
        Path ear = dir.resolve(prefix + fingerprint.substring(0, 12) + ".ear");
        if (item.isEar()) {
            Files.copy(item.archive(), ear, StandardCopyOption.REPLACE_EXISTING);
        } else {
            EarWrapper.wrap(item.archive(), item.appName(), item.contextRoot(), ear);
        }
        return ear;
    }
}
