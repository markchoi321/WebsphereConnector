package com.wasce.core.deploy;

/**
 * 上次成功部署的归档指纹。只作参考，判断前仍以服务器真实状态为准（设计方案 5.3）。
 */
public interface DeployHistory {
    String fingerprint(String app);

    void record(String app, String fingerprint);
}
