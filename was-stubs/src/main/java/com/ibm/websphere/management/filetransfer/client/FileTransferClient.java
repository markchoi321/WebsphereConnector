package com.ibm.websphere.management.filetransfer.client;

import java.io.File;

/** 签名桩 */
public interface FileTransferClient {
    String getServerStagingLocation() throws Exception;

    void uploadFile(File source, String destination) throws Exception;
}
