package com.towndemontower.core.executor;

import com.towndemontower.core.approval.ApprovalCenter;
import com.towndemontower.core.approval.ApprovalPolicy;
import com.towndemontower.core.approval.PlanApprovalService;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.context.PipelineStateStore;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.decorator.*;
import com.towndemontower.core.metrics.GovernanceMetrics;
import com.towndemontower.core.mode.ModeToolPolicy;
import com.towndemontower.core.policy.RiskGate;

/**
 * [core] 装饰器链装配工厂。链顺序（外→内，<b>固定，不因任何配置而变</b>）：
 * Audit → ModeToolPolicy → IntentFormat → Schema → Capability
 *      → Permission → RiskGate → TaintCheck → Snapshot → SandboxedTool
 * 任一环节可单独替换/插桩，core 不依赖 Spring。
 *
 * <p>V1.5 增加一个重载：把审批中心、指标、流水线状态三个<b>旁路设施</b>传进去。
 * 它们不是新的链环节，而是挂在既有环节上（{@code RiskGateDecorator} / {@code AuditDecorator}），
 * 因此链的层数与顺序保持 10 层不变——否则每加一个观测能力就要改一次链拓扑，
 * "机制写死"这句话就守不住了。</p>
 */
public final class ExecutorFactory {

    private ExecutorFactory() {
    }

    /** 兼容入口：不接旁路设施（旧调用方行为不变）。 */
    public static Executor buildDefault(SkillRegistry registry,
                                        ModeToolPolicy modePolicy,
                                        RiskGate riskGate,
                                        PermissionEngine permissionEngine,
                                        SnapshotService snapshotService,
                                        SkillInvoker invoker,
                                        AuditStore audit,
                                        ApprovalPolicy approvalPolicy) {
        return buildDefault(registry, modePolicy, riskGate, permissionEngine, snapshotService,
                invoker, audit, approvalPolicy, null, null, null);
    }

    /**
     * 完整装配。
     *
     * @param approvalCenter 审批中心，可 null（null 时行为等于旧版：单号内存自增、无超时）
     * @param metrics        指标计数器，可 null
     * @param stateStore     流水线状态存储，可 null（null 时不落状态）
     */
    public static Executor buildDefault(SkillRegistry registry,
                                        ModeToolPolicy modePolicy,
                                        RiskGate riskGate,
                                        PermissionEngine permissionEngine,
                                        SnapshotService snapshotService,
                                        SkillInvoker invoker,
                                        AuditStore audit,
                                        ApprovalPolicy approvalPolicy,
                                        ApprovalCenter approvalCenter,
                                        GovernanceMetrics metrics,
                                        PipelineStateStore stateStore) {
        return buildDefault(registry, modePolicy, riskGate, permissionEngine, snapshotService,
                invoker, audit, approvalPolicy, approvalCenter, metrics, stateStore, null);
    }

    /**
     * 完整装配（V1.13：增加计划审批服务）。
     *
     * <p>计划审批与审批中心一样，是<b>挂在既有环节上的旁路设施</b>，不新增链路层——
     * 链仍然是 10 层。理由见类注释：每加一个治理能力就改一次拓扑，"机制写死"这句话就守不住了。</p>
     *
     * @param planApprovals 计划审批服务，可 null；为 null 时声明了
     *                      {@code requiresPlanApproval} 的工具会被<b>拒绝</b>（fail-closed）
     */
    public static Executor buildDefault(SkillRegistry registry,
                                        ModeToolPolicy modePolicy,
                                        RiskGate riskGate,
                                        PermissionEngine permissionEngine,
                                        SnapshotService snapshotService,
                                        SkillInvoker invoker,
                                        AuditStore audit,
                                        ApprovalPolicy approvalPolicy,
                                        ApprovalCenter approvalCenter,
                                        GovernanceMetrics metrics,
                                        PipelineStateStore stateStore,
                                        PlanApprovalService planApprovals) {
        // V1.11 / D2：链尾是唯一发牌点，令牌帧带上本次装配的审计/指标端，
        // 使"违规记到哪条链的账上"不取决于装配顺序。configure 只服务于手工装配的兜底路径。
        GovernancePass.configure(audit, metrics);
        Executor e = new SandboxedToolExecutor(invoker, registry, audit, metrics);
        e = new SnapshotDecorator(e, snapshotService, registry);
        e = new TaintCheckDecorator(e, registry);
        e = new RiskGateDecorator(e, riskGate, approvalPolicy, registry, approvalCenter, planApprovals);
        e = new PermissionDecorator(e, permissionEngine, registry);
        e = new CapabilityDecorator(e, registry);
        e = new SchemaDecorator(e, registry);
        e = new IntentFormatDecorator(e);
        e = new ModeToolPolicyDecorator(e, modePolicy);
        e = new AuditDecorator(e, audit, metrics, stateStore);
        return e;
    }

    /**
     * 网关"只裁决不执行"链（docs/38 决策①）。
     *
     * <p>复用前几层裁决（Audit → ModeToolPolicy → IntentFormat → Schema → Capability
     * → Permission → RiskGate → TaintCheck），但<b>跳过 SandboxedToolExecutor 执行层与
     * SnapshotDecorator 快照层</b>：网关不执行、没有业务态可回滚，这两层在此无意义。
     * 终点用 {@link VerdictTerminalExecutor} 返回"允许"。</p>
     *
     * <p>本方法<b>不调用</b> {@link com.towndemontower.core.decorator.GovernancePass#configure}
     * 也不经过发牌点——令牌只在 SandboxedToolExecutor 发出，
     * 跳过它即不会误发 GovernancePass 令牌、不会误写快照。</p>
     *
     * @param approvalCenter 审批中心，可 null（null 时 R2 走内存自增单号、无超时）
     */
    public static Executor buildVerdictOnly(SkillRegistry registry,
                                            ModeToolPolicy modePolicy,
                                            RiskGate riskGate,
                                            PermissionEngine permissionEngine,
                                            ApprovalPolicy approvalPolicy,
                                            ApprovalCenter approvalCenter,
                                            AuditStore audit) {
        Executor e = new VerdictTerminalExecutor();
        e = new TaintCheckDecorator(e, registry);
        e = new RiskGateDecorator(e, riskGate, approvalPolicy, registry, approvalCenter, null);
        e = new PermissionDecorator(e, permissionEngine, registry);
        e = new CapabilityDecorator(e, registry);
        e = new SchemaDecorator(e, registry);
        e = new IntentFormatDecorator(e);
        e = new ModeToolPolicyDecorator(e, modePolicy);
        e = new AuditDecorator(e, audit);
        return e;
    }
}
