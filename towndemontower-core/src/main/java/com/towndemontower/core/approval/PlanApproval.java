package com.towndemontower.core.approval;

import java.time.Instant;

/**
 * [core] 一次执行计划审批记录（V1.13）。
 *
 * <p>不可变对象：状态流转通过 {@link #approved}/{@link #rejected} 产生新实例，
 * 存储层替换引用。这样"谁在什么时候改了什么"不会被后续修改覆盖——
 * 审计要的是当时的快照，不是最终态。</p>
 */
public final class PlanApproval {

    /** 计划单号，形如 {@code P1720000000000-1}。 */
    public final String id;
    /** 提交计划的 agent（同时也是调用者）。 */
    public final String agentId;
    /** 任务号：审批只对"这个 agent 的这个任务"生效。 */
    public final String taskId;
    /** 结构化计划（V1.14）；{@link #summary} 是它的摘要。 */
    public final PlanArtifact plan;
    /** 计划摘要（人读；不含执行细节，那是任务清单的事）。 */
    public final String summary;
    public final PlanStatus status;
    public final Instant requestedAt;
    /** 决策时间（PENDING 时为 null）。 */
    public final Instant decidedAt;
    /** 审批人（PENDING 时为 null）。 */
    public final String decidedBy;
    /** 审批意见 / 否决理由。 */
    public final String note;

    /** v1.13 兼容构造：只有摘要，视为不限定工具范围的旧计划。 */
    public PlanApproval(String id, String agentId, String taskId, String summary,
                        PlanStatus status, Instant requestedAt,
                        Instant decidedAt, String decidedBy, String note) {
        this(id, agentId, taskId, PlanArtifact.text(summary), status,
                requestedAt, decidedAt, decidedBy, note);
    }

    /** V1.14：带结构化计划的构造。 */
    public PlanApproval(String id, String agentId, String taskId, PlanArtifact plan,
                        PlanStatus status, Instant requestedAt,
                        Instant decidedAt, String decidedBy, String note) {
        this.id = id;
        this.agentId = agentId;
        this.taskId = taskId;
        this.plan = plan == null ? PlanArtifact.text("") : plan;
        this.summary = this.plan.summary;
        this.status = status;
        this.requestedAt = requestedAt;
        this.decidedAt = decidedAt;
        this.decidedBy = decidedBy;
        this.note = note;
    }

    public PlanApproval approved(String approver, String note, Instant at) {
        return new PlanApproval(id, agentId, taskId, plan, PlanStatus.APPROVED,
                requestedAt, at, approver, note);
    }

    public PlanApproval rejected(String approver, String note, Instant at) {
        return new PlanApproval(id, agentId, taskId, plan, PlanStatus.REJECTED,
                requestedAt, at, approver, note);
    }

    public boolean isApproved() {
        return status == PlanStatus.APPROVED;
    }

    /** V1.14：已批准计划是否覆盖某工具（未批准时只看计划内容，调用方应先判状态）。 */
    public boolean coversTool(String toolName) {
        return plan.coversTool(toolName);
    }

    @Override
    public String toString() {
        return "PlanApproval{" + id + " agent=" + agentId + " task=" + taskId
                + " status=" + status + "}";
    }
}
