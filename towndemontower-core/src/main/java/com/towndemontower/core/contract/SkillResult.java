package com.towndemontower.core.contract;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [core] 执行结果。统一用 code 表达成功/拒绝/补充信息/待审批。
 *
 * <p>字段集与已定稿的 {@code contract/towndemontower-wire.schema.json}
 * 的 {@code $defs/SkillResult} 完全一致（success / code / message / output / approvalTicketId），
 * 因此 Java 侧不需要改结构，只补 wire 出入口。</p>
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
        // 成功码从字面量收编为规范常量（contract/error-codes.json successCode）
        return new SkillResult(true, ErrorCodeCatalog.OK, null, out, null);
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

    /**
     * 输出统一 wire 结构：恰好 5 个 camelCase 字段，缺失项显式为 {@code null}。
     * {@code additionalProperties=false} —— 不夹带任何语言本地字段。
     * code 经 {@link ErrorCodeCatalog#canonicalize} 归一，保证输出一律是规范码。
     */
    public Map<String, Object> toWire() {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("success", success);
        w.put("code", ErrorCodeCatalog.canonicalize(code));
        w.put("message", message);
        w.put("output", output);
        w.put("approvalTicketId", approvalTicketId);
        return w;
    }

    /** 从统一 wire 结构还原；未知字段忽略，缺失字段按 null 处理。 */
    public static SkillResult fromWire(Map<String, Object> w) {
        boolean ok = Boolean.TRUE.equals(w.get("success"));
        String code = (String) w.get("code");
        String msg = (String) w.get("message");
        Object out = w.get("output");
        String ticket = (String) w.get("approvalTicketId");
        return new SkillResult(ok, code, msg, out, ticket);
    }
}
