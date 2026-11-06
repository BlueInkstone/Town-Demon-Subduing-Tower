package com.towndemontower.core.routing;

import com.towndemontower.core.contract.RiskLevel;
import com.towndemontower.core.mode.AgentMode;

/**
 * [core] 默认模型路由：规划/审计态用强模型，执行态用快模型，格式修复用小模型。
 * 路由变更应审计；模型切换导致的缓存未命中标记 planned_cache_miss。
 */
public class SimpleModelRouter implements ModelRouter {
    @Override
    public ModelProfile route(AgentMode mode, RiskLevel risk, String taskType) {
        if ("format-repair".equals(taskType)) {
            return new ModelProfile("mini-lm", "local", "small", "low");
        }
        if (mode == AgentMode.ARCHITECT_PLANNING || mode == AgentMode.AUDIT_REVIEWING) {
            return new ModelProfile("strong-llm", "cloud", "strong", "high");
        }
        // 执行态：默认快模型；但若涉及 R2/R3 高风险，仍用强模型保证判断质量
        if (risk == RiskLevel.R2 || risk == RiskLevel.R3) {
            return new ModelProfile("strong-llm", "cloud", "strong", "high");
        }
        return new ModelProfile("fast-llm", "cloud", "fast", "mid");
    }
}
