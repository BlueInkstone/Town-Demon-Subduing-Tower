package com.towndemontower.agents.service;

/**
 * [agents] Agent 档案字段校验失败（业务语义，HTTP 400）。
 */
public class AgentValidationException extends RuntimeException {

    private final String field;

    public AgentValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
