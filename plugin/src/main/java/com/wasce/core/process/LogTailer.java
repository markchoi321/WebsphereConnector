package com.wasce.core.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;

/**
 * 跟踪日志文件，按完整行回调。
 * 处理：文件尚未创建、文件被截断或轮转（大小变小或 fileKey 变化时从头读）、多字节字符跨读取边界。
 */
public final class LogTailer implements AutoCloseable {
    private static final long POLL_MILLIS = 300;

    private final Path file;
    private final Charset charset;
    private final Consumer<String> sink;
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private long position;
    private Object fileKey;
    private volatile boolean running = true;
    private final Thread thread;

    /**
     * @param fromEnd true 表示跳过已有内容，只输出之后追加的行
     */
    public LogTailer(Path file, boolean fromEnd, Charset charset, Consumer<String> sink) {
        this.file = file;
        this.charset = charset;
        this.sink = sink;
        if (fromEnd) {
            try {
                BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
                position = a.size();
                fileKey = a.fileKey();
            } catch (IOException ignored) {
                // 文件尚不存在，从头开始
            }
        }
        thread = new Thread(this::run, "wasce-tail-" + file.getFileName());
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        while (running) {
            poll();
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    synchronized void poll() {
        BasicFileAttributes a;
        try {
            a = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException e) {
            return;
        }
        Object key = a.fileKey();
        if (a.size() < position || (fileKey != null && key != null && !fileKey.equals(key))) {
            flushPartial();
            position = 0;
        }
        fileKey = key;
        if (a.size() == position) {
            return;
        }
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            ch.position(position);
            ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            int n;
            while ((n = ch.read(buf)) > 0) {
                position += n;
                byte[] bytes = buf.array();
                for (int i = 0; i < n; i++) {
                    if (bytes[i] == '\n') {
                        emit();
                    } else {
                        partial.write(bytes[i]);
                    }
                }
                buf.clear();
            }
        } catch (IOException ignored) {
            // 文件被轮转时可能短暂不可读，下次轮询重试
        }
    }

    private void emit() {
        String line = partial.toString(charset);
        partial.reset();
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        sink.accept(line);
    }

    private void flushPartial() {
        if (partial.size() > 0) {
            emit();
        }
    }

    /** 停止跟踪，读完剩余内容并输出不完整的最后一行 */
    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            poll();
            flushPartial();
        }
    }
}
