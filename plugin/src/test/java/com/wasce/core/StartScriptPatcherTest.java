package com.wasce.core;

import com.wasce.core.process.StartScriptPatcher;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StartScriptPatcherTest {
    private static final List<String> ARGS = List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:7777", "-Dx=a b");

    @Test
    public void injectsAfterQuotedJavaOnServerLine() throws Exception {
        String script = "#!/bin/sh\ncd \"/p\"\n\"/was/java/jre/bin/java\" -Xms64m -cp x com.ibm.wsspi.bootstrap.WSPreLauncher com.ibm.ws.runtime.WsServer /p/config c n s\n";
        String patched = StartScriptPatcher.patch(script, ARGS, false);
        assertTrue(patched.contains("\"/was/java/jre/bin/java\" -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:7777 '-Dx=a b' -Xms64m"));
        assertTrue(patched.startsWith("#!/bin/sh\ncd \"/p\"\n"));
    }

    @Test
    public void handlesContinuationLines() throws Exception {
        String script = "#!/bin/sh\nexec $JAVA_HOME/bin/java \\\n  -Xmx512m \\\n  com.ibm.ws.runtime.WsServer cfg c n s\n";
        String patched = StartScriptPatcher.patch(script, List.of("-Da=1"), false);
        assertEquals("#!/bin/sh\nexec $JAVA_HOME/bin/java -Da=1 \\\n  -Xmx512m \\\n  com.ibm.ws.runtime.WsServer cfg c n s\n", patched);
    }

    @Test
    public void ignoresJavaOnOtherLines() throws Exception {
        String script = "\"/was/bin/java\" -version\n\"/was/java/bin/java\" com.ibm.ws.runtime.WsServer cfg\n";
        String patched = StartScriptPatcher.patch(script, List.of("-Da=1"), false);
        assertEquals("\"/was/bin/java\" -version\n\"/was/java/bin/java\" -Da=1 com.ibm.ws.runtime.WsServer cfg\n", patched);
    }

    @Test
    public void windowsBatchStyle() throws Exception {
        String script = "@echo off\r\n\"C:\\IBM\\WebSphere\\AppServer\\java\\bin\\java.exe\" ^\r\n -Xms64m com.ibm.ws.runtime.WsServer cfg\r\n";
        String patched = StartScriptPatcher.patch(script, List.of("-Dx=a b"), true);
        assertTrue(patched.contains("java.exe\" \"-Dx=a b\" ^\r\n"));
    }

    @Test(expected = StartScriptPatcher.PatchException.class)
    public void failsWhenMarkerMissing() throws Exception {
        StartScriptPatcher.patch("#!/bin/sh\n/usr/bin/java -version\n", List.of("-Da=1"), false);
    }

    @Test(expected = StartScriptPatcher.PatchException.class)
    public void failsWhenJavaNotFound() throws Exception {
        StartScriptPatcher.patch("${JAVA_EXE} com.ibm.ws.runtime.WsServer cfg\n", List.of("-Da=1"), false);
    }

    @Test
    public void noArgsReturnsOriginal() throws Exception {
        assertEquals("anything", StartScriptPatcher.patch("anything", List.of(), false));
    }
}
