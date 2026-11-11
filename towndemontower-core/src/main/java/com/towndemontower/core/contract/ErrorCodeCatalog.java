package com.towndemontower.core.contract;

import java.util.Map;
import java.util.Set;

/**
 * [core] 方向 D 定稿的跨语言错误码目录（唯一权威数据：仓库根 {@code contract/error-codes.json}）。
 *
 * <p>与 {@link ErrorCodes} 的关系：本类<b>不修改</b>既有常量类，只在契约层补齐
 * Java 里原本以字面量散落的规范码，并提供「别名 → 规范码」归一与统一统计口径。
 * 这样既有 1575/1575 回归零改动，同时三语言可以按同一套口径统计。</p>
 *
 * <p><b>归一规则</b>：别名只允许输入，输出一律规范码；未映射码保留原值。
 * {@code EXECUTION_ERROR} / {@code AUDIT_FAILED} / {@code APPROVAL_EXPIRED} /
 * {@code APPROVAL_SELF_DENIED} 按 docs/35 §6.3 要求「先评审/澄清」，本轮<b>不归一</b>。</p>
 */
public final class ErrorCodeCatalog {

    /** 唯一成功码（Java core 原为字面量，本次收进规范集合）。 */
    public static final String OK = "OK";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String CAPABILITY_NOT_ALLOWED = "CAPABILITY_NOT_ALLOWED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String APPROVAL_DENIED = "APPROVAL_DENIED";
    public static final String STATE_PERSIST_FAILED = "STATE_PERSIST_FAILED";

    /** 统计口径：与 {@code AuditDecorator} 一致。 */
    public static final String STAT_PENDING_APPROVAL = ErrorCodes.PENDING_APPROVAL;
    public static final String STAT_R3_DENIED = ErrorCodes.R3_DENIED;

    private static final Set<String> CANONICAL = Set.of(
            OK,
            ErrorCodes.INTENT_FORMAT_INVALID, ErrorCodes.NEED_MORE_INFO, ErrorCodes.R3_DENIED,
            ErrorCodes.TAINTED_WRITE_BLOCKED, ErrorCodes.MODE_TOOL_NOT_ALLOWED,
            ErrorCodes.MODE_TRANSITION_INVALID, ErrorCodes.PLAN_APPROVAL_REQUIRED,
            ErrorCodes.GATE_SCRIPT_FAILED, ErrorCodes.AST_ANALYSIS_FALLBACK_R2,
            ErrorCodes.SNAPSHOT_FAILED, ErrorCodes.PENDING_APPROVAL, ErrorCodes.CANCELLED,
            ErrorCodes.SANDBOX_UNAVAILABLE, ErrorCodes.GOVERNANCE_DISABLED,
            ErrorCodes.INPUT_REJECTED, ErrorCodes.GATE_KEY_UNAVAILABLE,
            ErrorCodes.CALLER_UNAUTHENTICATED, ErrorCodes.AUTH_TOKEN_UNAVAILABLE,
            ErrorCodes.CALLER_NOT_LOCAL, ErrorCodes.CALLER_NOT_PROVISIONED,
            ErrorCodes.CALLER_AGENT_UNAVAILABLE, ErrorCodes.UNGOVERNED_INVOCATION,
            ErrorCodes.COMMAND_TEMPLATE_MISMATCH, ErrorCodes.COMMAND_TEMPLATE_INVALID,
            PERMISSION_DENIED, CAPABILITY_NOT_ALLOWED, INTERNAL_ERROR,
            APPROVAL_DENIED, STATE_PERSIST_FAILED);

    /** 别名 → 规范码；与 contract/error-codes.json 的 aliases 逐条对应。 */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("INTENT_FORMAT_ERROR", ErrorCodes.INTENT_FORMAT_INVALID),
            Map.entry("INTENT_INVALID", ErrorCodes.INTENT_FORMAT_INVALID),
            Map.entry("SCHEMA_VIOLATION", ErrorCodes.INTENT_FORMAT_INVALID),
            Map.entry("SCHEMA_MISMATCH", ErrorCodes.INTENT_FORMAT_INVALID),
            Map.entry("RISK_REJECTED", ErrorCodes.R3_DENIED),
            Map.entry("APPROVAL_REQUIRED", ErrorCodes.PENDING_APPROVAL),
            Map.entry("CAPABILITY_EXCEEDED", CAPABILITY_NOT_ALLOWED),
            Map.entry("TAINT_BLOCKED", ErrorCodes.TAINTED_WRITE_BLOCKED),
            Map.entry("MODE_NOT_ALLOWED", ErrorCodes.MODE_TOOL_NOT_ALLOWED),
            Map.entry("MODE_DENIED", ErrorCodes.MODE_TOOL_NOT_ALLOWED),
            Map.entry("SANDBOX_VIOLATION", ErrorCodes.SANDBOX_UNAVAILABLE),
            Map.entry("SANDBOX_DENIED", ErrorCodes.SANDBOX_UNAVAILABLE));

    private ErrorCodeCatalog() {
    }

    /** 归一：null / 空白原样返回；别名转规范码；未映射码保留原值。 */
    public static String canonicalize(String code) {
        if (code == null || code.isBlank()) return code;
        String c = code.trim();
        String mapped = ALIASES.get(c);
        return mapped != null ? mapped : c;
    }

    public static boolean isCanonical(String code) {
        return code != null && CANONICAL.contains(code.trim());
    }

    public static Set<String> canonicalCodes() {
        return CANONICAL;
    }

    public static Map<String, String> aliases() {
        return ALIASES;
    }

    /**
     * 统一拒绝口径：非 OK 且非 PENDING_APPROVAL 的码计入 denied。
     * 与 {@code AuditDecorator.record()} 完全一致，三语言统计必须按此实现。
     */
    public static boolean countsAsDenied(String canonicalCode) {
        return canonicalCode != null
                && !OK.equals(canonicalCode)
                && !STAT_PENDING_APPROVAL.equals(canonicalCode);
    }
}
