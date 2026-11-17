package com.wasce.idea.run;

import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.openapi.components.StoredProperty;

/**
 * 运行配置持久化字段。密码不在此处，见 WasCredentials。
 */
public class WasRunConfigurationOptions extends RunConfigurationOptions {
    private final StoredProperty<String> mode = string("LOCAL").provideDelegate(this, "mode");
    private final StoredProperty<String> installation = string("").provideDelegate(this, "installation");
    private final StoredProperty<String> profilePath = string("").provideDelegate(this, "profilePath");
    private final StoredProperty<String> serverId = string("").provideDelegate(this, "serverId");
    private final StoredProperty<String> host = string("").provideDelegate(this, "host");
    private final StoredProperty<Integer> soapPort = property(8880).provideDelegate(this, "soapPort");
    private final StoredProperty<String> user = string("").provideDelegate(this, "user");
    private final StoredProperty<String> credentialKey = string("").provideDelegate(this, "credentialKey");
    private final StoredProperty<Integer> debugPort = property(7777).provideDelegate(this, "debugPort");
    private final StoredProperty<String> vmOptions = string("").provideDelegate(this, "vmOptions");
    private final StoredProperty<Integer> startTimeout = property(600).provideDelegate(this, "startTimeout");
    private final StoredProperty<Integer> stopTimeout = property(120).provideDelegate(this, "stopTimeout");
    private final StoredProperty<String> whenRunning = string("ASK").provideDelegate(this, "whenRunning");
    /** 部署项，每行一项，格式见 WasDeployment */
    private final StoredProperty<String> deployments = string("").provideDelegate(this, "deployments");
    private final StoredProperty<String> mavenExecutable = string("").provideDelegate(this, "mavenExecutable");
    private final StoredProperty<String> logEncoding = string("").provideDelegate(this, "logEncoding");
    private final StoredProperty<String> frameUpdate = string("NONE").provideDelegate(this, "frameUpdate");
    private final StoredProperty<Boolean> openBrowser = property(false).provideDelegate(this, "openBrowser");

    public String getMode() {
        return mode.getValue(this);
    }

    public void setMode(String v) {
        mode.setValue(this, v);
    }

    public String getInstallation() {
        return installation.getValue(this);
    }

    public void setInstallation(String v) {
        installation.setValue(this, v);
    }

    public String getProfilePath() {
        return profilePath.getValue(this);
    }

    public void setProfilePath(String v) {
        profilePath.setValue(this, v);
    }

    public String getServerId() {
        return serverId.getValue(this);
    }

    public void setServerId(String v) {
        serverId.setValue(this, v);
    }

    public String getHost() {
        return host.getValue(this);
    }

    public void setHost(String v) {
        host.setValue(this, v);
    }

    public int getSoapPort() {
        return soapPort.getValue(this);
    }

    public void setSoapPort(int v) {
        soapPort.setValue(this, v);
    }

    public String getUser() {
        return user.getValue(this);
    }

    public void setUser(String v) {
        user.setValue(this, v);
    }

    public String getCredentialKey() {
        return credentialKey.getValue(this);
    }

    public void setCredentialKey(String v) {
        credentialKey.setValue(this, v);
    }

    public int getDebugPort() {
        return debugPort.getValue(this);
    }

    public void setDebugPort(int v) {
        debugPort.setValue(this, v);
    }

    public String getVmOptions() {
        return vmOptions.getValue(this);
    }

    public void setVmOptions(String v) {
        vmOptions.setValue(this, v);
    }

    public int getStartTimeout() {
        return startTimeout.getValue(this);
    }

    public void setStartTimeout(int v) {
        startTimeout.setValue(this, v);
    }

    public int getStopTimeout() {
        return stopTimeout.getValue(this);
    }

    public void setStopTimeout(int v) {
        stopTimeout.setValue(this, v);
    }

    public String getWhenRunning() {
        return whenRunning.getValue(this);
    }

    public void setWhenRunning(String v) {
        whenRunning.setValue(this, v);
    }

    public String getDeployments() {
        return deployments.getValue(this);
    }

    public void setDeployments(String v) {
        deployments.setValue(this, v);
    }

    public String getMavenExecutable() {
        return mavenExecutable.getValue(this);
    }

    public void setMavenExecutable(String v) {
        mavenExecutable.setValue(this, v);
    }

    public String getLogEncoding() {
        return logEncoding.getValue(this);
    }

    public void setLogEncoding(String v) {
        logEncoding.setValue(this, v);
    }

    public String getFrameUpdate() {
        return frameUpdate.getValue(this);
    }

    public void setFrameUpdate(String v) {
        frameUpdate.setValue(this, v);
    }

    public boolean isOpenBrowser() {
        return openBrowser.getValue(this);
    }

    public void setOpenBrowser(boolean v) {
        openBrowser.setValue(this, v);
    }
}
