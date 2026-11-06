package com.towndemontower.core.approval;

import com.towndemontower.core.contract.ApprovalTicket;
import com.towndemontower.core.contract.ExecutionContext;

/**
 * [core] 人类审批策略（基线裁决）：
 *  - R3：REFER_MANUAL_TERMINAL（永远不 APPROVE，即使带 --force）
 *  - R2：ESCALATE_TO_HUMAN（置信度 100% 也不自动放行）
 *  - R0/R1：APPROVE（轻量确认/短 TTL，由上层决定）
 * 禁自批、超时默认拒绝由上层保证。
 */
public class HumanApprovalPolicy implements ApprovalPolicy {
    @Override
    public ApprovalDecision decide(ApprovalTicket ticket, ExecutionContext ctx) {
        switch (ticket.riskLevel) {
            case "R3":
                return ApprovalDecision.REFER_MANUAL_TERMINAL;
            case "R2":
                return ApprovalDecision.ESCALATE_TO_HUMAN;
            case "R1":
            case "R0":
            default:
                return ApprovalDecision.APPROVE;
        }
    }
}
