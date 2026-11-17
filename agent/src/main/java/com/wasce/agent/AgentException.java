package com.wasce.agent;

/**
 * 带错误码的 agent 异常，错误码取自 Protocol.ERR_*，会原样回传给 IDE。
 */
public class AgentException extends Exception {
    private final String code;

    public AgentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AgentException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
