package com.towndemontower.core.decorator;

import com.towndemontower.core.approval.ApprovalCenter;
import com.towndemontower.core.approval.ApprovalDecision;
import com.towndemontower.core.approval.ApprovalPolicy;
import com.towndemontower.core.approval.ApprovalStatus;
import com.towndemontower.core.approval.PlanApproval;
import com.towndemontower.core.approval.PlanApprovalService;
import com.towndemontower.core.approval.TrustedApprovalPolicy;
import com.towndemontower.core.contract.ApprovalTicket;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.RiskLevel;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.policy.CommandTemplate;
import com.towndemontower.core.policy.RiskGate;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [core] 装饰器⑥：风险闸门（R0-R3）。R3 直接拒绝（含 --force）；R2 挂审批网关。
 * 状态机允许不代表动作允许；此处是动作本身的硬性裁决。
 *
 * <p><b>V1.5 加固</b>：
 * <ol>
 *   <li><b>R2 的最终批准权在机制层被钉住</b>：非 {@link TrustedApprovalPolicy} 的策略对 R2 返回
 *       APPROVE 时，一律改判转人工。否则"换一个恒 APPROVE 的 Bean"就能把 R2 降级为 R0。</li>
 *   <li><b>审批单进中心</b>：给出 {@link ApprovalCenter} 时，单号来自中心（可查询、可持久化、会超时），
 *       不再是内存自增的 {@code T1/T2}。</li>
 *   <li><b>超时即拒</b>：每次执行前顺手清扫过期单；已过期单不会被"补批"。</li>
 *   <li>R3 的拒绝文案补上"转人工终端"指引（规格要求），而不是一句 blocked 了事。</li>
 * </ol>
 *
 * <p><b>V1.12 加固（I5）</b>：工具可用 {@code SkillDescriptor.commandTemplate} 声明"只允许这种形状的命令"。
 * 声明后本层在分级之前先做形状校验，不符直接 {@code COMMAND_TEMPLATE_MISMATCH}；
 * 未声明时保持 v1.11 行为不变。另外补上"工具未注册"的显式拒绝，
 * 把原先的 NPE 变成带错误码的拒绝。
 */
public class RiskGateDecorator implements Executor {

    private final Executor next;
    private final RiskGate riskGate;
    private final ApprovalPolicy approvalPolicy;
    private final SkillRegistry registry;
    private final ApprovalCenter approvalCenter;
    /** 计划审批服务（V1.13），可为 null（此时声明了计划要求的工具一律拒绝）。 */
    private final PlanApprovalService planApprovals;
    private final AtomicLong ticketSeq = new AtomicLong(0);

    public RiskGateDecorator(Executor next, RiskGate riskGate, ApprovalPolicy approvalPolicy, SkillRegistry registry) {
        this(next, riskGate, approvalPolicy, registry, null);
    }

    public RiskGateDecorator(Executor next, RiskGate riskGate, ApprovalPolicy approvalPolicy,
                             SkillRegistry registry, ApprovalCenter approvalCenter) {
        this(next, riskGate, approvalPolicy, registry, approvalCenter, null);
    }

    /**
     * @param planApprovals 计划审批服务（V1.13），可 null——但若某个工具声明了
     *                      {@code requiresPlanApproval} 而这里为 null，会<b>拒绝</b>执行（fail-closed），
     *                      而不是默默放行
     */
    public RiskGateDecorator(Executor next, RiskGate riskGate, ApprovalPolicy approvalPolicy,
                             SkillRegistry registry, ApprovalCenter approvalCenter,
                             PlanApprovalService planApprovals) {
        this.next = next;
        this.riskGate = riskGate;
        this.approvalPolicy = approvalPolicy;
        this.registry = registry;
        this.approvalCenter = approvalCenter;
        this.planApprovals = planApprovals;
    }

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        SkillDescriptor d = registry.get(intent.tool);
        if (d == null) {
            // 未注册的工具没有任何元数据可裁决。以前这里会 NPE（表现为 500），
            // 现在按"意图不合法"拒绝：错误可读、有错误码、可被审计统计。
            return SkillResult.denied(ErrorCodes.INTENT_FORMAT_INVALID,
                    "tool not registered: " + intent.tool);
        }
        // V1.13 / Plan-and-Execute：这个任务有没有被批准过的计划？
        SkillResult planDenied = verifyPlanApproval(d, ctx);
        if (planDenied != null) {
            return planDenied;
        }
        // V1.12 / I5：先看命令形状是否符合声明，再谈它有多危险。
        SkillResult templateDenied = verifyCommandTemplate(d, intent);
        if (templateDenied != null) {
            return templateDenied;
        }
        RiskLevel r = riskGate.classify(d, intent);

        if (approvalCenter != null) {
            approvalCenter.expireOverdue(Instant.now());
        }

        if (r == RiskLevel.R3) {
            return SkillResult.denied(ErrorCodes.R3_DENIED,
                    "irreversible action blocked: " + intent.tool
                            + "；该操作不可逆，不提供一键执行，请转人工终端处置");
        }

