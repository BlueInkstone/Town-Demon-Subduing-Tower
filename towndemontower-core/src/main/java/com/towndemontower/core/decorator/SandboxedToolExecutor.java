package com.towndemontower.core.decorator;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.executor.SkillInvoker;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.metrics.GovernanceMetrics;

/**
 * [core] 装饰器链最内层：在沙箱中真正调用工具（一次性子进程/容器，run_in_background 不在本 demo）。
 * 实际环境由 starter 装配 Docker/进程实现；此处用 SkillInvoker 产出结果。
 *
 * <p><b>V1.11 / D2</b>：本类是<b>唯一发牌点</b>。只有走到这里（说明前九个环节全部放行）才会
 * 调用 {@link GovernancePass#issue}，并在 {@code finally} 里收回。这带来两个天然的性质：
 * 被拒/待审批/取消的调用根本到不了这里，不会发牌；而发出去的牌一定会在同一次栈帧内收回，
 * 不会留给同线程的下一次调用（{@code delivery} / {@code agents} 复用同一条链但目标方法
 * 没有 {@code require}，清理不干净会直接导致后续 require 误通过）。</p>
 */
public class SandboxedToolExecutor implements Executor {
    private final SkillInvoker invoker;
    private final SkillRegistry registry;
    /** 本链的审计端（可为 null，此时回落到 GovernancePass 的全局默认）。 */
    private final AuditStore audit;
    /** 本链的指标端（可为 null）。 */
    private final GovernanceMetrics metrics;

    public SandboxedToolExecutor(SkillInvoker invoker, SkillRegistry registry) {
        this(invoker, registry, null, null);
    }

    /**
     * @param audit   本次链装配的审计端，可 null
     * @param metrics 本次链装配的指标端，可 null
     */
    public SandboxedToolExecutor(SkillInvoker invoker, SkillRegistry registry,
                                 AuditStore audit, GovernanceMetrics metrics) {
        this.invoker = invoker;
        this.registry = registry;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        if (ctx.cancellationToken != null && ctx.cancellationToken.isCancelled()) {
            return SkillResult.denied(ErrorCodes.CANCELLED, "tool cancelled before execution: " + intent.tool);
        }
        SkillDescriptor d = registry.get(intent.tool);
        // 发牌 → 真实执行（→ 方法体首行 require 收牌）→ finally 兜底收牌。
        GovernancePass.issue(intent.tool, audit, metrics);
        try {
            Object out = invoker.invoke(d, intent);
            return SkillResult.success(out);
        } finally {
            GovernancePass.clear(intent.tool);
        }
    }
}
