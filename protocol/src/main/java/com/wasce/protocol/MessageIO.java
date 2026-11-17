package com.wasce.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息编解码。帧格式：4 字节长度 + 负载。
 * 负载：kind(1) id(8) name ok(1) count(4) [key value]*，字符串为 4 字节长度 + UTF-8 字节。
 * 写入方需自行保证同一输出流上的串行写入。
 */
public final class MessageIO {
    public static final int MAX_FRAME = 16 * 1024 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private MessageIO() {
    }

    public static void write(DataOutputStream out, Message m) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        DataOutputStream d = new DataOutputStream(buf);
        d.writeByte(m.kind);
        d.writeLong(m.id);
        writeString(d, m.name);
        d.writeBoolean(m.ok);
        d.writeInt(m.attrs.size());
        for (Map.Entry<String, String> e : m.attrs.entrySet()) {
            writeString(d, e.getKey());
            writeString(d, e.getValue());
        }
        d.flush();
        if (buf.size() > MAX_FRAME) {
            throw new IOException("消息过大: " + buf.size());
        }
        out.writeInt(buf.size());
        buf.writeTo(out);
        out.flush();
    }

    /** 读取一条消息，流结束时抛出 EOFException */
    public static Message read(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_FRAME) {
            throw new IOException("非法帧长度: " + len);
        }
        byte[] payload = new byte[len];
        in.readFully(payload);
        DataInputStream d = new DataInputStream(new java.io.ByteArrayInputStream(payload));
        byte kind = d.readByte();
        long id = d.readLong();
        String name = readString(d);
        boolean ok = d.readBoolean();
        int count = d.readInt();
        if (count < 0) {
            throw new IOException("非法属性数量: " + count);
        }
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        for (int i = 0; i < count; i++) {
            String k = readString(d);
            attrs.put(k, readString(d));
        }
        return new Message(kind, id, name, ok, attrs);
    }

    private static void writeString(DataOutputStream d, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes(UTF8);
        d.writeInt(b.length);
        d.write(b);
    }

    private static String readString(DataInputStream d) throws IOException {
        int n = d.readInt();
        if (n < 0 || n > MAX_FRAME) {
            throw new IOException("非法字符串长度: " + n);
        }
        byte[] b = new byte[n];
        d.readFully(b);
        return new String(b, UTF8);
    }
}
