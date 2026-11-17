package com.wasce.core.process;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在 startServer -script 生成的启动脚本中注入 JVM 参数。
 * 定位方式：找到包含 WsServer 主类的逻辑行（处理续行符），在其中第一个 java 可执行文件之后插入参数。
 * 无法识别脚本结构时抛出 PatchException，由调用方决定降级方案，绝不静默跳过。
 */
public final class StartScriptPatcher {
    public static final String MARKER = "com.ibm.ws.runtime.WsServer";
    private static final Pattern JAVA_TOKEN =
            Pattern.compile("(\"[^\"]*[\\\\/]java(?:\\.exe)?\"|[^\\s\"]*[\\\\/]java(?:\\.exe)?)(?=\\s)");

    private StartScriptPatcher() {
    }

    public static final class PatchException extends Exception {
        public PatchException(String message) {
            super(message);
        }
    }

    public static String patch(String script, List<String> jvmArgs, boolean windows) throws PatchException {
        if (jvmArgs.isEmpty()) {
            return script;
        }
        String[] lines = script.split("\n", -1);
        String continuation = windows ? "^" : "\\";
        // 按续行符把物理行分组为逻辑行
        List<int[]> groups = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < lines.length; i++) {
            if (!stripCr(lines[i]).endsWith(continuation) || i == lines.length - 1) {
                groups.add(new int[]{start, i});
                start = i + 1;
            }
        }
        for (int[] g : groups) {
            StringBuilder logical = new StringBuilder();
            for (int i = g[0]; i <= g[1]; i++) {
                logical.append(lines[i]).append(' ');
            }
            if (!logical.toString().contains(MARKER)) {
                continue;
            }
            for (int i = g[0]; i <= g[1]; i++) {
                Matcher m = JAVA_TOKEN.matcher(lines[i]);
                if (m.find()) {
                    StringBuilder insert = new StringBuilder();
                    for (String a : jvmArgs) {
                        insert.append(' ').append(quote(a, windows));
                    }
                    lines[i] = lines[i].substring(0, m.end()) + insert + lines[i].substring(m.end());
                    return String.join("\n", lines);
                }
            }
            throw new PatchException("找到服务器启动行，但无法定位 java 可执行文件");
        }
        throw new PatchException("启动脚本中找不到 " + MARKER + "，脚本格式无法识别");
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    static String quote(String arg, boolean windows) {
        if (arg.matches("[A-Za-z0-9_./:=,@%+-]+")) {
            return arg;
        }
        if (windows) {
            return "\"" + arg.replace("\"", "\\\"") + "\"";
        }
        return "'" + arg.replace("'", "'\\''") + "'";
    }
}