        if (r == RiskLevel.R2 || d.requiresApproval()) {
            String ticketId = nextTicketId();
            ApprovalTicket ticket = new ApprovalTicket(ticketId, ctx.taskId, intent.tool,
                    r.code(), intent.expectedEffect, intent.rollbackHint);
            if (approvalCenter != null) {
                approvalCenter.create(ticket, ctx.agentId, Instant.now());
            }
            ApprovalDecision dec = approvalPolicy.decide(ticket, ctx);

            // 机制兜底：R2 的批准权不接受"普通策略"的自动批准
            if (dec == ApprovalDecision.APPROVE && r == RiskLevel.R2
                    && !(approvalPolicy instanceof TrustedApprovalPolicy)) {
                return SkillResult.pendingApproval(ticketId);
            }
            if (dec == ApprovalDecision.ESCALATE_TO_HUMAN
                    || dec == ApprovalDecision.REFER_MANUAL_TERMINAL) {
                return SkillResult.pendingApproval(ticketId);
            }
            if (dec == ApprovalDecision.DENY) {
                if (approvalCenter != null) {
                    approvalCenter.deny(ticketId, "approval-policy", "policy denied", Instant.now());
                }
                return SkillResult.denied("APPROVAL_DENIED", "approver denied");
            }
            // APPROVE：受信策略（或 R0/R1 走到这里）→ 记账后放行
            if (approvalCenter != null
                    && approvalCenter.statusOf(ticketId) == ApprovalStatus.PENDING) {
                approvalCenter.approve(ticketId,
                        "approval-policy:" + approvalPolicy.getClass().getSimpleName(), "auto-approved", Instant.now());
            }
        }
        return next.execute(intent, ctx);
    }

    /**
     * I5：命令模板前置校验（V1.12）。
     *
     * <p>为什么放在风险分级<b>之前</b>：形状不符时"这条命令有多危险"根本问不出可靠答案
     * （黑名单永远列不完变体）；更糟的是把形状非法的 R2 命令送进审批流，等于把判断推给人。
     * 先卡形状，再判风险，顺序不能反。</p>
     *
     * <p>未声明模板的工具（存量全部）走原路径，行为与 v1.11 完全一致。</p>
     *
     * @return 需要拒绝时返回拒绝结果；校验通过返回 {@code null}
     */
    private SkillResult verifyCommandTemplate(SkillDescriptor d, IntentObject intent) {
        if (d == null || !d.hasCommandTemplate()) return null;
        CommandTemplate template;
        try {
            template = CommandTemplate.of(d.commandTemplate);
        } catch (IllegalArgumentException ex) {
            // 模板是代码里写死的，非法即开发期错误；运行期按拒绝处理，不静默放行。
            return SkillResult.denied(ErrorCodes.COMMAND_TEMPLATE_INVALID,
                    "工具 " + d.name + " 的命令模板不合法：" + ex.getMessage());
        }
        CommandTemplate.Match m = template.match(CommandTemplate.commandOf(intent));
        if (m.matched) return null;
        return SkillResult.denied(ErrorCodes.COMMAND_TEMPLATE_MISMATCH,
                "命令与工具 " + d.name + " 声明的模板 `" + template.template() + "` 不符：" + m.reason);
    }

    /**
     * V1.13：计划审批前置校验。
     *
     * <p>放在最前面（比命令模板还早）：一个"方案都没批"的任务，讨论"这条命令形状对不对"
     * 没有意义——顺序本身就是治理语义的一部分。</p>
     *
     * <p>未声明 {@code requiresPlanApproval} 的工具直接放行；声明了但没装配服务时<b>拒绝</b>，
     * 因为"忘了接线"不应该等价于"不需要审批"。</p>
     */
    private SkillResult verifyPlanApproval(SkillDescriptor d, ExecutionContext ctx) {
        if (d == null || !d.requiresPlanApproval) return null;
        if (planApprovals == null) {
            return SkillResult.denied(ErrorCodes.PLAN_APPROVAL_REQUIRED,
                    "工具 " + d.name + " 声明需要计划审批，但未装配 PlanApprovalService（拒绝而非放行）");
        }
        PlanApproval latest = planApprovals.latest(ctx.agentId, ctx.taskId);
        if (latest != null && latest.isApproved()) {
            if (latest.coversTool(d.name)) return null;
            // V1.14：计划已批准，但这一步不在计划范围内。按"未批准"处理——批准的是方案，
            // 不是"这个任务里 agent 想调的任何工具"。
            return SkillResult.denied(ErrorCodes.PLAN_APPROVAL_REQUIRED,
                    "工具 " + d.name + " 不在已批准计划 " + latest.id + " 的范围内；"
                            + "该计划覆盖：" + latest.plan.describeTools()
                            + "。如需执行，请调用 PlanApprovalService.revise(...) 提交包含该工具的新计划并重新审批");
        }

        StringBuilder msg = new StringBuilder("工具 ").append(d.name)
                .append(" 需要「执行计划已批准」后才能执行");
        if (latest == null) {
            msg.append("：当前任务尚未提交计划，请先调用 PlanApprovalService.request(...)");
        } else {
            msg.append("：当前计划 ").append(latest.id).append(" 状态=").append(latest.status)
                    .append("（审批人必须是提交者之外的角色）");
        }
        return SkillResult.denied(ErrorCodes.PLAN_APPROVAL_REQUIRED, msg.toString());
    }

    private String nextTicketId() {
        if (approvalCenter == null) return "T" + ticketSeq.incrementAndGet();
        return "T" + System.currentTimeMillis() + "-" + ticketSeq.incrementAndGet();
    }
}
