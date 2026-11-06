package com.towndemontower.spring.tx;

import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.SkillResult;

/**
 * [starter/tx] 治理决策记录器抽象。把"执行 core 装饰器链"与"把决策/Outbox 落库"放进同一个
 * Spring 事务，保证：决策结果、审计、审批单、待发事件要么都成功、要么一起回滚（事务一致性）。
 */
public interface GovernanceDecisionRecorder {
    /**
     * 在事务内执行治理链并记录决策 + 写入 Outbox。
     *
     * @param risk 工具风险等级（来自 @AgentSkill 注解），用于审批优先级展示
     */
    SkillResult decide(Executor executor, IntentObject intent, ExecutionContext ctx, String risk);
}
