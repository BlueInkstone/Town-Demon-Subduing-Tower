package com.towndemontower.agents.governance;

import com.towndemontower.core.contract.SkillDescriptor;

/**
 * [agents] 注册表工具元数据（注册进 core SkillRegistry 的描述符）。
 *
 * <p>风险分级：
 * <ul>
 *   <li>查询 / 汇总 → <b>R0</b>：只读，只需 {@code agents:read}；</li>
 *   <li>注册 / 改档 / 状态变更 / 注销 → <b>R1</b>：变更档案与授权，可回滚纠正，需 {@code agents:write}。
 *       <b>生产建议</b>：把注销提升为 R2 并叠加双人复核——该动作会改变"谁能操作系统"，
 *       误操作影响面比改一个字段大得多；当前演示环境保持 R1 以便流程可完整走通。</li>
 * </ul>
 *
 * <p>{@code sideEffect=true} 会触发快照装饰器：写前留痕，便于追溯与回滚。</p>
 */
public final class AgentSkillDescriptors {

    private AgentSkillDescriptors() {
    }

    /** 注册 Agent（R1，有副作用）。 */
    public static SkillDescriptor register() {
        return new SkillDescriptor(
                AgentsModeToolPolicy.TOOL_REGISTER, "R1",
                new String[]{"agents:write"},
                new String[]{"agents"},
                new String[]{"name", "role"},
                true, "ephemeral");
    }

    /** 改档（R1，有副作用）。 */
    public static SkillDescriptor update() {
        return new SkillDescriptor(
                AgentsModeToolPolicy.TOOL_UPDATE, "R1",
                new String[]{"agents:write"},
                new String[]{"agents"},
                new String[]{"id"},
                true, "ephemeral");
    }

    /** 状态流转（R1，有副作用：会授予/回收权限）。 */
    public static SkillDescriptor changeStatus() {
        return new SkillDescriptor(
                AgentsModeToolPolicy.TOOL_CHANGE_STATUS, "R1",
                new String[]{"agents:write"},
                new String[]{"agents"},
                new String[]{"id", "status"},
                true, "ephemeral");
    }

    /** 注销并删除档案（R1，有副作用）。 */
    public static SkillDescriptor delete() {
        return new SkillDescriptor(
                AgentsModeToolPolicy.TOOL_DELETE, "R1",
                new String[]{"agents:write"},
                new String[]{"agents"},
                new String[]{"id"},
                true, "ephemeral");
    }

    /** 查询注册表与汇总（R0，只读）。 */
    public static SkillDescriptor query() {
        return new SkillDescriptor(
                AgentsModeToolPolicy.TOOL_QUERY, "R0",
                new String[]{"agents:read"},
                new String[]{"agents"},
                new String[0],
                false, "read-only");
    }
}
