/**
 * 跨语言错误码目录 - 方向 D 定稿口径绑定
 *
 * 唯一权威数据：仓库根 `contract/error-codes.json`（校验脚本 `contract/verify_codes.py`）。
 * 本模块只做「别名 → 规范码」归一与统一统计口径，不改动 `contract.ts` 的 `ErrorCodes` 既有值。
 *
 * 归一规则：别名只允许输入，输出一律规范码；未映射码保留原值。
 * `EXECUTION_ERROR` / `AUDIT_FAILED` / `APPROVAL_EXPIRED` / `APPROVAL_SELF_DENIED`
 * 按 docs/35 §6.3 要求「先评审 / 澄清」，本轮不归一。
 */

export const CANONICAL_CODES: ReadonlySet<string> = new Set<string>([
  "OK",
  "INTENT_FORMAT_INVALID",
  "NEED_MORE_INFO",
  "R3_DENIED",
  "TAINTED_WRITE_BLOCKED",
  "MODE_TOOL_NOT_ALLOWED",
  "MODE_TRANSITION_INVALID",
  "PLAN_APPROVAL_REQUIRED",
  "GATE_SCRIPT_FAILED",
  "AST_ANALYSIS_FALLBACK_R2",
  "SNAPSHOT_FAILED",
  "PENDING_APPROVAL",
  "CANCELLED",
  "SANDBOX_UNAVAILABLE",
  "GOVERNANCE_DISABLED",
  "INPUT_REJECTED",
  "GATE_KEY_UNAVAILABLE",
  "CALLER_UNAUTHENTICATED",
  "AUTH_TOKEN_UNAVAILABLE",
  "CALLER_NOT_LOCAL",
  "CALLER_NOT_PROVISIONED",
  "CALLER_AGENT_UNAVAILABLE",
  "UNGOVERNED_INVOCATION",
  "COMMAND_TEMPLATE_MISMATCH",
  "COMMAND_TEMPLATE_INVALID",
  "PERMISSION_DENIED",
  "CAPABILITY_NOT_ALLOWED",
  "INTERNAL_ERROR",
  "APPROVAL_DENIED",
  "STATE_PERSIST_FAILED",
]);

export const SUCCESS_CODE = "OK";
export const STAT_PENDING_APPROVAL = "PENDING_APPROVAL";
export const STAT_R3_DENIED = "R3_DENIED";

export const ERROR_CODE_ALIASES: Readonly<Record<string, string>> = Object.freeze({
  INTENT_FORMAT_ERROR: "INTENT_FORMAT_INVALID",
  INTENT_INVALID: "INTENT_FORMAT_INVALID",
  SCHEMA_VIOLATION: "INTENT_FORMAT_INVALID",
  SCHEMA_MISMATCH: "INTENT_FORMAT_INVALID",
  RISK_REJECTED: "R3_DENIED",
  APPROVAL_REQUIRED: "PENDING_APPROVAL",
  CAPABILITY_EXCEEDED: "CAPABILITY_NOT_ALLOWED",
  TAINT_BLOCKED: "TAINTED_WRITE_BLOCKED",
  MODE_NOT_ALLOWED: "MODE_TOOL_NOT_ALLOWED",
  MODE_DENIED: "MODE_TOOL_NOT_ALLOWED",
  SANDBOX_VIOLATION: "SANDBOX_UNAVAILABLE",
  SANDBOX_DENIED: "SANDBOX_UNAVAILABLE",
});

/** docs/35 §6.3 要求先评审 / 澄清，本轮不归一；统计时按普通拒绝处理。 */
export const UNMAPPED_REQUIRES_REVIEW: ReadonlySet<string> = new Set<string>([
  "EXECUTION_ERROR",
  "AUDIT_FAILED",
  "APPROVAL_EXPIRED",
  "APPROVAL_SELF_DENIED",
]);

/** 归一：null / 空白原样返回；别名转规范码；未映射码保留原值。 */
export function canonicalCode(code: string | null | undefined): string | null | undefined {
  if (code === null || code === undefined || code.trim() === "") return code;
  const cleaned = code.trim();
  return ERROR_CODE_ALIASES[cleaned] ?? cleaned;
}

export function isCanonicalCode(code: string | null | undefined): boolean {
  return code !== null && code !== undefined && CANONICAL_CODES.has(code.trim());
}

/**
 * 统一拒绝口径：非 OK 且非 PENDING_APPROVAL 的码计入 denied。
 * 与 Java 侧 `ErrorCodeCatalog.countsAsDenied` / `AuditDecorator` 完全一致。
 */
export function countsAsDenied(canonical: string | null | undefined): boolean {
  return canonical !== null && canonical !== undefined
    && canonical !== SUCCESS_CODE
    && canonical !== STAT_PENDING_APPROVAL;
}
