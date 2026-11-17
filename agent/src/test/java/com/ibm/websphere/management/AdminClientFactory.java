package com.ibm.websphere.management;

import java.util.Properties;

/** 测试替身：遮蔽桩，返回 JmxBackendTest 设置的 AdminClient */
public class AdminClientFactory {
    public static AdminClient next;
    public static Properties lastProperties;

    public static AdminClient createAdminClient(Properties properties) {
        lastProperties = properties;
        return next;
    }
}
