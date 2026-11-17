package com.wasce.core.deploy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 WAS 日志行：[时间] 线程 组件 级别 消息ID: 内容。
 */
public final class LogLine {
    private static final Pattern LINE =
            Pattern.compile("^\\[[^\\]]+] [0-9a-fA-F]{8} \\S+\\s+([A-Z])\\s+(?:([A-Z]{4,5}\\d{4}[A-Z]):)?");

    private LogLine() {
    }

    /** @param messageId 没有消息 ID 时为 null；start/end 为其在行内的位置 */
    public record Parsed(char level, String messageId, int start, int end) {
    }

    public static Parsed parse(String line) {
        Matcher m = LINE.matcher(line);
        if (!m.find()) {
            return null;
        }
        return new Parsed(m.group(1).charAt(0), m.group(2), m.start(2), m.end(2));
    }
}
