package com.towndemontower.agents.domain;

import java.util.List;

/**
 * [agents] 角色模板：新建 Agent 的默认技能与默认权限。
 *
 * <p>"注册一个 Agent"如果每次都要人工勾选十几个技能与权限，配置成本会高到没人愿意用，
 * 最终大家都会图省事全选——最小权限原则反而被破坏。因此每个角色给一份<b>恰好够用</b>的
 * 默认模板：注册时套模板即可快速可用，个别差异再用技能/权限字段覆盖。</p>
 *
 * <p><b>模板里的技能必须是 core {@code SkillRegistry} 中真实存在的工具名</b>，
 * 否则档案会指向一个不存在的工具，等到执行时才炸——这类错误必须在注册时
 * 就被 {@code SkillCatalog} 拦下来。</p>
 */
public final class RoleTemplate {

    private RoleTemplate() {
    }

    /** 角色默认技能（core SkillRegistry 中的工具名）。 */
    public static List<String> defaultSkills(AgentRole role) {
        if (role == null) return List.of();
        return switch (role) {
            case PM -> List.of("splitTask", "writeDesign");
            case ARCHITECT -> List.of("writeDesign", "splitTask", "readDiff");
            case DEVELOPER -> List.of("writeFile", "runCompile", "readLog");
            case QA -> List.of("runRuleScan", "readTestReport", "readDiff");
            case OPS -> List.of("restartService", "deployService", "readLog");
        };
    }

    /** 角色默认 RBAC 权限。 */
    public static List<String> defaultPermissions(AgentRole role) {
        if (role == null) return List.of();
        return switch (role) {
            case PM -> List.of("plan:write", "delivery:read", "delivery:write", "agents:read");
            case ARCHITECT -> List.of("plan:write", "review:read", "agents:read");
            case DEVELOPER -> List.of("fs:write", "build:run", "log:read", "agents:read");
            case QA -> List.of("review:read", "log:read", "agents:read");
            case OPS -> List.of("ops:restart", "ops:deploy", "log:read", "agents:read");
        };
    }

    /** 角色默认模型档位：只有架构师默认强推理，其余走快速档以控制成本。 */
    public static ModelTier defaultModelTier(AgentRole role) {
        return role == AgentRole.ARCHITECT ? ModelTier.STRONG : ModelTier.FAST;
    }
}
