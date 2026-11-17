package com.wasce.core.server;

import com.wasce.core.agent.AgentCallException;
import com.wasce.core.agent.AgentClient;
import com.wasce.core.agent.AgentLauncher;
import com.wasce.core.deploy.AppLayout;
import com.wasce.core.deploy.AppState;
import com.wasce.core.deploy.ChangeTracker;
import com.wasce.core.deploy.DeployItem;
import com.wasce.core.deploy.FileSync;
import com.wasce.core.deploy.LayoutScanner;
import com.wasce.core.deploy.Staging;
import com.wasce.core.deploy.TrustStores;
import com.wasce.core.deploy.UpdatePlanner;
import com.wasce.core.install.WasHome;
import com.wasce.core.process.LogTailer;
import com.wasce.core.process.Ports;
import com.wasce.core.process.ProcessTree;
import com.wasce.core.process.StartScriptPatcher;
import com.wasce.core.profile.ProfileReader;
import com.wasce.core.profile.ServerConfig;
import com.wasce.protocol.Message;
import com.wasce.protocol.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次服务器会话：本地启动或 attach，或远程连接，直到停止或断开。
 * 所有操作在单线程执行器上串行执行，状态只在该线程上修改；
 * stop/detach 会先设置取消标志，使正在等待启动的操作尽快让出队列。
 */
