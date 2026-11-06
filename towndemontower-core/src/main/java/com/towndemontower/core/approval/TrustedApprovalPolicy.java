package com.towndemontower.core.approval;

/**
 * [core] 受信审批策略标记。
 *
 * <p>默认情况下，任何 {@link ApprovalPolicy} 对 R2 返回 {@code APPROVE} 都会被机制<b>改判为转人工</b>。
 * 原因：R2 的语义是"必须人工审批"，如果允许策略直接批准，那么"(可替换的)策略"就又成了绕过点——
 * 换一个恒 APPROVE 的 Bean 就等于把 R2 降级成 R0。</p>
 *
 * <p>确需由企业已有审批网关（OA / IM 审批流）自动裁决时，让该实现显式标记本接口，
 * 表示"它能对 R2 作出有据可查的批准"，并在启动期被 {@code GovernanceIntegrityChecker} 记为
 * {@code POLICY_SUBSTITUTED}。</p>
 */
public interface TrustedApprovalPolicy extends ApprovalPolicy {
}
