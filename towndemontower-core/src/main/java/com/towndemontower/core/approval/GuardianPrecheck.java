package com.towndemontower.core.approval;

import com.towndemontower.core.contract.ApprovalTicket;

/**
 * [core] Guardian（安检小模型）预审接口。只做粗筛/解释/排序/风险提示，不能终批。
 * 输出标不可信；不能批准 R2、不能放行 R3、不能自批。
 */
public interface GuardianPrecheck {
    GuardianOpinion precheck(ApprovalTicket ticket);
}
