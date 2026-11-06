package com.towndemontower.spring;

import com.towndemontower.core.decorator.GovernancePass;
import org.springframework.stereotype.Component;

/**
 * [starter/test] 示例受治理技能。每个方法用 {@link AgentSkill} 声明其工具元数据，
 * 运行时被 {@link AgentSkillAspect} 拦截并路由进 core 装饰器链。
 *
 * <p>覆盖四种典型结局：R1 放行、R3 拒绝、R2 待审批、模式白名单拦截。
 * 工具名刻意复用 core 预注册集合（restartService/deployService/deleteData/splitTask），
 * 以证明适配层直接复用 core 默认策略，而非另起一套。</p>
 *
 * <p><b>V1.11 起每个方法体首行都是 {@code GovernancePass.require("工具名")}</b>：这是
 * "经过治理链"从约定变成前置条件的地方——自调用（{@code this.x()}）走不到切面，因此也就
 * 拿不到链尾发出的令牌，首行会直接失败。参数必须与 {@code @AgentSkill(name=...)} 逐字一致，
 * CP-10 扫描规则 R3 会校验这一点。</p>
 */
@Component
public class AgentSkillDemo {

    @AgentSkill(name = "restartService", risk = "R1",
            requiredPerms = {"ops:restart"}, requiredArgs = {"service"},
            confidence = 1.0)
    public String restartService(String service) {
        GovernancePass.require("restartService");
        return "restarted:" + service;
    }

    @AgentSkill(name = "deleteData", risk = "R3",
            requiredPerms = {"data:delete"}, requiredArgs = {"table"},
            confidence = 1.0)
    public String deleteData(String table) {
        GovernancePass.require("deleteData");
        return "deleted:" + table;
    }

    @AgentSkill(name = "deployService", risk = "R2",
            requiredPerms = {"ops:deploy"}, requiredArgs = {"target", "version"},
            confidence = 1.0)
    public String deployService(String target, String version) {
        GovernancePass.require("deployService");
        return "deployed:" + target + "@" + version;
    }

    @AgentSkill(name = "splitTask", risk = "R0",
            requiredPerms = {"plan:write"}, requiredArgs = {"requirement"},
            confidence = 1.0)
    public String splitTask(String requirement) {
        GovernancePass.require("splitTask");
        return "split:" + requirement;
    }
}
