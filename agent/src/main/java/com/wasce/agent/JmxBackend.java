package com.wasce.agent;

import com.ibm.websphere.management.AdminClient;
import com.ibm.websphere.management.AdminClientFactory;
import com.ibm.websphere.management.application.AppManagement;
import com.ibm.websphere.management.application.AppManagementProxy;
import com.ibm.websphere.management.application.AppNotification;
import com.ibm.websphere.management.filetransfer.client.FileTransferClient;
import com.ibm.ws.management.fileservice.FileTransferFactory;
import com.wasce.protocol.Protocol;

import javax.management.Notification;
import javax.management.NotificationFilterSupport;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 真实 WAS 后端：通过 SOAP 连接器访问 Server、Application、AppManagement MBean。
 * 部署类操作是异步任务，向 AppManagement 注册通知，收到任务完成或失败后才返回。
 */
final class JmxBackend implements Backend, NotificationListener {
    private static final long TASK_TIMEOUT_MINUTES = 30;

    /** 等待某个应用的部署任务结束 */
    private static final class TaskWait {
        final Set<String> tasks;
        final CountDownLatch done = new CountDownLatch(1);
        volatile String failure;

        TaskWait(String... tasks) {
            this.tasks = new HashSet<String>(Arrays.asList(tasks));
        }
    }

    private final Map<String, TaskWait> waits = new ConcurrentHashMap<String, TaskWait>();
    private volatile EventSink events;
    private volatile AdminClient client;
    private volatile AppManagement apps;
    private volatile ObjectName server;
    private volatile String wantedServer;

    @Override
    public void setEventSink(EventSink sink) {
        this.events = sink;
    }

    @Override
    public Map<String, String> handle(String op, Map<String, String> p) throws AgentException {
        try {
            if (Protocol.OP_CONNECT.equals(op)) {
                return connect(p);
            }
            requireConnected();
            if (Protocol.OP_SERVER_INFO.equals(op)) {
                return serverInfo();
            } else if (Protocol.OP_STOP_SERVER.equals(op)) {
                client.invoke(server(), "stop", new Object[0], new String[0]);
                return empty();
            } else if (Protocol.OP_APP_STATUS.equals(op)) {
                return appStatus(p.get(Protocol.KEY_APP));
            } else if (Protocol.OP_INSTALL.equals(op)) {
                return install(p);
            } else if (Protocol.OP_REDEPLOY.equals(op)) {
                return redeploy(p);
            } else if (Protocol.OP_PARTIAL_UPDATE.equals(op)) {
                return partialUpdate(p);
            } else if (Protocol.OP_UNINSTALL.equals(op)) {
                return uninstall(p.get(Protocol.KEY_APP));
            } else if (Protocol.OP_START_APP.equals(op)) {
                return startApp(p.get(Protocol.KEY_APP));
            } else if (Protocol.OP_STOP_APP.equals(op)) {
                return stopApp(p.get(Protocol.KEY_APP));
            }
            throw new AgentException(Protocol.ERR_BAD_REQUEST, "未知操作: " + op);
        } catch (AgentException e) {
            throw e;
        } catch (NoClassDefFoundError e) {
            throw new AgentException(Protocol.ERR_CLIENT_MISSING, "缺少 IBM 管理客户端类: " + e.getMessage(), e);
        } catch (Exception e) {
            throw ErrorClassifier.classify(op, e);
        }
    }

    @Override
    public void close() {
        client = null;
    }

    private void requireConnected() throws AgentException {
        if (client == null) {
            throw new AgentException(Protocol.ERR_NOT_CONNECTED, "尚未连接服务器");
        }
    }

    private static Map<String, String> empty() {
        return new LinkedHashMap<String, String>();
    }

    private Map<String, String> connect(Map<String, String> p) throws Exception {
        Properties props = new Properties();
        props.setProperty(WasKeys.TYPE, WasKeys.TYPE_SOAP);
        props.setProperty(WasKeys.HOST, p.get(Protocol.KEY_HOST));
        props.setProperty(WasKeys.PORT, p.get(Protocol.KEY_PORT));
        boolean security = "true".equals(p.get(Protocol.KEY_SECURITY));
        props.setProperty(WasKeys.SECURITY_ENABLED, String.valueOf(security));
        if (security) {
            props.setProperty(WasKeys.USERNAME, p.get(Protocol.KEY_USER));
            props.setProperty(WasKeys.PASSWORD, p.get(Protocol.KEY_PASSWORD));
        }
        String trust = p.get(Protocol.KEY_TRUST_STORE);
        if (trust != null && !trust.isEmpty()) {
            props.setProperty(WasKeys.TRUST_STORE, trust);
            props.setProperty(WasKeys.TRUST_STORE_PASSWORD, p.get(Protocol.KEY_TRUST_PASSWORD));
        }
        wantedServer = p.get(Protocol.KEY_SERVER_NAME);
        AdminClient c = AdminClientFactory.createAdminClient(props);
        ObjectName s = findServer(c);
        AppManagement a = AppManagementProxy.getJMXProxyForClient(c);
        registerListener(c);
        client = c;
        apps = a;
        server = s;
        return serverInfo();
    }

