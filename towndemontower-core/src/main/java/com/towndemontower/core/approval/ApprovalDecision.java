package com.towndemontower.core.approval;

/**
 * [core] 审批决策（字符串枚举，跨语言一致）。
 * R3 永不 APPROVE，只 REFER_MANUAL_TERMINAL；R2 必须人工 ESCALATE_TO_HUMAN。
 */
public enum ApprovalDecision {
    APPROVE,
    DENY,
    ESCALATE_TO_HUMAN,        // R2：挂人工审批
    REFER_MANUAL_TERMINAL     // R3：转人工终端，不提供一键执行
}
