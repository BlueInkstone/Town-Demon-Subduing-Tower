/**
 * 装饰器模块 - 对齐 core/decorator/ 模块
 *
 * GovernancePass：一次性治理令牌，用于绕过治理链的场景
 */

/** 治理通行证 */
export interface GovernancePassToken {
  readonly auditId: string;
  readonly tool: string;
  readonly issuedAt: string;
  consumed: boolean;
}

/** 治理指标 */
export interface GovernanceMetricsSnapshot {
  readonly total_invocations: number;
  readonly r0_pass: number;
  readonly r1_pass: number;
  readonly r2_pending: number;
  readonly r2_approved: number;
  readonly r3_rejected: number;
  readonly permission_denied: number;
  readonly ungoverned_invocations: number;
}

/** 未治理调用异常 */
export class UngovernedInvocationError extends Error {
  constructor(tool: string) {
    super(`Ungoverned invocation detected for tool: ${tool}`);
    this.name = "UngovernedInvocationError";
  }
}

/** 治理通行证管理器（进程内单例） */
export class GovernancePass {
  private static _MAX_STACK_DEPTH = 128;
  private static _stack: GovernancePassToken[] = [];

  /** 发放通行证并压栈 */
  static issue(auditId: string, tool: string): GovernancePassToken {
    if (GovernancePass._stack.length >= GovernancePass._MAX_STACK_DEPTH) {
      throw new Error("GovernancePass stack overflow");
    }
    const token: GovernancePassToken = {
      auditId,
      tool,
      issuedAt: new Date().toISOString(),
      consumed: false,
    };
    GovernancePass._stack.push(token);
    return token;
  }

  /** 获取栈顶（不清除） */
  static current(): GovernancePassToken | null {
    if (GovernancePass._stack.length === 0) return null;
    return GovernancePass._stack[GovernancePass._stack.length - 1];
  }

  /** 要求指定工具存在有效通行证 */
  static require(tool: string): GovernancePassToken {
    const token = GovernancePass.current();
    if (!token || token.consumed || token.tool !== tool) {
      throw new UngovernedInvocationError(tool);
    }
    return token;
  }

  /** 清除栈顶 */
  static clear(): GovernancePassToken | undefined {
    return GovernancePass._stack.pop();
  }

  /** 消费通行证（一次性） */
  static consume(token: GovernancePassToken): boolean {
    if (token.consumed) return false;
    token.consumed = true;
    return true;
  }

  /** 作用域执行器 */
  static withScope<T>(
    auditId: string,
    tool: string,
    fn: (token: GovernancePassToken) => T,
  ): T {
    const token = GovernancePass.issue(auditId, tool);
    try {
      return fn(token);
    } finally {
      GovernancePass.clear();
    }
  }
}

/** 治理指标收集器 - 对齐 core GovernanceMetrics.java */
export class MetricsCollector {
  private _totalInvocations = 0;
  private _r0Pass = 0;
  private _r1Pass = 0;
  private _r2Pending = 0;
  private _r2Approved = 0;
  private _r3Rejected = 0;
  private _permissionDenied = 0;
  private _ungovernedInvocations = 0;

  recordInvocation(): void { this._totalInvocations++; }
  recordR0Pass(): void { this._r0Pass++; }
  recordR1Pass(): void { this._r1Pass++; }
  recordR2Pending(): void { this._r2Pending++; }
  recordR2Approved(): void { this._r2Approved++; }
  recordR3Rejected(): void { this._r3Rejected++; }
  recordPermissionDenied(): void { this._permissionDenied++; }
  recordUngoverned(): void { this._ungovernedInvocations++; }

  snapshot(): GovernanceMetricsSnapshot {
    return Object.freeze({
      total_invocations: this._totalInvocations,
      r0_pass: this._r0Pass,
      r1_pass: this._r1Pass,
      r2_pending: this._r2Pending,
      r2_approved: this._r2Approved,
      r3_rejected: this._r3Rejected,
      permission_denied: this._permissionDenied,
      ungoverned_invocations: this._ungovernedInvocations,
    });
  }
}
