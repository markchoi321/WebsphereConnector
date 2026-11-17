package com.wasce.agent;

import java.util.Map;

/**
 * 服务器管理后端。fake 对接模拟 WAS，jmx 对接真实 WAS。
 * handle 可能被多个线程并发调用，实现需自行保证线程安全。
 */
public interface Backend {
    /** 事件出口，用于推送部署进度 */
    interface EventSink {
        void emit(String type, Map<String, String> attrs);
    }

    void setEventSink(EventSink sink);

    Map<String, String> handle(String op, Map<String, String> params) throws AgentException;

    void close();
}
