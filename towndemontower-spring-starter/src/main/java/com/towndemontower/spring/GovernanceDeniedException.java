package com.towndemontower.spring;

/**
 * [starter] 治理拒绝异常。core 装饰器链返回 {@code SkillResult.success=false} 时，
 * 切面据此抛出本异常，调用方可用 {@link #getCode()} 区分拒绝类型（R3_DENIED /
 * PENDING_APPROVAL / MODE_TOOL_NOT_ALLOWED / PERMISSION_DENIED 等），用
 * {@link #getApprovalTicketId()} 取审批单号。
 */
public class GovernanceDeniedException extends RuntimeException {

    private final String code;
    private final String approvalTicketId;

    public GovernanceDeniedException(String code, String message) {
        this(code, message, null);
    }

    public GovernanceDeniedException(String code, String message, String approvalTicketId) {
        super(message);
        this.code = code;
        this.approvalTicketId = approvalTicketId;
    }

    public String getCode() {
        return code;
    }

    public String getApprovalTicketId() {
        return approvalTicketId;
    }
}
