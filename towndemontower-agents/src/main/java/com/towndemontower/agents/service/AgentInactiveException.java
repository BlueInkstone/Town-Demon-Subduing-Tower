package com.towndemontower.agents.service;

import com.towndemontower.agents.domain.AgentStatus;

/**
 * [agents] Agent 当前状态不允许执行（HTTP 409）。
 *
 * <p>用于执行前守卫：PAUSED / SUSPENDED / RETIRED 的 Agent 不能被派发任务。
 * 与"权限不足（403）"刻意区分开——前者是<b>状态问题</b>（改状态），
 * 后者是<b>授权问题</b>（改权限），混成一个码会让排查方向跑偏。</p>
 */
public class AgentInactiveException extends RuntimeException {

    private final String agentId;
    private final AgentStatus status;

    public AgentInactiveException(String agentId, AgentStatus status) {
        super("Agent " + agentId + " 当前状态为「" + (status == null ? "未知" : status.label())
                + "」，不允许执行任务");
        this.agentId = agentId;
        this.status = status;
    }

    public String agentId() {
        return agentId;
    }

    public AgentStatus status() {
        return status;
    }
}
