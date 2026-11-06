package com.towndemontower.delivery.governance;

import com.towndemontower.core.contract.SkillDescriptor;

/**
 * [delivery] 交付看板工具元数据（注册进 core SkillRegistry 的描述符）。
 *
 * <p>风险分级：
 * <ul>
 *   <li>创建 / 更新 / 删除交付项 → <b>R1</b>：业务数据写操作，可回滚（看板数据本身是管理元数据，
 *       误操作可通过再次更新纠正），由 {@code HumanApprovalPolicy} 直接放行但仍进审计哈希链；</li>
 *   <li>查询看板 / 汇总 → <b>R0</b>：只读。</li>
 * </ul>
 *
 * <p>{@code sideEffect=true} 触发 core 的快照装饰器：写操作前留痕，便于追溯与回滚。</p>
 */
public final class DeliverySkillDescriptors {

    private DeliverySkillDescriptors() {
    }

    /** 创建交付项（R1，有副作用）。 */
    public static SkillDescriptor create() {
        return new SkillDescriptor(
                DeliveryModeToolPolicy.TOOL_CREATE, "R1",
                new String[]{"delivery:write"},
                new String[]{"delivery"},
                new String[]{"phaseName", "owner", "plannedStart", "plannedEnd"},
                true, "ephemeral");
    }

    /** 更新交付项（R1，有副作用）。 */
    public static SkillDescriptor update() {
        return new SkillDescriptor(
                DeliveryModeToolPolicy.TOOL_UPDATE, "R1",
                new String[]{"delivery:write"},
                new String[]{"delivery"},
                new String[]{"id"},
                true, "ephemeral");
    }

    /** 删除交付项（R1，有副作用；删除是显式动作，故仍需 write 权限）。 */
    public static SkillDescriptor delete() {
        return new SkillDescriptor(
                DeliveryModeToolPolicy.TOOL_DELETE, "R1",
                new String[]{"delivery:write"},
                new String[]{"delivery"},
                new String[]{"id"},
                true, "ephemeral");
    }

    /** 查询看板与汇总（R0，只读）。 */
    public static SkillDescriptor query() {
        return new SkillDescriptor(
                DeliveryModeToolPolicy.TOOL_QUERY, "R0",
                new String[]{"delivery:read"},
                new String[]{"delivery"},
                new String[0],
                false, "read-only");
    }
}
