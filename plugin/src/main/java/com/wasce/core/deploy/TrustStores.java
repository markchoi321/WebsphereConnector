package com.wasce.core.deploy;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/**
 * 插件自管的信任库：取得服务器证书，由用户确认指纹后导入。
 * 信任库只含公开证书，口令不是秘密，只为满足 JKS 格式要求。
 */
public final class TrustStores {
    public static final String PASSWORD = "wasce-trust";

    private TrustStores() {
    }

    /** 只用于读取证书展示给用户，握手结果不用于传输任何数据 */
    public static X509Certificate fetchCertificate(String host, int port) throws IOException, GeneralSecurityException {
        X509Certificate[][] seen = new X509Certificate[1][];
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                seen[0] = chain;
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        try (SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket()) {
            socket.setSoTimeout(10000);
            socket.connect(new InetSocketAddress(host, port), 10000);
            socket.startHandshake();
        }
        if (seen[0] == null || seen[0].length == 0) {
            throw new IOException("服务器未提供证书");
        }
        return seen[0][seen[0].length - 1];
    }

    public static String fingerprint(X509Certificate cert) throws GeneralSecurityException {
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }

    public static void importCertificate(Path store, String alias, X509Certificate cert) throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance("JKS");
        if (Files.isRegularFile(store)) {
            try (InputStream in = Files.newInputStream(store)) {
                ks.load(in, PASSWORD.toCharArray());
            }
        } else {
            ks.load(null, PASSWORD.toCharArray());
            Files.createDirectories(store.getParent());
        }
        ks.setCertificateEntry(alias, cert);
        try (OutputStream out = Files.newOutputStream(store)) {
            ks.store(out, PASSWORD.toCharArray());
        }
    }
}
