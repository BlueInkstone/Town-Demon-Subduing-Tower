/**
 * 客户端模块 - 统一 SDK 入口
 */

import {
  IntentObject,
  ExecutionContext,
  SkillResult,
  SkillDescriptor,
  RiskLevel,
  ErrorCodes,
  createIntent,
  createContext,
  ok,
  fail,
} from "./contract";

import { IAuditStore, HashChainAuditStore } from "./audit";
import { DefaultRiskGate, CommandTemplateValidator } from "./policy";
import { MetricsCollector } from "./decorator";
import { TypeScriptFunctionExecutor } from "./executor";

/** 技能处理函数类型 */
export type SkillHandler = (args: Record<string, unknown>, ctx: ExecutionContext) => unknown | Promise<unknown>;

/** 客户端配置 */
export interface ClientConfig {
  auditStore?: IAuditStore;
  defaultMode?: string;
  defaultAgentId?: string;
  metricsEnabled?: boolean;
}

/** Town Demontower 客户端 - 对齐 Java TownDemontowerClient */
export class TownDemontowerClient {
  // 统一执行链路：技能注册表与处理器均由执行器托管
  private _executor = new TypeScriptFunctionExecutor();
  private _audit: IAuditStore;
  private _riskGate = new DefaultRiskGate();
  private _metrics: MetricsCollector;
  private _defaultMode: string;
  private _defaultAgentId?: string;

  constructor(config: ClientConfig = {}) {
    this._audit = config.auditStore ?? new HashChainAuditStore();
    this._metrics = new MetricsCollector();
    this._defaultMode = config.defaultMode ?? "NORMAL";
    this._defaultAgentId = config.defaultAgentId;
  }

  /** 注册技能 */
  skill(
    name: string,
    descriptor: Partial<SkillDescriptor> & { risk: RiskLevel },
    handler: SkillHandler,
  ): void {
    const full: SkillDescriptor = Object.freeze({
      name,
      risk: descriptor.risk,
      description: descriptor.description ?? "",
      requiredPermissions: descriptor.requiredPermissions ?? [],
      requiredCapabilities: descriptor.requiredCapabilities ?? [],
      requiredArgs: descriptor.requiredArgs ?? [],
      commandTemplate: descriptor.commandTemplate,
      requiresPlanApproval: descriptor.requiresPlanApproval ?? false,
    });
    this._executor.register(name, handler, full);
  }

  /** 调用技能 */
  async invoke(
    tool: string,
    args: Record<string, unknown> = {},
    contextOverrides: Partial<ExecutionContext> = {},
  ): Promise<SkillResult> {
    const intent = createIntent(tool, args);
    const ctx = createContext({
      mode: contextOverrides.mode ?? this._defaultMode,
      agentId: contextOverrides.agentId ?? this._defaultAgentId,
      ...contextOverrides,
    });

    this._metrics.recordInvocation();

    // 1. 检查技能存在
    const descriptor = this._executor.getDescriptor(tool);
    if (!descriptor) {
      this._audit.append("CAPABILITY_EXCEEDED", { tool });
      return fail(ErrorCodes.CAPABILITY_EXCEEDED, `Unknown tool: ${tool}`);
    }

    // 2. 命令模板验证
    if (descriptor.commandTemplate) {
      const commandStr = (args["command"] as string) ?? "";
      const { valid, error } = CommandTemplateValidator.validate(commandStr, descriptor.commandTemplate);
      if (!valid) {
        this._audit.append("COMMAND_TEMPLATE_MISMATCH", { tool, error });
        return fail(ErrorCodes.COMMAND_TEMPLATE_MISMATCH, error ?? "Command template mismatch");
      }
    }

    // 3. 风险闸门
    const { risk, ticket } = this._riskGate.evaluate(intent, descriptor, ctx);
    this._audit.append("RISK_EVALUATED", {
      tool,
      risk,
      mode: ctx.mode,
      agent_id: ctx.agentId,
    });

    if (risk === RiskLevel.R3) {
      this._metrics.recordR3Rejected();
      this._audit.append("RISK_REJECTED", { tool, risk });
      return fail(ErrorCodes.RISK_REJECTED, `Risk level ${risk} rejected`, ticket?.ticketId);
    }

    if (risk === RiskLevel.R2) {
      this._metrics.recordR2Pending();
      this._audit.append("APPROVAL_REQUIRED", { tool, risk, ticketId: ticket?.ticketId });
      return fail(ErrorCodes.APPROVAL_REQUIRED, "Approval required", ticket?.ticketId);
    }

    if (risk === RiskLevel.R0) this._metrics.recordR0Pass();
    else if (risk === RiskLevel.R1) this._metrics.recordR1Pass();

    // 4. 统一执行链路：交由执行器调用已注册处理函数
    this._audit.append("SKILL_INVOKED", { tool, risk });
    const result = await this._executor.invoke(intent, ctx);
    if (result.success) {
      const auditId = this._audit.append("SKILL_COMPLETED", { tool, success: true });
      return ok(result.output, auditId);
    }

    this._audit.append("SKILL_FAILED", { tool, error: result.errorMessage ?? "execution failed" });
    return fail("EXECUTION_ERROR", result.errorMessage ?? "Execution failed");
  }

  /** 注销技能 */
  unregisterSkill(name: string): void {
    this._executor.unregister(name);
  }

  /** 列出所有技能 */
  listSkills(): ReadonlyArray<{ name: string; risk: RiskLevel; description?: string }> {
    return this._executor.listSkills().map((name) => {
      const d = this._executor.getDescriptor(name)!;
      return { name: d.name, risk: d.risk, description: d.description };
    });
  }

  /** 获取审计记录 */
  getAuditRecords(): ReadonlyArray<{ seq: number; action: string; payload: Record<string, unknown>; timestamp: string }> {
    return this._audit.all().map((r) => ({
      seq: r.seq,
      action: r.action,
      payload: r.payload as Record<string, unknown>,
      timestamp: r.timestamp,
    }));
  }

  /** 验证审计链完整性 */
  verifyAuditChain(): boolean {
    return this._audit.verify();
  }

  /** 获取治理指标 */
  getMetrics(): Readonly<import("./decorator").GovernanceMetricsSnapshot> {
    return this._metrics.snapshot();
  }
}
