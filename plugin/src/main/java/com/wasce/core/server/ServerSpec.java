package com.wasce.core.server;

import com.wasce.core.deploy.DeployHistory;
import com.wasce.core.deploy.DeployItem;
import com.wasce.core.profile.ProfileReader;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 一次服务器会话所需的全部参数，创建后不可变。
 *
 * @param mode           本地（插件负责启停）或远程（只连接）
 * @param wasHome        WAS 安装目录，用于定位 JRE 和管理客户端
 * @param profileDir     本地模式的 profile 目录
 * @param serverId       本地模式的 cell/node/server
 * @param host           远程模式的主机（本地模式从 profile 读取）
 * @param soapPort       远程模式的 SOAP 端口（本地模式从 profile 读取）
 * @param user           管理用户名，未开启安全时为空
 * @param password       密码提供者，只在工作线程上调用（PasswordSafe 读取可能较慢）
 * @param debugPort      调试端口，0 表示不调试
 * @param extraJvmArgs   本次启动附加的 JVM 参数，不修改 server.xml
 * @param whenRunning    本地服务器已在运行时的处理方式
 * @param agentClasspath 插件自带的 agent 与 protocol jar
 * @param workDir        存放生成脚本和 agent 日志的目录
 * @param deployments    要部署的应用
 * @param history        部署历史，用于跳过未变化的部署
 * @param trustStore     远程 SSL 信任库，可为 null
 * @param logCharset     服务器日志编码
 */
public record ServerSpec(Mode mode, Path wasHome, Path profileDir, ProfileReader.ServerId serverId, String host,
                         int soapPort, String user, Supplier<String> password, int debugPort,
                         List<String> extraJvmArgs, RunningPolicy whenRunning, Duration startTimeout,
                         Duration stopTimeout, List<Path> agentClasspath, Path workDir,
                         List<DeployItem> deployments, DeployHistory history, Path trustStore, Charset logCharset) {

    public enum Mode {LOCAL, REMOTE}

    public enum RunningPolicy {ASK, ATTACH, FAIL}

    public boolean isDebug() {
        return debugPort > 0;
    }

    public String displayName() {
        return mode == Mode.LOCAL ? String.valueOf(serverId) : host + ":" + soapPort;
    }
}