    private ObjectName findServer(AdminClient c) throws Exception {
        Set names = c.queryNames(new ObjectName("WebSphere:type=Server,*"), null);
        for (Iterator it = names.iterator(); it.hasNext(); ) {
            ObjectName n = (ObjectName) it.next();
            if (wantedServer == null || wantedServer.isEmpty() || wantedServer.equals(n.getKeyProperty("name"))) {
                return n;
            }
        }
        throw new AgentException(Protocol.ERR_CONNECT_REFUSED, "找不到 Server MBean" + (wantedServer == null ? "" : ": " + wantedServer));
    }

    private void registerListener(AdminClient c) throws Exception {
        Set names = c.queryNames(new ObjectName("WebSphere:type=AppManagement,*"), null);
        if (names.isEmpty()) {
            AgentMain.log("找不到 AppManagement MBean，部署任务将无法收到通知");
            return;
        }
        NotificationFilterSupport filter = new NotificationFilterSupport();
        filter.enableType(WasKeys.NOTIFICATION_TYPE);
        c.addNotificationListener((ObjectName) names.iterator().next(), this, filter, null);
    }

    /** MBean 的 ObjectName 随服务器进程变化，失效时重新查询一次 */
    private ObjectName server() throws Exception {
        if (!client.isRegistered(server)) {
            server = findServer(client);
        }
        return server;
    }

    private Map<String, String> serverInfo() throws Exception {
        ObjectName s = server();
        Map<String, String> r = empty();
        r.put(Protocol.KEY_STATE, String.valueOf(client.getAttribute(s, "state")));
        r.put(Protocol.KEY_CELL, String.valueOf(client.getAttribute(s, "cellName")));
        r.put(Protocol.KEY_NODE, String.valueOf(client.getAttribute(s, "nodeName")));
        r.put(Protocol.KEY_SERVER, String.valueOf(client.getAttribute(s, "name")));
        return r;
    }

    private Map<String, String> appStatus(String app) throws Exception {
        Map<String, String> r = empty();
        boolean exists = apps.checkIfAppExists(app, new Hashtable(), null);
        r.put(Protocol.KEY_EXISTS, String.valueOf(exists));
        r.put(Protocol.KEY_RUNNING, String.valueOf(exists && isRunning(app)));
        return r;
    }

    private boolean isRunning(String app) throws Exception {
        return !client.queryNames(new ObjectName("WebSphere:type=Application,name=" + app + ",*"), null).isEmpty();
    }

    private Hashtable deployOptions(Map<String, String> p) throws Exception {
        Hashtable prefs = new Hashtable();
        ObjectName s = server();
        String cell = String.valueOf(client.getAttribute(s, "cellName"));
        String node = String.valueOf(client.getAttribute(s, "nodeName"));
        String name = String.valueOf(client.getAttribute(s, "name"));
        Hashtable mapping = new Hashtable();
        mapping.put("*", "WebSphere:cell=" + cell + ",node=" + node + ",server=" + name);
        prefs.put(WasKeys.MODULE_TO_SERVER, mapping);
        prefs.put(WasKeys.CELL_NAME, cell);
        prefs.put(WasKeys.DEPLOY_EJB, Boolean.valueOf(p.get(Protocol.KEY_DEPLOY_EJB)));
        // 重载由插件在同步完成后显式执行，避免 WAS 在文件复制到一半时自动重载（设计方案 9.3）
        prefs.put(WasKeys.RELOAD_ENABLED, Boolean.FALSE);
        prefs.put(WasKeys.ARCHIVE_UPLOAD, Boolean.FALSE);
        return prefs;
    }

    /** 需要上传时把归档传到服务器暂存目录，返回服务器一侧的路径 */
    private String prepareArchive(Map<String, String> p, Hashtable prefs) throws Exception {
        String path = p.get(Protocol.KEY_PATH);
        if (!"true".equals(p.get(Protocol.KEY_UPLOAD))) {
            return path;
        }
        File file = new File(path);
        progress(p.get(Protocol.KEY_APP), "上传归档到服务器: " + file.getName());
        FileTransferClient ft = FileTransferFactory.getFileTransferClient(client);
        ft.uploadFile(file, file.getName());
        if (prefs != null) {
            prefs.put(WasKeys.DELETE_SOURCE_EAR, Boolean.TRUE);
        }
        return ft.getServerStagingLocation() + "/" + file.getName();
    }

    private interface Action {
        void run() throws Exception;
    }