public final class ServerSession {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    private static final ScheduledExecutorService MONITOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wasce-monitor");
        t.setDaemon(true);
        return t;
    });
    private static final long MONITOR_MILLIS = 5000;
    private static final Duration AGENT_CALL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEPLOY_TIMEOUT = Duration.ofMinutes(40);
    private static final long BUILD_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);

    /** 一个部署项的运行时信息，只在执行器线程上访问 */
    private static final class AppRuntime {
        final DeployItem item;
        AppLayout layout;
        Map<String, ChangeTracker.Entry> snapshot = Map.of();
        Path binaries;

        AppRuntime(DeployItem item) {
            this.item = item;
        }
    }

    private final ServerSpec spec;
    private final ServerListener listener;
    private final ExecutorService executor;

    private volatile ServerState state = ServerState.UNKNOWN;
    private volatile boolean cancelStart;
    private volatile boolean ended;
    private volatile boolean external;
    private volatile boolean startedMarkerSeen;
    private volatile String failMarkerLine;

    // 以下字段只在执行器线程上访问
    private ServerConfig config;
    private String host;
    private int soapPort;
    private Process process;
    private AgentClient agent;
    private boolean agentConnected;
    private boolean agentUnusable;
    private long lastConnectAttempt;
    private final List<LogTailer> tailers = new ArrayList<>();
    private ScheduledFuture<?> monitor;
    private final AtomicBoolean monitorQueued = new AtomicBoolean();
    private final AtomicBoolean updateQueued = new AtomicBoolean();
    private final Map<String, AppRuntime> apps = new LinkedHashMap<>();
    /** 供 UI 读取的应用状态快照 */
    private final Map<String, AppState> appStates = new ConcurrentHashMap<>();

    public ServerSession(ServerSpec spec, ServerListener listener) {
        this.spec = spec;
        this.listener = listener;
        for (DeployItem item : spec.deployments()) {
            apps.put(item.appName(), new AppRuntime(item));
            appStates.put(item.appName(), AppState.NOT_DEPLOYED);
        }
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wasce-session-" + spec.displayName());
            t.setDaemon(true);
            return t;
        });
    }

    public ServerState state() {
        return state;
    }

    /** 是否连接的是外部已运行的服务器（非本会话启动） */
    public boolean isExternal() {
        return external || spec.mode() == ServerSpec.Mode.REMOTE;
    }

    public boolean isEnded() {
        return ended;
    }

    public void start() {
        submit(this::doStart);
    }

    /** 停止服务器；远程模式下等同于断开 */
    public void stop() {
        cancelStart = true;
        submit(spec.mode() == ServerSpec.Mode.REMOTE ? this::doDetach : this::doStop);
    }

    /** 只断开，服务器保持运行 */
    public void detach() {
        cancelStart = true;
        submit(this::doDetach);
    }

    /** 增量更新。队列中已有等待的更新时合并，不重复执行 */
    public void update(UpdatePlanner.Mode mode) {
        if (updateQueued.compareAndSet(false, true)) {
            submit(() -> {
                updateQueued.set(false);
                doUpdate(mode);
            });
        }
    }

    /** 重新构建并部署一个应用 */
    public void redeploy(String app) {
        submit(() -> forApp(app, rt -> {
            if (build(rt)) {
                deployOne(rt, true);
            }
        }));
    }

    public void startApp(String app) {
        submit(() -> forApp(app, rt -> appCommand(rt, Protocol.OP_START_APP)));
    }

    public void stopApp(String app) {
        submit(() -> forApp(app, rt -> appCommand(rt, Protocol.OP_STOP_APP)));
    }

    public void restartApp(String app) {
        submit(() -> forApp(app, this::restart));
    }

    public void uninstallApp(String app) {
        submit(() -> forApp(app, rt -> appCommand(rt, Protocol.OP_UNINSTALL)));
    }

    public Map<String, AppState> appStates() {
        return new LinkedHashMap<>(appStates);
    }

    public ServerSpec spec() {
        return spec;
    }

    private void submit(Runnable task) {
        if (ended) {
            return;
        }
        executor.execute(() -> {
            if (ended) {
                return;
            }
            try {
                task.run();
            } catch (Throwable t) {
                fail("内部错误: " + t);
            }
        });
    }

    // ---------------------------------------------------------------- 启动

    private void doStart() {
        // 先构建：编译失败时不必启动服务器
        for (AppRuntime rt : apps.values()) {
            if (cancelStart) {
                return;
            }
            if (!build(rt)) {
                fail("应用 " + rt.item.appName() + " 构建失败");
                return;
            }
        }
        if (spec.mode() == ServerSpec.Mode.REMOTE) {
            doConnectRemote();
            return;
        }
        try {
            sys("读取 profile 配置: " + spec.profileDir());
            config = ProfileReader.read(spec.profileDir(), spec.serverId());
            host = config.soapHost;
            soapPort = config.soapPort;
            sys("服务器 " + config.displayName() + "，SOAP " + host + ":" + soapPort
                    + (config.httpPort > 0 ? "，HTTP " + config.httpPort : "")
                    + "，全局安全" + (config.securityEnabled ? "已开启" : "未开启"));
            if (config.securityEnabled && isBlank(spec.user())) {
                sys("警告：服务器开启了全局安全，但运行配置未填写用户名，JMX 连接和停止可能失败");
            }

            if (isRunning()) {
                if (!decideAttach()) {
                    fail("服务器已在运行，已按设置取消启动");
                    return;
                }
                attachExternal();
                return;
            }

            List<String> jvmArgs = new ArrayList<>(spec.extraJvmArgs());
            if (spec.isDebug()) {
                if (config.debugMode) {
                    int port = ProfileReader.debugPortOf(config.debugArgs);
                    if (port != spec.debugPort()) {
                        fail("server.xml 已开启调试模式（" + config.debugArgs + "），端口为 " + port
                                + "，与运行配置的调试端口 " + spec.debugPort() + " 不一致，请修改其中之一");
                        return;
                    }
                    sys("server.xml 已开启调试模式，使用其调试参数，不再注入");
                } else {
                    if (Ports.isOpen("127.0.0.1", spec.debugPort(), 300)) {
                        fail("调试端口 " + spec.debugPort() + " 已被占用，请在运行配置中更换");
                        return;
                    }
                    jvmArgs.add(0, "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + spec.debugPort());
                }
            }

            Path script = generateScript();
            if (!jvmArgs.isEmpty()) {
                String original = Files.readString(script, Charset.defaultCharset());
                try {
                    Files.writeString(script, StartScriptPatcher.patch(original, jvmArgs, WINDOWS), Charset.defaultCharset());
                    sys("已注入 JVM 参数: " + String.join(" ", jvmArgs));
                } catch (StartScriptPatcher.PatchException e) {
                    fail("无法在启动脚本中注入 JVM 参数: " + e.getMessage() + "（脚本: " + script + "）");
                    return;
                }
            }

            startTailers();
            launch(script);
            setState(ServerState.STARTING);
            waitStarted();
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

    private boolean isRunning() {
        Optional<ProcessHandle> pid = ProcessTree.fromPidFile(config.pidFile)
                .filter(p -> p.info().command().map(c -> c.contains("java")).orElse(true));
        boolean portOpen = Ports.isOpen(host, soapPort, 500);
        if (pid.isEmpty() && !portOpen && Files.exists(config.pidFile)) {
            sys("发现残留的 pid 文件，对应进程已不存在，忽略: " + config.pidFile);
        }
        return pid.isPresent() || portOpen;
    }

    private boolean decideAttach() {
        String msg = "服务器 " + config.displayName() + " 已在运行（不是由本次会话启动）。是否直接连接？";
        return switch (spec.whenRunning()) {
            case ATTACH -> true;
            case FAIL -> false;
            case ASK -> listener.confirmAttach(msg);
        };
    }

    private void attachExternal() {
        external = true;
        sys("连接到已在运行的服务器");
        startTailers();
        ensureAgentConnected(true);
        ServerState s = queryState();
        setState(s != null ? s : ServerState.STARTED);
        if (spec.isDebug()) {
            sys("调试器将持续尝试连接端口 " + spec.debugPort() + "；若该服务器未以调试模式启动，将无法连接");
        }
        startMonitor();
        deployAll();
    }

    private Path generateScript() throws IOException {
        Files.createDirectories(spec.workDir());
        Path script = spec.workDir().resolve("start_" + config.server + (WINDOWS ? ".bat" : ".sh"));
        Files.deleteIfExists(script);
        List<String> cmd = new ArrayList<>(profileScript("startServer"));
        cmd.add(config.server);
        cmd.add("-script");
        cmd.add(script.toString());
        sys("生成启动脚本: " + String.join(" ", cmd));
        int code = runTool(cmd, Duration.ofSeconds(180));
        if (code != 0 || !Files.isRegularFile(script)) {
            throw new IOException("生成启动脚本失败，退出码 " + code);
        }
        script.toFile().setExecutable(true);
        return script;
    }

    private void launch(Path script) throws IOException {
        List<String> cmd = WINDOWS ? List.of("cmd.exe", "/c", script.toString()) : List.of("/bin/sh", script.toString());
        process = new ProcessBuilder(cmd).directory(config.profileDir.toFile()).start();
        pump(process.getInputStream(), ServerListener.Output.STDOUT);
        pump(process.getErrorStream(), ServerListener.Output.STDERR);
        process.onExit().thenRun(() -> submit(this::onProcessExit));
        sys("服务器进程已启动，pid " + process.pid());
    }

    private void waitStarted() {
        long begin = System.currentTimeMillis();
        long deadline = begin + spec.startTimeout().toMillis();
        while (true) {
            if (cancelStart) {
                sys("启动等待已取消");
                return;
            }
            if (!process.isAlive()) {
                closeTailers();
                fail("服务器进程在启动过程中退出，退出码 " + process.exitValue()
                        + (failMarkerLine != null ? "，日志: " + failMarkerLine : ""));
                return;
            }
            if (failMarkerLine != null) {
                fail("启动日志中出现失败消息: " + failMarkerLine);
                return;
            }
            if (Ports.isOpen(host, soapPort, 300)) {
                ensureAgentConnected(false);
            }
            ServerState s = queryState();
            if (s == ServerState.STARTED || (s == null && startedMarkerSeen)) {
                setState(ServerState.STARTED);
                sys("服务器已启动，用时 " + (System.currentTimeMillis() - begin) / 1000 + " 秒"
                        + (s == null ? "（依据启动日志判断）" : ""));
                startMonitor();
                deployAll();
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("启动超时（" + spec.startTimeout().toSeconds() + " 秒）");
                return;
            }
            sleep(1000);
        }
    }

    // ---------------------------------------------------------------- 远程

    private void doConnectRemote() {
        host = spec.host();
        soapPort = spec.soapPort();
        external = true;
        sys("连接远程服务器 " + host + ":" + soapPort);
        if (!ensureAgentConnected(true)) {
            fail(agentUnusable ? "远程模式需要 JMX 后端，当前 agent 不可用" : "无法连接远程服务器");
            return;
        }
        ServerState s = queryState();
        setState(s != null ? s : ServerState.UNKNOWN);
        startMonitor();
        deployAll();
    }

    // ---------------------------------------------------------------- 停止与断开

    private void doStop() {
        stopMonitor();
        if (process == null && !external) {
            // 启动失败或尚未启动进程
            finish(false, 0);
            return;
        }
        setState(ServerState.STOPPING);
        boolean stopped = false;

        // 第一级：JMX
        if (agentConnected && !agentUnusable) {
            try {
                agent.call(Protocol.OP_STOP_SERVER, Map.of(), AGENT_CALL_TIMEOUT);
                sys("已通过 JMX 发送停止请求，等待服务器退出");
                stopped = waitServerGone(spec.stopTimeout());
                if (!stopped) {
                    sys("通过 JMX 停止超时（" + spec.stopTimeout().toSeconds() + " 秒）");
                }
            } catch (AgentCallException e) {
                sys("通过 JMX 停止失败: " + describe(e));
            }
        }

        // 第二级：stopServer 脚本
        if (!stopped) {
            List<String> cmd = new ArrayList<>(profileScript("stopServer"));
            cmd.add(config.server);
            String password = isBlank(spec.user()) ? null : spec.password().get();
            if (!isBlank(spec.user())) {
                cmd.addAll(List.of("-username", spec.user(), "-password", password == null ? "" : password));
                sys("注意：stopServer 脚本需要在命令行传递凭据，会短暂出现在进程列表中");
            }
            sys("使用 stopServer 脚本停止");
            int code = runTool(cmd, Duration.ofSeconds(90));
            stopped = waitServerGone(Duration.ofSeconds(code == 0 ? 30 : 5));
        }

        // 第三级：结束进程树
        if (!stopped) {
            Optional<ProcessHandle> root = process != null ? Optional.of(process.toHandle()) : ProcessTree.fromPidFile(config.pidFile);
            if (root.isPresent()) {
                sys("强制结束服务器进程树，pid " + root.get().pid());
                stopped = ProcessTree.terminate(root.get(), 10000) && waitServerGone(Duration.ofSeconds(10));
            }
        }

        if (!stopped) {
            setState(ServerState.FAILED);
            sys("停止失败：服务器进程或端口仍然存在，请手动检查");
            finish(false, 1);
            return;
        }
        if (Files.exists(config.pidFile) && ProcessTree.fromPidFile(config.pidFile).isEmpty()) {
            try {
                Files.deleteIfExists(config.pidFile);
            } catch (IOException ignored) {
                // 残留 pid 文件不影响下次启动判定
            }
        }
        setState(ServerState.STOPPED);
        sys("服务器已停止");
        finish(false, process != null && !process.isAlive() ? process.exitValue() : 0);
    }

    /** 等待服务器退出：进程退出（或 pid 失效）且 SOAP 端口释放 */
    private boolean waitServerGone(Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            boolean procGone = process != null ? !process.isAlive() : ProcessTree.fromPidFile(config.pidFile).isEmpty();
            if (procGone) {
                return Ports.waitClosed(host, soapPort, Math.max(1000, deadline - System.currentTimeMillis()));
            }
            sleep(300);
        }
        return false;
    }

    private void doDetach() {
        stopMonitor();
        sys("已断开连接，服务器保持运行");
        finish(true, 0);
    }

    private void onProcessExit() {
        if (state == ServerState.STOPPING || state == ServerState.STARTING) {
            // 停止流程和启动等待会自行处理进程退出
            return;
        }
        stopMonitor();
        closeTailers();
        int code = process.exitValue();
        sys("服务器进程已退出，退出码 " + code);
        setState(code == 0 ? ServerState.STOPPED : ServerState.FAILED);
        finish(false, code);
    }

    // ---------------------------------------------------------------- 状态监控

    private void startMonitor() {
        monitor = MONITOR.scheduleWithFixedDelay(() -> {
            if (monitorQueued.compareAndSet(false, true)) {
                submit(() -> {
                    monitorQueued.set(false);
                    monitorTick();
                });
            }
        }, MONITOR_MILLIS, MONITOR_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void stopMonitor() {
        if (monitor != null) {
            monitor.cancel(false);
            monitor = null;
        }
    }

    private void monitorTick() {
        if (state == ServerState.STOPPING) {
            return;
        }
        if (agent != null && !agent.isAlive()) {
            sys("agent 连接已断开，正在重连");
            agent = null;
            agentConnected = false;
        }
        ensureAgentConnected(false);
        ServerState s = queryState();
        if (s != null) {
            setState(s);
            refreshApps();
            return;
        }
        // 没有可用的 JMX 状态时，外部服务器按进程与端口判断是否仍在运行
        if (external && spec.mode() == ServerSpec.Mode.LOCAL && !isRunning()) {
            stopMonitor();
            closeTailers();
            setState(ServerState.STOPPED);
            sys("服务器已在外部停止");
            finish(false, 0);
        } else if (external && spec.mode() == ServerSpec.Mode.REMOTE && !Ports.isOpen(host, soapPort, 1000)) {
            setState(ServerState.UNKNOWN);
        }
    }

    // ---------------------------------------------------------------- agent

    /**
     * 确保 agent 已启动并连接服务器。启动失败或后端未实现时标记为不可用，之后改用日志与端口判断。
     *
     * @param force 为 false 时，两次连接尝试至少间隔 3 秒
     */
    private boolean ensureAgentConnected(boolean force) {
        if (agentUnusable) {
            return false;
        }
        if (agentConnected) {
            return true;
        }
        if (!force && System.currentTimeMillis() - lastConnectAttempt < 3000) {
            return false;
        }
        lastConnectAttempt = System.currentTimeMillis();
        if (agent == null) {
            AgentLauncher.Spec agentSpec = agentSpec(spec, config);
            if (agentSpec == null) {
                agentUnusable = true;
                sys("找不到 WAS 自带 JRE，无法启动 agent，改用日志和端口判断状态");
                return false;
            }
            try {
                agent = AgentLauncher.launch(agentSpec, Duration.ofSeconds(30));
                agent.setEventListener(this::onAgentEvent);
                sys("agent 已启动（后端 " + agentSpec.backend() + "），日志: " + agentSpec.logFile());
            } catch (AgentCallException e) {
                agentUnusable = true;
                sys("agent 启动失败，改用日志和端口判断状态: " + e.getMessage()
                        + (e.getDetail().isEmpty() ? "" : "\n" + e.getDetail()));
                return false;
            }
        }
        try {
            agent.call(Protocol.OP_CONNECT, connectParams(spec, host, soapPort, config), Duration.ofSeconds(60));
            agentConnected = true;
            sys("已通过 JMX 连接到 " + host + ":" + soapPort);
            return true;
        } catch (AgentCallException e) {
            if (Protocol.ERR_NOT_IMPLEMENTED.equals(e.getCode()) || Protocol.ERR_CLIENT_MISSING.equals(e.getCode())) {
                agentUnusable = true;
                sys(describe(e) + "。改用日志和端口判断状态，部署不可用");
            } else if (force || state != ServerState.STARTING) {
                sys("JMX 连接失败: " + describe(e));
            }
            return false;
        }
    }

    private void onAgentEvent(Message m) {
        if (Protocol.EV_DEPLOY_PROGRESS.equals(m.name)) {
            sys("[部署 " + m.get(Protocol.KEY_APP) + "] " + m.get(Protocol.KEY_MESSAGE));
        }
    }

    /** 组装 agent 启动参数；找不到 JRE 时返回 null */
    private static AgentLauncher.Spec agentSpec(ServerSpec spec, ServerConfig config) {
        WasHome home = new WasHome(spec.wasHome());
        Path jre = home.findJre();
        if (jre == null) {
            return null;
        }
        List<Path> cp = new ArrayList<>(spec.agentClasspath());
        cp.addAll(home.adminClientClasspath());
        Map<String, String> props = new LinkedHashMap<>();
        if (!home.isFake()) {
            // 证书不受信任时直接报错，不让 IBM 客户端在控制台等待输入
            props.put("com.ibm.ssl.enableSignerExchangePrompt", "false");
            if (config != null) {
                props.put("com.ibm.SOAP.ConfigURL", config.profileDir.resolve("properties/soap.client.props").toUri().toString());
                props.put("com.ibm.SSL.ConfigURL", config.profileDir.resolve("properties/ssl.client.props").toUri().toString());
            }
        }
        Path log = spec.workDir().resolve("agent-" + spec.displayName().replaceAll("[^A-Za-z0-9._-]", "_") + ".log");
        return new AgentLauncher.Spec(jre, cp, home.isFake() ? "fake" : "jmx", props, log);
    }

    private static Map<String, String> connectParams(ServerSpec spec, String host, int port, ServerConfig config) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(Protocol.KEY_HOST, host);
        params.put(Protocol.KEY_PORT, String.valueOf(port));
        params.put(Protocol.KEY_SECURITY, String.valueOf(!isBlank(spec.user())));
        if (!isBlank(spec.user())) {
            params.put(Protocol.KEY_USER, spec.user());
            String pwd = spec.password().get();
            params.put(Protocol.KEY_PASSWORD, pwd == null ? "" : pwd);
        }
        if (config != null) {
            params.put(Protocol.KEY_SERVER_NAME, config.server);
        }
        if (spec.trustStore() != null && Files.isRegularFile(spec.trustStore())) {
            params.put(Protocol.KEY_TRUST_STORE, spec.trustStore().toString());
            params.put(Protocol.KEY_TRUST_PASSWORD, TrustStores.PASSWORD);
        }
        return params;
    }

    /** 测试连接：独立启动一个 agent，连接后读取服务器信息并退出。阻塞，需在后台线程调用 */
    public static Map<String, String> testConnection(ServerSpec spec) throws AgentCallException, IOException {
        ServerConfig config = spec.mode() == ServerSpec.Mode.LOCAL ? ProfileReader.read(spec.profileDir(), spec.serverId()) : null;
        String host = config != null ? config.soapHost : spec.host();
        int port = config != null ? config.soapPort : spec.soapPort();
        AgentLauncher.Spec agentSpec = agentSpec(spec, config);
        if (agentSpec == null) {
            throw new AgentCallException(Protocol.ERR_CLIENT_MISSING, "找不到 WAS 自带 JRE", null);
        }
        try (AgentClient client = AgentLauncher.launch(agentSpec, Duration.ofSeconds(30))) {
            client.call(Protocol.OP_CONNECT, connectParams(spec, host, port, config), Duration.ofSeconds(60));
            return client.call(Protocol.OP_SERVER_INFO, Map.of(), AGENT_CALL_TIMEOUT);
        }
    }

    /** 通过 agent 查询状态；agent 不可用或查询失败时返回 null */
    private ServerState queryState() {
        if (!agentConnected || agentUnusable) {
            return null;
        }
        try {
            return ServerState.fromAgent(agent.call(Protocol.OP_SERVER_INFO, Map.of(), AGENT_CALL_TIMEOUT).get(Protocol.KEY_STATE));
        } catch (AgentCallException e) {
            if (Protocol.ERR_AGENT_DIED.equals(e.getCode())) {
                agentConnected = false;
            }
            return null;
        }
    }

    /** 错误码对应的修复提示（设计方案第 11 节） */
    public static String describe(AgentCallException e) {
        String hint = switch (e.getCode() == null ? "" : e.getCode()) {
            case Protocol.ERR_CONNECT_REFUSED -> "无法连接 SOAP 端口，请确认服务器已启动，且端口为 serverindex.xml 中 SOAP_CONNECTOR_ADDRESS 的端口";
            case Protocol.ERR_BAD_PORT -> "该端口不是 SOAP 端口，请使用 serverindex.xml 中 SOAP_CONNECTOR_ADDRESS 的端口（通常是 8880）";
            case Protocol.ERR_CLIENT_MISSING -> "WAS 安装中缺少 runtimes/com.ibm.ws.admin.client_*.jar";
            case Protocol.ERR_AUTH -> "认证失败，请检查用户名和密码；若未开启全局安全，请清空用户名";
            case Protocol.ERR_SSL -> "SSL 握手失败，请确认信任服务器证书";
            case Protocol.ERR_TIMEOUT -> "调用超时，服务器可能负载过高或网络不通";
            case Protocol.ERR_AGENT_DIED -> "agent 进程已退出，请查看 agent 日志";
            default -> "";
        };
        return e.getMessage() + (hint.isEmpty() ? "" : "。" + hint);
    }

    // ---------------------------------------------------------------- 部署

    private interface AppAction {
        void run(AppRuntime rt);
    }

    private void forApp(String app, AppAction action) {
        AppRuntime rt = apps.get(app);
        if (rt == null) {
            sys("未知应用: " + app);
        } else if (state != ServerState.STARTED || !agentReady()) {
            problem("服务器未就绪（" + state.label() + "），无法操作应用 " + app);
        } else {
            action.run(rt);
        }
    }

    private boolean agentReady() {
        return agentConnected && !agentUnusable;
    }

    private void setAppState(AppRuntime rt, AppState s) {
        if (appStates.put(rt.item.appName(), s) != s) {
            listener.onAppState(rt.item.appName(), s);
        }
    }

    private void problem(String message) {
        sys("错误: " + message);
        listener.onOperationFailed(message);
    }

    private boolean build(AppRuntime rt) {
        if (rt.item.build() == null) {
            return true;
        }
        setAppState(rt, AppState.BUILDING);
        sys("构建 " + rt.item.appName() + ": " + String.join(" ", rt.item.build().command()));
        long begin = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(rt.item.build().command())
                    .directory(rt.item.build().directory().toFile()).redirectErrorStream(true);
            pb.environment().putAll(rt.item.build().environment());
            Process p = pb.start();
            Thread out = pump(p.getInputStream(), ServerListener.Output.SYSTEM);
            while (!p.waitFor(300, TimeUnit.MILLISECONDS)) {
                if (cancelStart || System.currentTimeMillis() - begin > BUILD_TIMEOUT_MILLIS) {
                    ProcessTree.terminate(p.toHandle(), 3000);
                    setAppState(rt, AppState.FAILED);
                    sys(cancelStart ? "构建已取消" : "构建超时");
                    return false;
                }
            }
            out.join(2000);
            if (p.exitValue() != 0) {
                setAppState(rt, AppState.FAILED);
                sys("构建失败，退出码 " + p.exitValue());
                return false;
            }
        } catch (IOException e) {
            setAppState(rt, AppState.FAILED);
            sys("无法执行构建命令: " + e.getMessage() + "。请在运行配置中检查 Maven 可执行文件");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!Files.isRegularFile(rt.item.archive())) {
            setAppState(rt, AppState.FAILED);
            sys("构建完成，但找不到产物: " + rt.item.archive());
            return false;
        }
        sys("构建完成，用时 " + (System.currentTimeMillis() - begin) / 1000 + " 秒");
        return true;
    }

    private void deployAll() {
        if (apps.isEmpty()) {
            return;
        }
        if (!agentReady()) {
            problem("JMX 不可用，无法部署应用");
            return;
        }
        for (AppRuntime rt : apps.values()) {
            if (cancelStart) {
                return;
            }
            deployOne(rt, false);
        }
    }

    /** 安装或更新。是否已安装以服务器为准；指纹相同且应用在运行时跳过（设计方案 5.3） */
    private void deployOne(AppRuntime rt, boolean force) {
        String app = rt.item.appName();
        try {
            String fingerprint = Staging.fingerprint(rt.item);
            boolean[] status = appStatus(app);
            if (!force && status[0] && status[1] && fingerprint.equals(spec.history().fingerprint(app))) {
                sys("应用 " + app + " 未变化且正在运行，跳过部署");
            } else {
                setAppState(rt, AppState.DEPLOYING);
                Path ear = Staging.stage(rt.item, fingerprint, spec.workDir().resolve("deploy-staging"));
                Map<String, String> params = new LinkedHashMap<>();
                params.put(Protocol.KEY_APP, app);
                params.put(Protocol.KEY_PATH, ear.toString());
                params.put(Protocol.KEY_DEPLOY_EJB, String.valueOf(rt.item.deployEjb()));
                params.put(Protocol.KEY_UPLOAD, String.valueOf(spec.mode() == ServerSpec.Mode.REMOTE));
                sys((status[0] ? "更新应用 " : "安装应用 ") + app);
                callLong(status[0] ? Protocol.OP_REDEPLOY : Protocol.OP_INSTALL, params);
                if (!appStatus(app)[1]) {
                    callLong(Protocol.OP_START_APP, Map.of(Protocol.KEY_APP, app));
                }
                spec.history().record(app, fingerprint);
            }
            prepareIncremental(rt);
            setAppState(rt, AppState.STARTED);
            sys("应用 " + app + " 已启动");
            listener.onAppDeployed(app, appUrl(rt));
        } catch (AgentCallException e) {
            setAppState(rt, AppState.FAILED);
            if (!cancelStart) {
                problem("部署 " + app + " 失败: " + describe(e));
            }
        } catch (IOException e) {
            setAppState(rt, AppState.FAILED);
            problem("部署 " + app + " 失败: " + e.getMessage());
        }
    }

    private void prepareIncremental(AppRuntime rt) throws IOException {
        rt.layout = LayoutScanner.scan(rt.item.archive(), rt.item.contextRoot(), rt.item.modules());
        rt.layout.warnings().forEach(this::sys);
        rt.snapshot = ChangeTracker.snapshot(rt.layout.roots());
        rt.binaries = spec.mode() == ServerSpec.Mode.LOCAL ? ProfileReader.binariesDir(config, rt.item.appName()) : null;
    }

    private String appUrl(AppRuntime rt) {
        if (config == null || config.httpPort <= 0 || rt.layout == null || rt.layout.contextRoot() == null) {
            return null;
        }
        String ctx = rt.layout.contextRoot();
        return "http://localhost:" + config.httpPort + (ctx.startsWith("/") ? ctx : "/" + ctx);
    }

    private boolean[] appStatus(String app) throws AgentCallException {
        Map<String, String> r = agent.call(Protocol.OP_APP_STATUS, Map.of(Protocol.KEY_APP, app), AGENT_CALL_TIMEOUT);
        return new boolean[]{"true".equals(r.get(Protocol.KEY_EXISTS)), "true".equals(r.get(Protocol.KEY_RUNNING))};
    }

    /** 长耗时调用：可被 stop/detach 打断，让出队列（agent 端任务继续，服务器随后被停止或断开） */
    private Map<String, String> callLong(String op, Map<String, String> params) throws AgentCallException {
        CompletableFuture<Map<String, String>> f = agent.request(op, params, DEPLOY_TIMEOUT);
        while (true) {
            try {
                return f.get(300, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (cancelStart) {
                    throw new AgentCallException(Protocol.ERR_TIMEOUT, "操作已取消", null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentCallException(Protocol.ERR_TIMEOUT, "操作被中断", null);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof AgentCallException ace) {
                    throw ace;
                }
                throw new AgentCallException(Protocol.ERR_TIMEOUT, "操作超时: " + op, null);
            }
        }
    }

    private void appCommand(AppRuntime rt, String op) {
        String app = rt.item.appName();
        try {
            setAppState(rt, AppState.UPDATING);
            callLong(op, Map.of(Protocol.KEY_APP, app));
            setAppState(rt, switch (op) {
                case Protocol.OP_START_APP -> AppState.STARTED;
                case Protocol.OP_STOP_APP -> AppState.STOPPED;
                default -> AppState.NOT_DEPLOYED;
            });
            if (Protocol.OP_UNINSTALL.equals(op)) {
                spec.history().record(app, null);
            }
        } catch (AgentCallException e) {
            setAppState(rt, AppState.FAILED);
            problem(op + " " + app + " 失败: " + describe(e));
        }
    }

    private boolean restart(AppRuntime rt) {
        String app = rt.item.appName();
        try {
            setAppState(rt, AppState.UPDATING);
            sys("重启应用 " + app);
            callLong(Protocol.OP_STOP_APP, Map.of(Protocol.KEY_APP, app));
            callLong(Protocol.OP_START_APP, Map.of(Protocol.KEY_APP, app));
            setAppState(rt, AppState.STARTED);
            return true;
        } catch (AgentCallException e) {
            setAppState(rt, AppState.FAILED);
            problem("重启 " + app + " 失败: " + describe(e));
            return false;
        }
    }

    private void refreshApps() {
        for (AppRuntime rt : apps.values()) {
            AppState cur = appStates.get(rt.item.appName());
            if (cur != AppState.STARTED && cur != AppState.STOPPED) {
                continue;
            }
            try {
                boolean[] st = appStatus(rt.item.appName());
                setAppState(rt, !st[0] ? AppState.NOT_DEPLOYED : st[1] ? AppState.STARTED : AppState.STOPPED);
            } catch (AgentCallException ignored) {
                // 下次监控重试
            }
        }
    }

    // ---------------------------------------------------------------- 增量更新

    private void doUpdate(UpdatePlanner.Mode mode) {
        if (state != ServerState.STARTED || !agentReady()) {
            problem("服务器未就绪（" + state.label() + "），无法更新");
            return;
        }
        for (AppRuntime rt : apps.values()) {
            if (rt.layout == null && mode != UpdatePlanner.Mode.REDEPLOY) {
                sys("应用 " + rt.item.appName() + " 尚未成功部署，改为重新部署");
                redeployNow(rt);
                continue;
            }
            try {
                updateOne(rt, mode);
            } catch (IOException e) {
                setAppState(rt, AppState.FAILED);
                problem("更新 " + rt.item.appName() + " 失败，已部署内容可能不一致，建议重新部署: " + e.getMessage());
            }
        }
    }

    private void redeployNow(AppRuntime rt) {
        if (build(rt)) {
            deployOne(rt, true);
        }
    }

    private void updateOne(AppRuntime rt, UpdatePlanner.Mode mode) throws IOException {
        String app = rt.item.appName();
        Map<String, ChangeTracker.Entry> now = rt.layout == null ? Map.of() : ChangeTracker.snapshot(rt.layout.roots());
        ChangeTracker.Changes changes = ChangeTracker.diff(rt.snapshot, now);
        UpdatePlanner.Plan plan = UpdatePlanner.plan(changes, mode, spec.isDebug(), rt.item.incrementalBlocked());
        sys(app + ": " + plan.reason() + "（改动 " + plan.apply().changed().size() + " 个，删除 " + plan.apply().deleted().size() + " 个）");
        switch (plan.action()) {
            case NOTHING -> {
                return;
            }
            case REDEPLOY -> {
                redeployNow(rt);
                return;
            }
            default -> {
            }
        }
        setAppState(rt, AppState.UPDATING);
        boolean commit = spec.mode() == ServerSpec.Mode.LOCAL ? updateLocal(rt, plan) : updateRemote(rt, plan);
        if (commit) {
            rt.snapshot = ChangeTracker.commit(rt.snapshot, plan.apply());
        }
        if (appStates.get(app) == AppState.UPDATING) {
            setAppState(rt, AppState.STARTED);
        }
    }

    private boolean updateLocal(AppRuntime rt, UpdatePlanner.Plan plan) throws IOException {
        try {
            FileSync.apply(plan.apply(), rt.binaries);
        } catch (FileSync.LayoutMismatchException e) {
            sys(e.getMessage() + "，改为重新部署");
            redeployNow(rt);
            return false;
        }
        boolean restart = plan.action() == UpdatePlanner.Action.RESTART_APP;
        if (plan.action() == UpdatePlanner.Action.HOTSWAP && !listener.hotSwap()) {
            sys("HotSwap 未成功，改为重启应用");
            restart = true;
        }
        if (restart) {
            restart(rt);
        }
        return true;
    }

    private boolean updateRemote(AppRuntime rt, UpdatePlanner.Plan plan) throws IOException {
        // partialapp 会重启应用，HotSwap 成功时不推送，改动留到下次非调试更新
        if (plan.action() == UpdatePlanner.Action.HOTSWAP && listener.hotSwap()) {
            return false;
        }
        Path delta = spec.workDir().resolve("delta-" + rt.item.appName().replaceAll("[^A-Za-z0-9._-]", "_") + ".zip");
        Files.createDirectories(spec.workDir());
        FileSync.writeDelta(plan.apply(), delta);
        Map<String, String> params = new LinkedHashMap<>();
        params.put(Protocol.KEY_APP, rt.item.appName());
        params.put(Protocol.KEY_PATH, delta.toString());
        params.put(Protocol.KEY_UPLOAD, "true");
        try {
            callLong(Protocol.OP_PARTIAL_UPDATE, params);
            return true;
        } catch (AgentCallException e) {
            if (Protocol.ERR_NOT_IMPLEMENTED.equals(e.getCode())) {
                sys("服务器不支持部分更新，改为重新部署");
                redeployNow(rt);
                return false;
            }
            throw new IOException(describe(e), e);
        } finally {
            Files.deleteIfExists(delta);
        }
    }

    // ---------------------------------------------------------------- 日志与工具进程

    private void startTailers() {
        Charset cs = spec.logCharset();
        tailers.add(new LogTailer(config.systemOut, true, cs, line -> {
            if (line.contains("WSVR0001I")) {
                startedMarkerSeen = true;
            } else if (line.contains("WSVR0009E") || line.contains("ADMU3011E")) {
                failMarkerLine = line;
            }
            listener.onText(line + "\n", ServerListener.Output.STDOUT);
        }));
        tailers.add(new LogTailer(config.systemErr, true, cs, line -> listener.onText(line + "\n", ServerListener.Output.STDERR)));
        sys("跟踪日志: " + config.systemOut + "，" + config.systemErr);
    }

    private void closeTailers() {
        tailers.forEach(LogTailer::close);
        tailers.clear();
    }

    private List<String> profileScript(String name) {
        Path bin = config.profileDir.resolve("bin");
        return WINDOWS ? List.of("cmd.exe", "/c", bin.resolve(name + ".bat").toString())
                : List.of("/bin/sh", bin.resolve(name + ".sh").toString());
    }

    /** 运行 WAS 命令行工具，输出转发到 Console，超时则结束进程树 */
    private int runTool(List<String> cmd, Duration timeout) {
        try {
            Process p = new ProcessBuilder(cmd).directory(config.profileDir.toFile()).redirectErrorStream(true).start();
            Thread t = pump(p.getInputStream(), ServerListener.Output.SYSTEM);
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                ProcessTree.terminate(p.toHandle(), 3000);
                sys("命令超时（" + timeout.toSeconds() + " 秒）: " + cmd.get(Math.min(1, cmd.size() - 1)));
                return -1;
            }
            t.join(2000);
            return p.exitValue();
        } catch (IOException e) {
            sys("命令执行失败: " + e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private Thread pump(InputStream in, ServerListener.Output kind) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.defaultCharset()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    listener.onText(line + "\n", kind);
                }
            } catch (IOException ignored) {
                // 进程退出时流关闭
            }
        }, "wasce-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ---------------------------------------------------------------- 收尾

    private void fail(String message) {
        sys("错误: " + message);
        stopMonitor();
        closeTailers();
        if (process != null && process.isAlive()) {
            sys("结束未能正常启动的服务器进程");
            ProcessTree.terminate(process.toHandle(), 10000);
        }
        setState(ServerState.FAILED);
        finish(false, 1);
    }

    private void finish(boolean detached, int exitCode) {
        if (ended) {
            return;
        }
        ended = true;
        stopMonitor();
        closeTailers();
        if (agent != null) {
            agent.close();
            agent = null;
        }
        listener.onEnded(detached, exitCode);
        executor.shutdown();
    }

    private void setState(ServerState s) {
        if (state != s) {
            state = s;
            listener.onStateChanged(s);
        }
    }

    private void sys(String message) {
        listener.onText(message + "\n", ServerListener.Output.SYSTEM);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
