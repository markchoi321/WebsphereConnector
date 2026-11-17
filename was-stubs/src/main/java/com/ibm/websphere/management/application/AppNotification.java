package com.ibm.websphere.management.application;

import java.io.Serializable;
import java.util.Properties;

/** 签名桩 */
public class AppNotification implements Serializable {
    public String message;
    public Properties props;
    public String taskName;
    public String taskStatus;
}
