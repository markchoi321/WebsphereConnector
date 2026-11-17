package com.wasce.agent;

import com.ibm.websphere.management.AdminClient;
import com.ibm.websphere.management.AdminClientFactory;
import com.ibm.websphere.management.application.AppManagement;
import com.ibm.websphere.management.application.AppManagementProxy;
import com.ibm.websphere.management.application.AppNotification;
import com.wasce.protocol.Protocol;
import org.junit.Before;
import org.junit.Test;

import javax.management.Notification;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.QueryExp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 用内存替身验证 JmxBackend 的调用顺序与异步任务等待。真实 WAS 上的键值仍需按设计方案 13.2 验证。
 */
public class JmxBackendTest {
    private final JmxBackend backend = new JmxBackend();
    private final Set<String> runningApps = new HashSet<String>();
    private final List<String> calls = new ArrayList<String>();
    private NotificationListener listener;
    private Hashtable lastPrefs;
    /** 安装任务的结束状态 */
    private String taskStatus = "Completed";

    private final AdminClient client = new AdminClient() {
        @Override
        public void addNotificationListener(ObjectName n, NotificationListener l, NotificationFilter f, Object h) {
            listener = l;
        }

        @Override
        public Object getAttribute(ObjectName n, String a) {
            Map<String, String> m = new HashMap<String, String>();
            m.put("state", "STARTED");
            m.put("cellName", "C");
            m.put("nodeName", "N");
            m.put("name", "server1");
            return m.get(a);
        }

        @Override
        public boolean isRegistered(ObjectName n) {
            return true;
        }

        @Override
        public Set queryNames(ObjectName q, QueryExp e) throws Exception {
            String s = q.toString();
            if (s.contains("type=Server")) {
                return Collections.singleton(new ObjectName("WebSphere:type=Server,name=server1"));
            }
            if (s.contains("type=AppManagement")) {
                return Collections.singleton(new ObjectName("WebSphere:type=AppManagement"));
            }
            String app = q.getKeyProperty("name");
            return runningApps.contains(app) ? Collections.singleton(q) : Collections.emptySet();
        }

        @Override
        public Object invoke(ObjectName n, String op, Object[] p, String[] sig) {
            calls.add("invoke:" + op);
            return null;
        }
    };

    private final AppManagement apps = new AppManagement() {
        @Override
        public boolean checkIfAppExists(String a, Hashtable p, String w) {
            return "installed".equals(a);
        }

        @Override
        public Vector getApplicationInfo(String a, Hashtable p, String w) {
            return new Vector();
        }

        @Override
        public void installApplication(String ear, String a, Hashtable p, String w) {
            calls.add("install:" + ear);
            lastPrefs = p;
            finish("InstallApplication", a);
        }

        @Override
        public void redeployApplication(String ear, String a, Hashtable p, String w) {
            calls.add("redeploy:" + ear);
            finish("UninstallApplication", a);
            finish("InstallApplication", a);
        }

        @Override
        public String startApplication(String a, Hashtable p, String w) {
            runningApps.add(a);
            return a;
        }

        @Override
        public String stopApplication(String a, Hashtable p, String w) {
            runningApps.remove(a);
            return a;
        }

        @Override
        public void uninstallApplication(String a, Hashtable p, String w) {
            finish("UninstallApplication", a);
        }

        @Override
        public void updateApplication(String a, String c, String contents, String op, Hashtable p, String w) {
            calls.add("update:" + contents + ":" + p.get("contenttype"));
            finish("UpdateApplication", a);
        }
    };

    /** 任务在另一线程异步结束，模拟 WAS 的通知 */
    private void finish(final String task, final String app) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                AppNotification n = new AppNotification();
                n.taskName = task;
                n.taskStatus = taskStatus;
                n.message = "msg";
                n.props = new Properties();
                n.props.setProperty("appname", app);
                Notification notification = new Notification("websphere.admin.appmgmt", "src", 1);
                notification.setUserData(n);
                listener.handleNotification(notification, null);
            }
        }).start();
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Before
    public void connect() throws Exception {
        AdminClientFactory.next = client;
        AppManagementProxy.next = apps;
        Map<String, String> info = backend.handle(Protocol.OP_CONNECT, map(Protocol.KEY_HOST, "h", Protocol.KEY_PORT, "8880",
                Protocol.KEY_SECURITY, "true", Protocol.KEY_USER, "u", Protocol.KEY_PASSWORD, "p"));
        assertEquals("STARTED", info.get(Protocol.KEY_STATE));
        assertEquals("true", AdminClientFactory.lastProperties.getProperty("securityEnabled"));
        assertEquals("u", AdminClientFactory.lastProperties.getProperty("username"));
    }

    @Test
    public void installWaitsForCompletionAndMapsToServer() throws Exception {
        backend.handle(Protocol.OP_INSTALL, map(Protocol.KEY_APP, "a", Protocol.KEY_PATH, "/x.ear", Protocol.KEY_DEPLOY_EJB, "true"));
        assertEquals("install:/x.ear", calls.get(0));
        assertEquals("WebSphere:cell=C,node=N,server=server1", ((Hashtable) lastPrefs.get("moduleToServer")).get("*"));
        assertEquals(Boolean.TRUE, lastPrefs.get("deployejb"));
        assertEquals(Boolean.FALSE, lastPrefs.get("reloadEnabled"));
    }

    @Test
    public void failedTaskIsReported() throws Exception {
        taskStatus = "Failed";
        try {
            backend.handle(Protocol.OP_INSTALL, map(Protocol.KEY_APP, "a", Protocol.KEY_PATH, "/x.ear"));
            fail("任务失败应抛出异常");
        } catch (AgentException e) {
            assertEquals(Protocol.ERR_DEPLOY_FAILED, e.getCode());
        }
    }

    @Test
    public void redeployIgnoresUninstallStepAndCompletes() throws Exception {
        backend.handle(Protocol.OP_REDEPLOY, map(Protocol.KEY_APP, "a", Protocol.KEY_PATH, "/x.ear"));
        assertEquals("redeploy:/x.ear", calls.get(0));
    }

    @Test
    public void partialUpdateUsesPartialContentType() throws Exception {
        backend.handle(Protocol.OP_PARTIAL_UPDATE, map(Protocol.KEY_APP, "a", Protocol.KEY_PATH, "/d.zip"));
        assertEquals("update:/d.zip:partialapp", calls.get(0));
    }

    @Test
    public void appLifecycleAndStatus() throws Exception {
        assertEquals("true", backend.handle(Protocol.OP_APP_STATUS, map(Protocol.KEY_APP, "installed")).get(Protocol.KEY_EXISTS));
        backend.handle(Protocol.OP_START_APP, map(Protocol.KEY_APP, "installed"));
        assertEquals("true", backend.handle(Protocol.OP_APP_STATUS, map(Protocol.KEY_APP, "installed")).get(Protocol.KEY_RUNNING));
        backend.handle(Protocol.OP_STOP_APP, map(Protocol.KEY_APP, "installed"));
        assertFalse(runningApps.contains("installed"));
        backend.handle(Protocol.OP_STOP_SERVER, map());
        assertTrue(calls.contains("invoke:stop"));
    }
}
