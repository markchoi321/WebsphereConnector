package com.wasce.protocol;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class MessageIOTest {

    @Test
    public void roundTripKeepsAllFields() throws IOException {
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("host", "localhost");
        attrs.put("中文键", "中文值\n换行");
        attrs.put("empty", "");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        MessageIO.write(out, Message.request(42, Protocol.OP_CONNECT, attrs));
        MessageIO.write(out, Message.failure(43, Protocol.ERR_AUTH, "认证失败", null));

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        Message a = MessageIO.read(in);
        assertEquals(Message.REQUEST, a.kind);
        assertEquals(42, a.id);
        assertEquals(Protocol.OP_CONNECT, a.name);
        assertEquals(attrs, a.attrs);

        Message b = MessageIO.read(in);
        assertFalse(b.ok);
        assertEquals(Protocol.ERR_AUTH, b.get(Protocol.KEY_ERROR_CODE));
        assertEquals("", b.get(Protocol.KEY_ERROR_DETAIL));

        try {
            MessageIO.read(in);
            fail("流结束应抛出 EOFException");
        } catch (EOFException expected) {
            // 预期
        }
    }

    @Test(expected = IOException.class)
    public void rejectsIllegalFrameLength() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(MessageIO.MAX_FRAME + 1);
        MessageIO.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }
}
