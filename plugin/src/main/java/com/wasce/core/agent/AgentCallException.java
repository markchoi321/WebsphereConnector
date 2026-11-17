package com.wasce.core.agent;

/**
 * agent 调用失败，code 取自 Protocol.ERR_*。
 */
public final class AgentCallException extends Exception {
    private final String code;
    private final String detail;

    public AgentCallException(String code, String message, String detail) {
        super(message);
        this.code = code;
        this.detail = detail == null ? "" : detail;
    }

    public String getCode() {
        return code;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return "[" + code + "] " + getMessage();
    }
}
