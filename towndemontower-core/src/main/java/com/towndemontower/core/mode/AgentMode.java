package com.towndemontower.core.mode;

/**
 * [core] Agent 执行视图枚举（语言中立，对应字符串）。
 * 规划态看全局；执行态埋头写；审计态只读扫描。
 */
public enum AgentMode {
    ARCHITECT_PLANNING, // 架构规划态
    DEV_EXECUTION,      // 局部执行态
    AUDIT_REVIEWING     // 审计审查态
}
