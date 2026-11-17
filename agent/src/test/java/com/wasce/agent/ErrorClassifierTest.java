package com.wasce.agent;

import com.wasce.protocol.Protocol;
import org.junit.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;

import static org.junit.Assert.assertEquals;

public class ErrorClassifierTest {
    private static String code(Throwable t) {
        return ErrorClassifier.classify("连接", t).getCode();
    }

    @Test
    public void classifiesThroughCauseChain() {
        assertEquals(Protocol.ERR_SSL, code(new IOException("wrapper", new SSLHandshakeException("PKIX path building failed"))));
        assertEquals(Protocol.ERR_CONNECT_REFUSED, code(new IOException("ADMC0016E", new ConnectException("Connection refused"))));
        assertEquals(Protocol.ERR_BAD_PORT, code(new IOException("Error parsing HTTP status line \"SSH-2.0\"")));
        assertEquals(Protocol.ERR_AUTH, code(new IOException("ADMN0022E: Access is denied")));
        assertEquals(Protocol.ERR_DEPLOY_FAILED, code(new IOException("ADMA0063E: bad archive")));
        assertEquals(Protocol.ERR_INTERNAL, code(new IllegalStateException("x")));
    }
}
