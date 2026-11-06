package com.towndemontower.core.approval;

import com.towndemontower.core.contract.ApprovalTicket;

/**
 * [core] Guardian 预审实现示例：只给出风险提示，绝不做最终批准。
 * 强调：R2/R3 的最终决定权永远在人/转人工终端，Guardian 不可触碰。
 */
public class GuardianPrecheckImpl implements GuardianPrecheck {
    @Override
    public GuardianOpinion precheck(ApprovalTicket ticket) {
        boolean risky = "R2".equals(ticket.riskLevel) || "R3".equals(ticket.riskLevel);
        String advice = risky
                ? "高危操作，需人工终批；Guardian 不代批"
                : "低风险提示，可按策略放行";
        return new GuardianOpinion("Guardian 预审：" + ticket.tool, risky, advice);
    }
}
