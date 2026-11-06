package com.towndemontower.agents.service;

/**
 * [agents] Agent 不存在（HTTP 404）。
 */
public class AgentNotFoundException extends RuntimeException {

    private final String id;

    public AgentNotFoundException(String id) {
        super("Agent 不存在：" + id);
        this.id = id;
    }

    public String id() {
        return id;
    }
}
