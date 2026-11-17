package com.ibm.websphere.management.application;

import java.util.Hashtable;
import java.util.Vector;

/** 签名桩。stopApplication 与 updateApplication 未经官方插件佐证，调用方需捕获 LinkageError */
public interface AppManagement {
    boolean checkIfAppExists(String appName, Hashtable prefs, String workspaceId) throws Exception;

    Vector getApplicationInfo(String appName, Hashtable prefs, String workspaceId) throws Exception;

    void installApplication(String earPath, String appName, Hashtable prefs, String workspaceId) throws Exception;

    void redeployApplication(String earPath, String appName, Hashtable prefs, String workspaceId) throws Exception;

    String startApplication(String appName, Hashtable prefs, String workspaceId) throws Exception;

    String stopApplication(String appName, Hashtable prefs, String workspaceId) throws Exception;

    void uninstallApplication(String appName, Hashtable prefs, String workspaceId) throws Exception;

    void updateApplication(String appName, String contentUri, String contents, String operation, Hashtable prefs, String workspaceId) throws Exception;
}
