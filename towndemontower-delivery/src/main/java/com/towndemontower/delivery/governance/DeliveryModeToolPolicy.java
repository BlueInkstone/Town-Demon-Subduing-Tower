package com.towndemontower.delivery.governance;

import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.mode.ModeToolPolicy;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * [delivery] 交付看板专属模式工具白名单。
 *
 * <p>core 的 {@code DefaultModeToolPolicy} 白名单面向"通用开发工具"，
 * 交付看板的工具（创建/更新/删除交付项、查询看板）不在其中。
 * 本实现把交付看板工具加入<b>全部模式</b>的白名单——因为看板读写是纯业务操作，
 * 不涉及 shell/文件等高危动作，风险由风险闸门（R0-R3）单独裁决。</p>
 *
 * <p>注意：这是<b>模块内自带的独立白名单</b>，不替换 core 的默认策略；
 * 交付看板用独立注册表与独立策略装配自己的治理链，因此不会影响业务方
 * 通过 core 装配的主链行为。</p>
 */
public class DeliveryModeToolPolicy implements ModeToolPolicy {

    /** 交付看板工具名（同时用作 core SkillRegistry 的 key）。 */
    public static final String TOOL_CREATE = "delivery.createItem";
    public static final String TOOL_UPDATE = "delivery.updateItem";
    public static final String TOOL_DELETE = "delivery.deleteItem";
    public static final String TOOL_QUERY = "delivery.queryBoard";

    private static final Set<String> DELIVERY_TOOLS;

    static {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, TOOL_CREATE, TOOL_UPDATE, TOOL_DELETE, TOOL_QUERY);
        DELIVERY_TOOLS = Collections.unmodifiableSet(s);
    }

    @Override
    public Set<String> allowedTools(AgentMode mode) {
        return DELIVERY_TOOLS;
    }
}
