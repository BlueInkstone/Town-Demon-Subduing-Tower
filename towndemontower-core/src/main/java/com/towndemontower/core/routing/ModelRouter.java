package com.towndemontower.core.routing;

import com.towndemontower.core.contract.RiskLevel;
import com.towndemontower.core.mode.AgentMode;

/**
 * [core] 模型路由接口（快慢分离）。按模式/风险/任务类型选模型：
 * 规划态→强模型，执行态→快模型，格式修复→小模型，Guardian→独立模型。
 * 不能因用便宜模型降低 R2/R3 闸门。
 */
public interface ModelRouter {
    ModelProfile route(AgentMode mode, RiskLevel risk, String taskType);
}
