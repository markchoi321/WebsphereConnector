package com.wasce.core.deploy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 应用改动：本地原子同步到已部署目录，或打成远程 partialapp 所需的 delta 包。
 */
public final class FileSync {
    /** 删除清单在 delta 包中的位置（待在真实 WAS 上验证） */
    public static final String DELETE_LIST = "META-INF/ibm-partialapp-delete.props";

    private FileSync() {
    }

    /** 已部署目录的布局与预期不符（模块没有展开），调用方应改为重新部署 */
    public static final class LayoutMismatchException extends IOException {
        public LayoutMismatchException(String message) {
            super(message);
        }
    }

    public static void apply(ChangeTracker.Changes changes, Path binaries) throws IOException {
        for (ChangeTracker.Entry e : changes.changed()) {
            checkModuleExpanded(e, binaries);
            Path target = binaries.resolve(e.target());
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".wasce", ".tmp");
            try {
                if (e.root().kind() == SyncRoot.Kind.JAR) {
                    packJar(e.source(), tmp);
                } else {
                    Files.copy(e.source(), tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        for (ChangeTracker.Entry e : changes.deleted()) {
            Files.deleteIfExists(binaries.resolve(e.target()));
        }
    }

    /** 模块根必须是展开后的目录；若仍是文件，写入子路径没有意义 */
    private static void checkModuleExpanded(ChangeTracker.Entry e, Path binaries) throws IOException {
        if (e.root().kind() == SyncRoot.Kind.JAR) {
            return;
        }
        String target = e.root().target();
        int slash = target.indexOf('/');
        Path module = binaries.resolve(slash < 0 ? target : target.substring(0, slash));
        if (!Files.isDirectory(module)) {
            throw new LayoutMismatchException("模块未展开为目录: " + module);
        }
    }

    public static void writeDelta(ChangeTracker.Changes changes, Path zipFile) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (ChangeTracker.Entry e : changes.changed()) {
                zip.putNextEntry(new ZipEntry(e.target()));
                if (e.root().kind() == SyncRoot.Kind.JAR) {
                    Path tmp = Files.createTempFile("wasce-jar", ".jar");
                    try {
                        packJar(e.source(), tmp);
                        Files.copy(tmp, zip);
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                } else {
                    Files.copy(e.source(), zip);
                }
                zip.closeEntry();
            }
            if (!changes.deleted().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                changes.deleted().forEach(e -> sb.append(e.target()).append('\n'));
                zip.putNextEntry(new ZipEntry(DELETE_LIST));
                zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    /** 把类输出目录打成 jar，用于替换归档内的反应堆依赖 */
    static void packJar(Path classesDir, Path jar) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(classesDir)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        try (OutputStream os = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (Path f : files) {
                zip.putNextEntry(new ZipEntry(classesDir.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, zip);
                zip.closeEntry();
            }
        }
    }
}
