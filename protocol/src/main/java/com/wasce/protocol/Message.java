package com.wasce.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IDE 与 agent 之间传递的消息。所有属性值都是字符串，保持编解码简单且零依赖。
 */
public final class Message {
    public static final byte HELLO = 1;
    public static final byte REQUEST = 2;
    public static final byte RESPONSE = 3;
    public static final byte EVENT = 4;

    public final byte kind;
    public final long id;
    /** 请求为操作名，事件为事件类型，其余可为空串 */
    public final String name;
    public final boolean ok;
    public final Map<String, String> attrs;

    public Message(byte kind, long id, String name, boolean ok, Map<String, String> attrs) {
        this.kind = kind;
        this.id = id;
        this.name = name == null ? "" : name;
        this.ok = ok;
        this.attrs = attrs == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(attrs));
    }

    public static Message hello(Map<String, String> attrs) {
        return new Message(HELLO, 0, "hello", true, attrs);
    }

    public static Message request(long id, String op, Map<String, String> params) {
        return new Message(REQUEST, id, op, true, params);
    }

    public static Message success(long id, Map<String, String> result) {
        return new Message(RESPONSE, id, "", true, result);
    }

    public static Message failure(long id, String code, String message, String detail) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(Protocol.KEY_ERROR_CODE, code);
        m.put(Protocol.KEY_ERROR_MESSAGE, message == null ? "" : message);
        m.put(Protocol.KEY_ERROR_DETAIL, detail == null ? "" : detail);
        return new Message(RESPONSE, id, "", false, m);
    }

    public static Message event(String type, Map<String, String> attrs) {
        return new Message(EVENT, 0, type, true, attrs);
    }

    public String get(String key) {
        return attrs.get(key);
    }

    @Override
    public String toString() {
        return "Message{kind=" + kind + ", id=" + id + ", name='" + name + "', ok=" + ok + ", attrs=" + attrs.keySet() + "}";
    }
}
