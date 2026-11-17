package com.wasce.agent;

/**
 * IBM API 使用的字面量。不用 AppConstants 等常量类：桩中的 static final 会被内联，写错就是隐藏 bug。
 * 标注"已佐证"的取自官方插件的字符串常量；"待验证"的来自 IBM 文档记忆，需在真实 WAS 上确认（设计方案 13.2）。
 */
final class WasKeys {
    private WasKeys() {
    }

    // 连接属性（已佐证）
    static final String TYPE = "type";
    static final String TYPE_SOAP = "SOAP";
    static final String HOST = "host";
    static final String PORT = "port";
    static final String SECURITY_ENABLED = "securityEnabled";
    static final String USERNAME = "username";
    static final String PASSWORD = "password";
    static final String TRUST_STORE = "javax.net.ssl.trustStore";
    static final String TRUST_STORE_PASSWORD = "javax.net.ssl.trustStorePassword";

    // AppManagement 选项（已佐证）
    static final String MODULE_TO_SERVER = "moduleToServer";
    static final String ARCHIVE_UPLOAD = "archive.upload";
    static final String DEPLOY_EJB = "deployejb";
    static final String DELETE_SOURCE_EAR = "DeleteSourceEar";
    static final String CELL_NAME = "cell.name";

    // AppManagement 选项（待验证）：未知键会被忽略，写错只影响重载行为
    static final String RELOAD_ENABLED = "reloadEnabled";
    static final String CONTENT_TYPE = "contenttype";
    static final String CONTENT_TYPE_PARTIAL = "partialapp";
    static final String UPDATE_OPERATION = "update";

    // 通知（已佐证）
    static final String NOTIFICATION_TYPE = "websphere.admin.appmgmt";
    static final String PROP_APP_NAME = "appname";
    static final String STATUS_COMPLETED = "Completed";
    static final String STATUS_FAILED = "Failed";
    static final String TASK_INSTALL = "InstallApplication";
    static final String TASK_UNINSTALL = "UninstallApplication";
    // 待验证：redeploy 和 partialapp 的任务名
    static final String TASK_UPDATE = "UpdateApplication";
    static final String TASK_REDEPLOY = "RedeployApplication";
}
