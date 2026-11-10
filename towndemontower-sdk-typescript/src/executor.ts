/**
 * 执行器模块 - 对齐 core/executor/ 模块
 * 
 * 定义执行器协议和技能注册表。
 */

import {
  IntentObject,
  ExecutionContext,
  SkillResult,
  SkillDescriptor,
  ErrorCodes,
} from "./contract";

/**
 * 执行器协议 - 对齐 core Executor.java
 * 
 * 定义执行意图对象的标准接口。
 */
export interface Executor {
  /**
   * 执行意图对象
   * 
   * @param intent 要执行的意图
   * @param context 执行上下文
   * @returns 执行结果
   */
  execute(intent: IntentObject, context: ExecutionContext): SkillResult;
}

/**
 * 技能调用器协议 - 对齐 core SkillInvoker.java
 * 
 * 用于桥接到具体实现（如 TypeScript 函数、远程调用等）。
 */
export interface SkillInvoker {
  /**
   * 调用技能
   * 
   * @param intent 要调用的意图
   * @param context 执行上下文
   * @returns 调用结果
   */
  invoke(intent: IntentObject, context: ExecutionContext): Promise<SkillResult>;
}

/** 能力超出范围异常 */
export class CapabilityException extends Error {
  constructor(message = "Capability exceeded") {
    super(message);
    this.name = "CapabilityException";
  }
}

/** 意图格式异常 */
export class IntentFormatException extends Error {
  constructor(message = "Intent format invalid") {
    super(message);
    this.name = "IntentFormatException";
  }
}

/** Schema 验证异常 */
export class SchemaValidationException extends Error {
  constructor(message = "Schema validation failed") {
    super(message);
    this.name = "SchemaValidationException";
  }
}

/** 权限不足异常 */
export class PermissionDeniedException extends Error {
  constructor(message = "Permission denied") {
    super(message);
    this.name = "PermissionDeniedException";
  }
}

/**
 * 全拒绝执行器 - 对齐 core RejectAllExecutor.java
 * 
 * 当治理关闭时，拒绝所有执行请求。
 */
export class RejectAllExecutor implements Executor {
  private _auditStore?: { append(action: string, payload: Record<string, unknown>): string };

  constructor(auditStore?: { append(action: string, payload: Record<string, unknown>): string }) {
    this._auditStore = auditStore;
  }

  execute(intent: IntentObject, context: ExecutionContext): SkillResult {
    const errorMsg = "Governance is disabled, all executions are rejected";
    let auditId: string | undefined;

    if (this._auditStore) {
      auditId = this._auditStore.append("SKILL_REJECTED", {
        tool: intent.tool,
        reason: "GOVERNANCE_DISABLED",
        mode: context.mode,
      });
    }

    return Object.freeze({
      success: false,
      errorCode: "INTERNAL_ERROR",
      errorMessage: errorMsg,
      auditId,
    });
  }
}

/**
 * TypeScript 函数执行器
 * 
 * 将 IntentObject 路由到注册的 TypeScript 函数。
 */
export class TypeScriptFunctionExecutor implements Executor, SkillInvoker {
  private _functions: Map<string, (args: Record<string, unknown>, ctx: ExecutionContext) => unknown | Promise<unknown>> =
    new Map();
  private _descriptors: Map<string, SkillDescriptor> = new Map();

  register(
    name: string,
    func: (args: Record<string, unknown>, ctx: ExecutionContext) => unknown | Promise<unknown>,
    descriptor?: SkillDescriptor,
  ): void {
    this._functions.set(name, func);
    if (descriptor) {
      this._descriptors.set(name, descriptor);
    }
  }

  unregister(name: string): void {
    this._functions.delete(name);
    this._descriptors.delete(name);
  }

  getDescriptor(name: string): SkillDescriptor | undefined {
    return this._descriptors.get(name);
  }

  listSkills(): string[] {
    return Array.from(this._functions.keys());
  }

  execute(intent: IntentObject, context: ExecutionContext): SkillResult {
    const func = this._functions.get(intent.tool);
    if (!func) {
      return Object.freeze({
        success: false,
        errorCode: ErrorCodes.CAPABILITY_EXCEEDED,
        errorMessage: `Unknown tool: ${intent.tool}`,
      });
    }

    try {
      // 调用函数，传入意图参数和执行上下文
      const result = func(intent.args, context);
      // 处理同步结果
      if (!(result instanceof Promise)) {
        return Object.freeze({ success: true, output: result });
      }
      // 对于异步结果，返回一个特殊的失败标记（实际应由客户端处理）
      // 这里为了保持接口一致性，返回一个待处理的错误
      return Object.freeze({
        success: false,
        errorCode: "ASYNC_PENDING",
        errorMessage: "Async execution not supported in synchronous executor",
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      return Object.freeze({
        success: false,
        errorCode: "INTERNAL_ERROR",
        errorMessage: msg,
      });
    }
  }

  /**
   * 异步技能调用 - 实现 SkillInvoker 协议
   *
   * 与同步 execute 不同，invoke 会 await 处理函数返回的 Promise，
   * 供客户端统一执行链路使用。
   */
  async invoke(intent: IntentObject, context: ExecutionContext): Promise<SkillResult> {
    const func = this._functions.get(intent.tool);
    if (!func) {
      return Object.freeze({
        success: false,
        errorCode: ErrorCodes.CAPABILITY_EXCEEDED,
        errorMessage: `Unknown tool: ${intent.tool}`,
      });
    }

    try {
      const output = await func(intent.args, context);
      return Object.freeze({ success: true, output });
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      return Object.freeze({
        success: false,
        errorCode: "INTERNAL_ERROR",
        errorMessage: msg,
      });
    }
  }
}

/**
 * 技能注册表 - 对齐 core SkillRegistry.java
 * 
 * 管理所有已注册的技能描述符。
 */
export class SkillRegistry {
  private _skills: Map<string, SkillDescriptor> = new Map();

  register(descriptor: SkillDescriptor): void {
    this._skills.set(descriptor.name, descriptor);
  }

  unregister(name: string): void {
    this._skills.delete(name);
  }

  get(name: string): SkillDescriptor | undefined {
    return this._skills.get(name);
  }

  names(): string[] {
    return Array.from(this._skills.keys());
  }

  all(): SkillDescriptor[] {
    return Array.from(this._skills.values());
  }

  contains(name: string): boolean {
    return this._skills.has(name);
  }
}
