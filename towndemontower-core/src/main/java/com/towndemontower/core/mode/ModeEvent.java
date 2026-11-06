package com.towndemontower.core.mode;

/**
 * [core] 模式转换事件。模式切换只能由状态机事件触发，Agent 不得自行切。
 */
public enum ModeEvent {
    PLAN_APPROVED, // PlanArtifact 审核通过：PLANNING -> EXECUTION
    ENTER_AUDIT,   // 进入审计态：EXECUTION -> AUDIT
    REPLAN         // 重新规划：AUDIT -> PLANNING（跳过则非法）
}
