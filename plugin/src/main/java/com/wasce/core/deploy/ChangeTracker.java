package com.wasce.core.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 比较源目录快照，得出相对上次同步的改动。key 为已部署目录下的相对路径。
 * JAR 根整体视为一个文件，内容指纹由目录内所有文件的大小和时间组成。
 */
public final class ChangeTracker {
    private ChangeTracker() {
    }

    /** 一个待同步文件；JAR 根的 source 是类输出目录 */
    public record Entry(String target, Path source, SyncRoot root, long stamp) {
    }

    public record Changes(List<Entry> changed, List<Entry> deleted) {
        public boolean isEmpty() {
            return changed.isEmpty() && deleted.isEmpty();
        }
    }

    public static Map<String, Entry> snapshot(List<SyncRoot> roots) throws IOException {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (SyncRoot root : roots) {
            if (!Files.isDirectory(root.source())) {
                continue;
            }
            if (root.kind() == SyncRoot.Kind.JAR) {
                result.put(root.target(), new Entry(root.target(), root.source(), root, directoryStamp(root.source())));
                continue;
            }
            for (Path file : files(root.source())) {
                String rel = root.source().relativize(file).toString().replace('\\', '/');
                // Web 源目录下的 WEB-INF/classes 与 WEB-INF/lib 由 CLASSES 根和构建产物负责
                if (root.kind() == SyncRoot.Kind.WEB && (rel.startsWith("WEB-INF/classes/") || rel.startsWith("WEB-INF/lib/"))) {
                    continue;
                }
                String target = root.target() + "/" + rel;
                result.put(target, new Entry(target, file, root, Files.size(file) * 31 + Files.getLastModifiedTime(file).toMillis()));
            }
        }
        return result;
    }

    public static Changes diff(Map<String, Entry> before, Map<String, Entry> after) {
        List<Entry> changed = new ArrayList<>();
        List<Entry> deleted = new ArrayList<>();
        for (Entry e : after.values()) {
            Entry old = before.get(e.target());
            if (old == null || old.stamp() != e.stamp()) {
                changed.add(e);
            }
        }
        for (Entry e : before.values()) {
            if (!after.containsKey(e.target())) {
                deleted.add(e);
            }
        }
        return new Changes(changed, deleted);
    }

    /** 把已处理的改动写回快照，未处理的（如只更新资源时的类）留待下次 */
    public static Map<String, Entry> commit(Map<String, Entry> before, Changes applied) {
        Map<String, Entry> result = new HashMap<>(before);
        applied.changed().forEach(e -> result.put(e.target(), e));
        applied.deleted().forEach(e -> result.remove(e.target()));
        return result;
    }

    private static long directoryStamp(Path dir) throws IOException {
        long stamp = 0;
        for (Path f : files(dir)) {
            stamp = stamp * 31 + Files.size(f) + Files.getLastModifiedTime(f).toMillis() + f.toString().hashCode();
        }
        return stamp;
    }

    private static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals(".DS_Store"))
                    .sorted().toList();
        }
    }
}
