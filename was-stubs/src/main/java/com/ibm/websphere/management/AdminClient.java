package com.ibm.websphere.management;

import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.QueryExp;
import java.util.Set;

/** 签名桩，方法描述符与官方插件引用的 IBM API 一致；invoke 为标准 JMX 形式 */
public interface AdminClient {
    void addNotificationListener(ObjectName name, NotificationListener listener, NotificationFilter filter, Object handback) throws Exception;

    Object getAttribute(ObjectName name, String attribute) throws Exception;

    boolean isRegistered(ObjectName name) throws Exception;

    Set queryNames(ObjectName name, QueryExp query) throws Exception;

    Object invoke(ObjectName name, String operation, Object[] params, String[] signature) throws Exception;
}
