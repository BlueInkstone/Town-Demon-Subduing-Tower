package com.towndemontower.agents.governance;

/**
 * [agents] 治理链拒绝（HTTP 403 / 202）。
 */
public class AgentGovernanceException extends RuntimeException {

    private final String code;
    private final String approvalTicketId;

    public AgentGovernanceException(String code, String message) {
        this(code, message, null);
    }

    public AgentGovernanceException(String code, String message, String approvalTicketId) {
        super(message);
        this.code = code;
        this.approvalTicketId = approvalTicketId;
    }

    public String code() {
        return code;
    }

    public String approvalTicketId() {
        return approvalTicketId;
    }

    /** 是否属于"已转人工审批、等待处理"（HTTP 202 而非 403）。 */
    public boolean isPendingApproval() {
        return approvalTicketId != null;
    }
}
