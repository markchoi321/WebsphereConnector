package com.wasce.idea.util;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;

/**
 * 管理密码存取（PasswordSafe），运行配置 XML 中只保存凭据 key。
 * 读写可能较慢（系统钥匙串），调用方应在后台线程调用。
 */
public final class WasCredentials {
    private WasCredentials() {
    }

    private static CredentialAttributes attributes(String key) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName("WebSphere CE", key));
    }

    public static String load(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        Credentials c = PasswordSafe.getInstance().get(attributes(key));
        return c == null ? null : c.getPasswordAsString();
    }

    public static void save(String key, String user, String password) {
        PasswordSafe.getInstance().set(attributes(key),
                password == null || password.isEmpty() ? null : new Credentials(user, password));
    }
}
