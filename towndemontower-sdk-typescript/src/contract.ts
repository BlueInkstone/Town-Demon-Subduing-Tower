/**
 * 契约层 - 对齐 core/contract/ 模块
 */

/** 风险级别 */
export enum RiskLevel {
  R0 = "R0",  // 只读操作
  R1 = "R1",  // 低风险写操作
  R2 = "R2",  // 中风险，需审批
  R3 = "R3",  // 高风险，拒绝或需明确审批
}

/** 错误码 - 对齐 core ErrorCodes.java */
export const ErrorCodes = {
  MODE_DENIED: "MODE_DENIED",
  INTENT_INVALID: "INTENT_INVALID",
  SCHEMA_MISMATCH: "SCHEMA_MISMATCH",
  CAPABILITY_EXCEEDED: "CAPABILITY_EXCEEDED",
  PERMISSION_DENIED: "PERMISSION_DENIED",
  RISK_REJECTED: "RISK_REJECTED",
  APPROVAL_REQUIRED: "APPROVAL_REQUIRED",
  SANDBOX_DENIED: "SANDBOX_DENIED",
  AUDIT_FAILED: "AUDIT_FAILED",
  UNGOVERNED_INVOCATION: "UNGOVERNED_INVOCATION",
  COMMAND_TEMPLATE_MISMATCH: "COMMAND_TEMPLATE_MISMATCH",
} as const;

export type ErrorCode = typeof ErrorCodes[keyof typeof ErrorCodes];

/** 意图对象 - 对齐 core IntentObject.java */
export interface IntentObject {
  readonly tool: string;
  readonly args: Readonly<Record<string, unknown>>;
  readonly confidence: number;
  readonly text: string;
}

/** 创建意图对象（冻结） */
export function createIntent(
  tool: string,
  args: Record<string, unknown> = {},
  confidence: number = 1.0,
  text: string = "",
): IntentObject {
  if (!tool) throw new Error("tool is required");
  if (confidence < 0 || confidence > 1) throw new Error("confidence must be between 0 and 1");
  return Object.freeze({
    tool,
    args: Object.freeze({ ...args }),
    confidence,
    text,
  });
}

/** 技能描述符 - 对齐 core SkillDescriptor.java */
export interface SkillDescriptor {
  readonly name: string;
  readonly description?: string;
  readonly risk: RiskLevel;
  readonly requiredPermissions: readonly string[];
  readonly requiredCapabilities: readonly string[];
  readonly requiredArgs: readonly string[];
  readonly commandTemplate?: string;
  readonly requiresPlanApproval?: boolean;
}

/** 执行上下文 - 对齐 core ExecutionContext.java */
export interface ExecutionContext {
  readonly invocationId?: string;
  readonly mode: string;
  readonly agentId?: string;
  readonly ownerUserId?: string;
  readonly force: boolean;
}

/** 创建执行上下文（默认值） */
export function createContext(
  overrides: Partial<ExecutionContext> = {},
): ExecutionContext {
  return Object.freeze({
    invocationId: overrides.invocationId,
    mode: overrides.mode ?? "NORMAL",
    agentId: overrides.agentId,
    ownerUserId: overrides.ownerUserId,
    force: overrides.force ?? false,
  });
}

/** 技能执行结果 - 对齐 core SkillResult.java */
export interface SkillResult {
  readonly success: boolean;
  readonly output?: unknown;
  readonly errorCode?: ErrorCode | string;
  readonly errorMessage?: string;
  readonly ticketId?: string;
  readonly auditId?: string;
}

/** 成功结果 */
export function ok(output?: unknown, auditId?: string): SkillResult {
  return Object.freeze({ success: true, output, auditId });
}

/** 失败结果 */
export function fail(
  errorCode: ErrorCode | string,
  errorMessage: string,
  ticketId?: string,
): SkillResult {
  return Object.freeze({ success: false, errorCode, errorMessage, ticketId });
}

/** 审批单 - 对齐 core ApprovalTicket.java */
export interface ApprovalTicket {
  readonly ticketId: string;
  readonly tool: string;
  readonly riskLevel: RiskLevel;
  readonly reason: string;
  readonly createdAt: string;
}

/** 生成审批单 ID */
export function newTicketId(): string {
  const rand = Math.random().toString(16).slice(2, 14);
  return `TKT-${rand.padEnd(12, "0").slice(0, 12)}`;
}
