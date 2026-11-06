package com.towndemontower.core.approval;

/**
 * [core] 执行计划的审批状态（V1.13 / Plan-and-Execute）。
 *
 * <p>与 {@link ApprovalStatus}（单个 R2 动作的审批）刻意分开：动作审批问的是
 * "这一下能不能做"，计划审批问的是"这条任务整体打算怎么做、准不准这么做"。
 * 把两者混成一个状态机，就再也说不清"这一步被拒"和"方案被否"的区别。</p>
 */
public enum PlanStatus {
    /** 已提交，等待审批。此状态下受计划约束的工具一律拒绝执行。 */
    PENDING,
    /** 已批准：同一 agent + task 下，声明 requiresPlanApproval 的工具可执行。 */
    APPROVED,
    /** 已否决：仍然拒绝执行，需重新提交计划（不会被"补批"）。 */
    REJECTED
}
