package com.towndemontower.core.contract;

/**
 * [core] 执行结果。统一用 code 表达成功/拒绝/补充信息/待审批。
 */
public class SkillResult {
    public final boolean success;
    public final String code;
    public final String message;
    public final Object output;
    public final String approvalTicketId;

    private SkillResult(boolean success, String code, String message, Object output, String approvalTicketId) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.output = output;
        this.approvalTicketId = approvalTicketId;
    }

    public static SkillResult success(Object out) {
        return new SkillResult(true, "OK", null, out, null);
    }

    public static SkillResult denied(String code, String msg) {
        return new SkillResult(false, code, msg, null, null);
    }

    public static SkillResult needMoreInfo(String msg) {
        return new SkillResult(false, "NEED_MORE_INFO", msg, null, null);
    }

    public static SkillResult pendingApproval(String ticketId) {
        return new SkillResult(false, "PENDING_APPROVAL", "awaiting human approval", null, ticketId);
    }
}
