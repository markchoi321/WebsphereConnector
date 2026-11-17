package com.wasce.core;

import com.wasce.core.process.LogTailer;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;

public class LogTailerTest {

    @Test
    public void followsAppendsTruncationAndPartialLines() throws Exception {
        Path dir = Files.createTempDirectory("tail");
        Path log = dir.resolve("SystemOut.log");
        Files.writeString(log, "历史内容\n");
        List<String> lines = new CopyOnWriteArrayList<>();
        LogTailer tailer = new LogTailer(log, true, StandardCharsets.UTF_8, lines::add);

        append(log, "第一行\n第二");
        waitFor(lines, 1);
        append(log, "行\r\n");
        waitFor(lines, 2);

        // 截断（模拟轮转）后从头读取
        Files.writeString(log, "轮转后\n");
        waitFor(lines, 3);

        append(log, "未结束的行");
        tailer.close();
        assertEquals(List.of("第一行", "第二行", "轮转后", "未结束的行"), lines);
    }

    @Test
    public void waitsForFileCreation() throws Exception {
        Path log = Files.createTempDirectory("tail").resolve("later.log");
        List<String> lines = new CopyOnWriteArrayList<>();
        try (LogTailer ignored = new LogTailer(log, true, StandardCharsets.UTF_8, lines::add)) {
            Thread.sleep(400);
            Files.writeString(log, "新文件\n");
            waitFor(lines, 1);
        }
        assertEquals(List.of("新文件"), lines);
    }

    private static void append(Path p, String s) throws Exception {
        Files.writeString(p, s, StandardOpenOption.APPEND);
    }

    private static void waitFor(List<String> lines, int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (lines.size() < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(n, lines.size());
    }
}
