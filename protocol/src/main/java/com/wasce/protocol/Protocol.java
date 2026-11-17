package com.wasce.protocol;

/**
 * 协议常量：版本、操作名、属性键、错误码。
 */
public final class Protocol {
    private Protocol() {
    }

    /** 协议版本，握手时双方必须一致 */
    public static final int VERSION = 1;

    /** agent 超过该时长未收到任何消息即自行退出 */
    public static final long AGENT_IDLE_EXIT_MILLIS = 20000;
    /** IDE 发送心跳的间隔 */
    public static final long HEARTBEAT_MILLIS = 5000;

    // 操作
    public static final String OP_PING = "ping";
    public static final String OP_CONNECT = "connect";
    public static final String OP_SERVER_INFO = "serverInfo";
    public static final String OP_STOP_SERVER = "stopServer";
    public static final String OP_SHUTDOWN = "shutdown";
    public static final String OP_APP_STATUS = "appStatus";
    public static final String OP_INSTALL = "install";
    public static final String OP_REDEPLOY = "redeploy";
    public static final String OP_PARTIAL_UPDATE = "partialUpdate";
    public static final String OP_UNINSTALL = "uninstall";
    public static final String OP_START_APP = "startApp";
    public static final String OP_STOP_APP = "stopApp";

    /** 部署进度事件，属性 KEY_APP、KEY_MESSAGE */
    public static final String EV_DEPLOY_PROGRESS = "deployProgress";

    // 握手属性
    public static final String KEY_TOKEN = "token";
    public static final String KEY_VERSION = "version";
    public static final String KEY_PID = "pid";
    public static final String KEY_JAVA = "java";
    public static final String KEY_BACKEND = "backend";

    // 连接参数
    public static final String KEY_HOST = "host";
    public static final String KEY_PORT = "port";
    public static final String KEY_USER = "user";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_SECURITY = "security";

    public static final String KEY_TRUST_STORE = "trustStore";
    public static final String KEY_TRUST_PASSWORD = "trustPassword";
    /** 限定连接的服务器名，可为空 */
    public static final String KEY_SERVER_NAME = "serverName";

    // 应用操作
    public static final String KEY_APP = "app";
    public static final String KEY_PATH = "path";
    public static final String KEY_DEPLOY_EJB = "deployEjb";
    /** true 表示归档在 IDE 所在机器上，需要先上传到服务器 */
    public static final String KEY_UPLOAD = "upload";
    public static final String KEY_EXISTS = "exists";
    public static final String KEY_RUNNING = "running";
    public static final String KEY_MESSAGE = "message";

    // 服务器信息
    public static final String KEY_STATE = "state";
    public static final String KEY_CELL = "cell";
    public static final String KEY_NODE = "node";
    public static final String KEY_SERVER = "server";
    public static final String KEY_WAS_VERSION = "wasVersion";

    // 错误
    public static final String KEY_ERROR_CODE = "error.code";
    public static final String KEY_ERROR_MESSAGE = "error.message";
    public static final String KEY_ERROR_DETAIL = "error.detail";

    public static final String ERR_CONNECT_REFUSED = "CONNECT_REFUSED";
    public static final String ERR_NOT_CONNECTED = "NOT_CONNECTED";
    public static final String ERR_AUTH = "AUTH";
    public static final String ERR_SSL = "SSL";
    public static final String ERR_TIMEOUT = "TIMEOUT";
    public static final String ERR_NOT_IMPLEMENTED = "NOT_IMPLEMENTED";
    public static final String ERR_BAD_REQUEST = "BAD_REQUEST";
    public static final String ERR_INTERNAL = "INTERNAL";
    /** 端口可连但不是 SOAP 连接器 */
    public static final String ERR_BAD_PORT = "BAD_PORT";
    /** 缺少 IBM 管理客户端 jar */
    public static final String ERR_CLIENT_MISSING = "CLIENT_MISSING";
    public static final String ERR_DEPLOY_FAILED = "DEPLOY_FAILED";
    /** 仅 IDE 侧使用：agent 进程或连接已失效 */
    public static final String ERR_AGENT_DIED = "AGENT_DIED";
}
