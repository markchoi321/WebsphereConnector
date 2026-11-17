package com.ibm.websphere.management.application;

import com.ibm.websphere.management.AdminClient;

/** 测试替身：遮蔽桩 */
public class AppManagementProxy {
    public static AppManagement next;

    public static AppManagement getJMXProxyForClient(AdminClient client) {
        return next;
    }
}
