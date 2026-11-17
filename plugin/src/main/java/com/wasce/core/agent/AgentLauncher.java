package com.wasce.core.agent;

import com.wasce.core.install.WasHome;
import com.wasce.protocol.Message;
import com.wasce.protocol.MessageIO;
import com.wasce.protocol.Protocol;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 启动 agent 进程并完成握手。agent 主动回连 IDE 在 127.0.0.1 上开的随机端口，并携带一次性 token。
 * 凭据不出现在命令行上，连接建立后通过 connect 操作传递。
 */
public final class AgentLauncher {
    private static final String MAIN_CLASS = "com.wasce.agent.AgentMain";

    private AgentLauncher() {
    }

    /**
     * @param jreHome           运行 agent 的 JRE（WAS 自带）
     * @param classpath         agent 与 protocol 的 jar，以及 IBM 管理客户端 jar
     * @param backend           fake 或 jmx
     * @param systemProperties  传给 agent JVM 的系统属性（如 com.ibm.SOAP.ConfigURL）
     * @param logFile           agent 标准输出写入的文件，用于排查问题
     */
    public record Spec(Path jreHome, List<Path> classpath, String backend, Map<String, String> systemProperties,
                       Path logFile) {
    }

    public static AgentClient launch(Spec spec, Duration timeout) throws AgentCallException {
        String token = UUID.randomUUID().toString();
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            List<String> cmd = new ArrayList<>();
            cmd.add(WasHome.javaExecutable(spec.jreHome()).toString());
            cmd.add("-Xmx256m");
            spec.systemProperties().forEach((k, v) -> cmd.add("-D" + k + "=" + v));
            cmd.add("-cp");
            cmd.add(spec.classpath().stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
            cmd.add(MAIN_CLASS);
            cmd.add(String.valueOf(ss.getLocalPort()));
            cmd.add(token);
            cmd.add(spec.backend());

            Files.createDirectories(spec.logFile().getParent());
            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(spec.logFile().toFile()))
                    .start();

            Socket socket = accept(ss, process, timeout, spec.logFile());
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout((int) timeout.toMillis());
                AgentClient client = new AgentClient(process, socket, spec.backend());
                Message hello = MessageIO.read(client.input());
                if (hello.kind != Message.HELLO || !token.equals(hello.get(Protocol.KEY_TOKEN))) {
                    throw new IOException("agent 握手 token 不匹配");
                }
                if (!String.valueOf(Protocol.VERSION).equals(hello.get(Protocol.KEY_VERSION))) {
                    throw new IOException("agent 协议版本不一致: " + hello.get(Protocol.KEY_VERSION));
                }
                client.sendRaw(Message.hello(Map.of()));
                socket.setSoTimeout(0);
                client.start();
                return client;
            } catch (IOException e) {
                socket.close();
                process.destroyForcibly();
                throw e;
            }
        } catch (IOException e) {
            throw new AgentCallException(Protocol.ERR_AGENT_DIED, "启动 agent 失败: " + e.getMessage(), tail(spec.logFile()));
        }
    }

    private static Socket accept(ServerSocket ss, Process process, Duration timeout, Path logFile) throws IOException {
        ss.setSoTimeout(300);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                return ss.accept();
            } catch (SocketTimeoutException e) {
                if (!process.isAlive()) {
                    throw new IOException("agent 进程已退出，退出码 " + process.exitValue() + "，日志: " + logFile);
                }
            }
        }
        process.destroyForcibly();
        throw new IOException("等待 agent 回连超时，日志: " + logFile);
    }

    /** 读取 agent 日志最后若干行，作为错误详情 */
    private static String tail(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 30), lines.size()));
        } catch (IOException e) {
            return "";
        }
    }
}
