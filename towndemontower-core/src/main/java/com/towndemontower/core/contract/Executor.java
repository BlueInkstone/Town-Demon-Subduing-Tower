package com.towndemontower.core.contract;

/**
 * [core] Executor 统一入口。所有工具调用必经此接口（机制写死）。
 * 具体实现由装饰器链组合（ModeToolPolicy -> ... -> Audit）。
 */
public interface Executor {
    SkillResult execute(IntentObject intent, ExecutionContext ctx);
}
