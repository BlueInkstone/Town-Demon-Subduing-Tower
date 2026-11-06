package com.towndemontower.core.approval;

import com.towndemontower.core.contract.ApprovalTicket;
import com.towndemontower.core.contract.ExecutionContext;

/**
 * [core] 审批决策者抽象（机制写死：R3 默认拒绝、R2 人工；实现可配）。
 * 用户/IM/CLI、Guardian 预审、全部拒绝、转人工终端，均实现本接口。
 */
public interface ApprovalPolicy {
    ApprovalDecision decide(ApprovalTicket ticket, ExecutionContext ctx);
}
