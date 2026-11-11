/**
 * Town Demontower TypeScript SDK
 * 版本: v2.0.0
 *
 * 对齐 Java core 契约，提供纯函数治理链路。
 */

export {
  RiskLevel,
  ErrorCodes,
  createIntent,
  createContext,
  ok,
  fail,
  newTicketId,
} from "./contract";

export type {
  IntentObject,
  SkillDescriptor,
  ExecutionContext,
  SkillResult,
  ApprovalTicket,
  ErrorCode,
} from "./contract";

export { HashChainAuditStore, FileAuditStore, computeHash } from "./audit";
export type { AuditRecord, IAuditStore } from "./audit";

export { DefaultRiskGate, CommandTemplateValidator } from "./policy";
export type { IRiskGate } from "./policy";

export { GovernancePass, UngovernedInvocationError, MetricsCollector } from "./decorator";
export type { GovernancePassToken, GovernanceMetricsSnapshot } from "./decorator";

export {
  Executor,
  SkillInvoker,
  CapabilityException,
  IntentFormatException,
  SchemaValidationException,
  PermissionDeniedException,
  RejectAllExecutor,
  TypeScriptFunctionExecutor,
  SkillRegistry,
} from "./executor";
export type {
  SkillHandler,
  ClientConfig,
} from "./client";

export { TownDemontowerClient } from "./client";

export { GovernanceRestClient, GovernanceApiError } from "./rest";
export type {
  ApprovalStatus,
  MetricsReport,
  IntegrityReport,
  AnchorReplicaView,
  ApprovalView,
  ApprovalListData,
  ApiEnvelope,
  FetchLike,
  RestErrorConfig,
} from "./rest";

// 方向 D / 定稿口径：跨语言错误码归一与统一统计口径
export {
  CANONICAL_CODES,
  SUCCESS_CODE,
  STAT_PENDING_APPROVAL,
  STAT_R3_DENIED,
  ERROR_CODE_ALIASES,
  UNMAPPED_REQUIRES_REVIEW,
  canonicalCode,
  isCanonicalCode,
  countsAsDenied,
} from "./codes";
