package com.wasce.core.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 进程树工具：按 pid 文件查找进程、先 TERM 后 KILL 结束整棵进程树。
 */
public final class ProcessTree {
    private ProcessTree() {
    }

    /** 读取 pid 文件并返回存活的进程 */
    public static Optional<ProcessHandle> fromPidFile(Path pidFile) {
        try {
            if (!Files.isRegularFile(pidFile)) {
                return Optional.empty();
            }
            long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * 结束进程树：子孙进程先于根进程收到 TERM（让 JVM 执行关闭钩子），宽限期过后对仍存活的进程发送 KILL。
     *
     * @return 所有进程是否都已退出
     */
    public static boolean terminate(ProcessHandle root, long graceMillis) {
        List<ProcessHandle> all = new ArrayList<>(root.descendants().toList());
        java.util.Collections.reverse(all);
        all.add(root);
        all.forEach(ProcessHandle::destroy);
        if (waitAllDead(all, graceMillis)) {
            return true;
        }
        all.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        return waitAllDead(all, 5000);
    }

    private static boolean waitAllDead(List<ProcessHandle> all, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (all.stream().noneMatch(ProcessHandle::isAlive)) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return all.stream().noneMatch(ProcessHandle::isAlive);
    }
}