    private void runTask(String app, TaskWait wait, Action action) throws Exception {
        waits.put(app, wait);
        try {
            action.run();
            if (!wait.done.await(TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                throw new AgentException(Protocol.ERR_TIMEOUT, "等待部署任务完成超时: " + app);
            }
            if (wait.failure != null) {
                throw new AgentException(Protocol.ERR_DEPLOY_FAILED, wait.failure);
            }
        } finally {
            waits.remove(app);
        }
    }

    private Map<String, String> install(Map<String, String> p) throws Exception {
        final String app = p.get(Protocol.KEY_APP);
        final Hashtable prefs = deployOptions(p);
        final String path = prepareArchive(p, prefs);
        runTask(app, new TaskWait(WasKeys.TASK_INSTALL), new Action() {
            @Override
            public void run() throws Exception {
                apps.installApplication(path, app, prefs, null);
            }
        });
        return empty();
    }

    private Map<String, String> redeploy(Map<String, String> p) throws Exception {
        final String app = p.get(Protocol.KEY_APP);
        final Hashtable prefs = deployOptions(p);
        final String path = prepareArchive(p, prefs);
        runTask(app, new TaskWait(WasKeys.TASK_INSTALL, WasKeys.TASK_UPDATE, WasKeys.TASK_REDEPLOY), new Action() {
            @Override
            public void run() throws Exception {
                apps.redeployApplication(path, app, prefs, null);
            }
        });
        return empty();
    }

    private Map<String, String> partialUpdate(Map<String, String> p) throws Exception {
        final String app = p.get(Protocol.KEY_APP);
        final Hashtable prefs = new Hashtable();
        prefs.put(WasKeys.CONTENT_TYPE, WasKeys.CONTENT_TYPE_PARTIAL);
        final String path = prepareArchive(p, null);
        try {
            runTask(app, new TaskWait(WasKeys.TASK_UPDATE), new Action() {
                @Override
                public void run() throws Exception {
                    apps.updateApplication(app, null, path, WasKeys.UPDATE_OPERATION, prefs, null);
                }
            });
        } catch (LinkageError e) {
            throw new AgentException(Protocol.ERR_NOT_IMPLEMENTED, "当前 WAS 不支持部分更新: " + e, e);
        }
        return empty();
    }

    private Map<String, String> uninstall(final String app) throws Exception {
        runTask(app, new TaskWait(WasKeys.TASK_UNINSTALL), new Action() {
            @Override
            public void run() throws Exception {
                apps.uninstallApplication(app, new Hashtable(), null);
            }
        });
        return empty();
    }

    private Map<String, String> startApp(String app) throws Exception {
        apps.startApplication(app, new Hashtable(), null);
        waitRunning(app, true);
        return empty();
    }

    private Map<String, String> stopApp(String app) throws Exception {
        try {
            apps.stopApplication(app, new Hashtable(), null);
        } catch (LinkageError e) {
            // 接口签名不符时退回 ApplicationManager MBean
            Set names = client.queryNames(new ObjectName("WebSphere:type=ApplicationManager,*"), null);
            if (names.isEmpty()) {
                throw new AgentException(Protocol.ERR_NOT_IMPLEMENTED, "无法停止应用: " + e, e);
            }
            client.invoke((ObjectName) names.iterator().next(), "stopApplication", new Object[]{app}, new String[]{"java.lang.String"});
        }
        waitRunning(app, false);
        return empty();
    }

    private void waitRunning(String app, boolean expected) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        while (isRunning(app) != expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new AgentException(Protocol.ERR_TIMEOUT, "等待应用" + (expected ? "启动" : "停止") + "超时: " + app);
            }
            Thread.sleep(500);
        }
    }

    private void progress(String app, String message) {
        EventSink sink = events;
        if (sink != null) {
            Map<String, String> m = empty();
            m.put(Protocol.KEY_APP, app == null ? "" : app);
            m.put(Protocol.KEY_MESSAGE, message);
            sink.emit(Protocol.EV_DEPLOY_PROGRESS, m);
        }
    }

    @Override
    public void handleNotification(Notification notification, Object handback) {
        Object data = notification.getUserData();
        if (!(data instanceof AppNotification)) {
            return;
        }
        AppNotification n = (AppNotification) data;
        String app = n.props == null ? null : n.props.getProperty(WasKeys.PROP_APP_NAME);
        progress(app, n.taskName + " " + n.taskStatus + (n.message == null ? "" : ": " + n.message));
        TaskWait wait = app == null ? null : waits.get(app);
        if (wait == null) {
            return;
        }
        if (WasKeys.STATUS_FAILED.equals(n.taskStatus)) {
            wait.failure = n.taskName + " 失败: " + n.message;
            wait.done.countDown();
        } else if (WasKeys.STATUS_COMPLETED.equals(n.taskStatus) && wait.tasks.contains(n.taskName)) {
            wait.done.countDown();
        }
    }
}
