package com.wasce.core;

import com.wasce.core.install.WasHome;
import com.wasce.core.profile.ProfileReader;
import com.wasce.core.profile.ServerConfig;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 以模拟 WAS 生成的 profile 作为解析样本（结构与真实 WAS 一致）。
 */
public class ProfileReaderTest {
    static Path fakeWasHome() {
        return Path.of(System.getProperty("wasce.fakeWas.home"));
    }

    @Test
    public void detectsFakeInstallation() {
        WasHome home = new WasHome(fakeWasHome());
        assertNull(home.validate());
        assertEquals("8.5.5.99", home.version());
        assertTrue(home.isFake());
        assertTrue(home.findJre().endsWith("java_1.8_64/jre"));
        assertEquals(1, home.profiles().size());
    }

    @Test
    public void readsServerConfig() throws Exception {
        Path profile = fakeWasHome().resolve("profiles/AppSrv01");
        List<ProfileReader.ServerId> servers = ProfileReader.listServers(profile);
        assertEquals(List.of(new ProfileReader.ServerId("Cell01", "Node01", "server1")), servers);

        ServerConfig c = ProfileReader.read(profile, servers.get(0));
        assertEquals("localhost", c.soapHost);
        assertEquals(18880, c.soapPort);
        assertEquals(19080, c.httpPort);
        assertFalse(c.securityEnabled);
        assertEquals(profile.resolve("logs/server1/SystemOut.log"), c.systemOut);
        assertEquals(profile.resolve("logs/server1/SystemErr.log"), c.systemErr);
        assertEquals(profile.resolve("logs/server1/server1.pid"), c.pidFile);
        assertFalse(c.debugMode);
        assertEquals(7777, ProfileReader.debugPortOf(c.debugArgs));
    }

    @Test
    public void parsesDebugPortVariants() {
        assertEquals(7777, ProfileReader.debugPortOf("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=7777"));
        assertEquals(8000, ProfileReader.debugPortOf("-Xrunjdwp:transport=dt_socket,address=localhost:8000"));
        assertEquals(0, ProfileReader.debugPortOf(""));
    }

    @Test
    public void serverIdRoundTrip() {
        assertEquals(new ProfileReader.ServerId("a", "b", "c"), ProfileReader.ServerId.parse("a/b/c"));
        assertNull(ProfileReader.ServerId.parse("a/b"));
    }
}
