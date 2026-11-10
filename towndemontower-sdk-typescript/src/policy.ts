/**
 * 策略模块 - 对齐 core/policy/ 模块
 */

import {
  RiskLevel,
  IntentObject,
  ExecutionContext,
  SkillDescriptor,
  ApprovalTicket,
  newTicketId,
} from "./contract";

/** 风险闸门接口 - 对齐 core RiskGate.java */
export interface IRiskGate {
  evaluate(
    intent: IntentObject,
    descriptor: SkillDescriptor | null,
    context: ExecutionContext,
  ): { risk: RiskLevel; ticket: ApprovalTicket | null };
}

const READONLY_PREFIXES = ["get", "list", "query", "search", "read", "view", "show"];
const HIGH_RISK_KEYWORDS = ["delete", "destroy", "drop", "truncate", "rm", "format", "reset"];

/** 默认风险闸门 - 对齐 core DefaultRiskGate.java */
export class DefaultRiskGate implements IRiskGate {
  evaluate(
    intent: IntentObject,
    descriptor: SkillDescriptor | null,
    _context: ExecutionContext,
  ): { risk: RiskLevel; ticket: ApprovalTicket | null } {
    if (descriptor) {
      const risk = descriptor.risk;
      let ticket: ApprovalTicket | null = null;
      if (risk === RiskLevel.R2) {
        ticket = {
          ticketId: newTicketId(),
          tool: intent.tool,
          riskLevel: risk,
          reason: "Medium risk operation requires approval",
          createdAt: new Date().toISOString(),
        };
      } else if (risk === RiskLevel.R3) {
        ticket = {
          ticketId: newTicketId(),
          tool: intent.tool,
          riskLevel: risk,
          reason: "High risk operation requires explicit approval",
          createdAt: new Date().toISOString(),
        };
      }
      return { risk, ticket };
    }

    const toolLower = intent.tool.toLowerCase();
    if (READONLY_PREFIXES.some((p) => toolLower.startsWith(p))) {
      return { risk: RiskLevel.R0, ticket: null };
    }
    if (HIGH_RISK_KEYWORDS.some((k) => toolLower.includes(k))) {
      const ticket: ApprovalTicket = {
        ticketId: newTicketId(),
        tool: intent.tool,
        riskLevel: RiskLevel.R3,
        reason: `High risk keyword detected in tool: ${intent.tool}`,
        createdAt: new Date().toISOString(),
      };
      return { risk: RiskLevel.R3, ticket };
    }
    return { risk: RiskLevel.R1, ticket: null };
  }
}

/** 命令模板验证器 - 对齐 core CommandTemplate.java */
export class CommandTemplateValidator {
  private static readonly FORBIDDEN_CHARS = new Set(";|&$`<>(){}!'\"\\".split(""));

  static validate(
    command: string,
    template: string,
  ): { valid: boolean; error?: string } {
    if (!command) return { valid: false, error: "Command is empty" };
    if (command.length > 2048) return { valid: false, error: "Command too long (max 2048)" };
    if (!template) return { valid: true };

    const templateParts = template.split(/\s+/).filter(Boolean);
    const commandParts = command.split(/\s+/).filter(Boolean);

    const hasTrailingWildcard =
      templateParts.length > 0 &&
      templateParts[templateParts.length - 1].startsWith("{") &&
      templateParts[templateParts.length - 1].endsWith("*}");

    const fixedLen = hasTrailingWildcard ? templateParts.length - 1 : templateParts.length;

    // 检查多余参数（非通配符模式）
    if (!hasTrailingWildcard && commandParts.length > templateParts.length) {
      return {
        valid: false,
        error: `Extra arguments: ${commandParts.slice(templateParts.length).join(" ")}`,
      };
    }

    // 检查缺少参数
    if (commandParts.length < fixedLen) {
      return {
        valid: false,
        error: `Missing required argument: ${templateParts[commandParts.length]}`,
      };
    }

    // 逐词匹配固定部分
    for (let i = 0; i < fixedLen; i++) {
      const tPart = templateParts[i];
      const cPart = commandParts[i];

      if (tPart.startsWith("{") && tPart.endsWith("}") && !tPart.endsWith("*}")) {
        // 单值槽位
        if (CommandTemplateValidator._hasForbiddenChars(cPart)) {
          return { valid: false, error: `Parameter contains forbidden characters: ${cPart}` };
        }
        if (cPart.startsWith("-")) {
          return { valid: false, error: `Single-value slot cannot start with -: ${cPart}` };
        }
      } else {
        // 字面量精确匹配
        if (tPart !== cPart) {
          return { valid: false, error: `Expected '${tPart}' but got '${cPart}'` };
        }
      }
    }

    // 尾部通配符槽：只检查禁止字符，允许 `-` 开头
    if (hasTrailingWildcard) {
      for (let i = fixedLen; i < commandParts.length; i++) {
        if (CommandTemplateValidator._hasForbiddenChars(commandParts[i])) {
          return { valid: false, error: `Parameter contains forbidden characters: ${commandParts[i]}` };
        }
      }
    }

    return { valid: true };
  }

  private static _hasForbiddenChars(value: string): boolean {
    return value.split("").some((c) => CommandTemplateValidator.FORBIDDEN_CHARS.has(c));
  }
}
