package com.towndemontower.delivery.governance;

/**
 * [delivery] 治理链拒绝异常。携带 core 的拒绝码与审批单号（若为待审批）。
 * Web 层据此映射 HTTP 状态：待审批 → 202，拒绝 → 403。
 */
public class DeliveryGovernanceException extends RuntimeException {

    private final String code;
    private final String approvalTicketId;

    public DeliveryGovernanceException(String code, String message, String approvalTicketId) {
        super(message == null ? code : message);
        this.code = code;
        this.approvalTicketId = approvalTicketId;
    }

    /** core 错误码（如 PERMISSION_DENIED / PENDING_APPROVAL / R3_DENIED）。 */
    public String code() {
        return code;
    }

    /** 审批单号；非待审批场景为 null。 */
    public String approvalTicketId() {
        return approvalTicketId;
    }

    /** 是否为"待人工审批"。 */
    public boolean isPendingApproval() {
        return "PENDING_APPROVAL".equals(code);
    }
}
