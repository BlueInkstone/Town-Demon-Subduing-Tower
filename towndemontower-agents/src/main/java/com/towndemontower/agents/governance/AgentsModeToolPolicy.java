package com.towndemontower.agents.governance;

import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.mode.ModeToolPolicy;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * [agents] Agent 注册表专属模式工具白名单。
 *
 * <p>注册表的读写是业务操作，不涉及 shell / 文件等危险动作，因此加入全部模式的
 * 白名单，风险由风险闸门按 R0/R1 单独裁决。</p>
 *
 * <p>本类是<b>模块内独立实例</b>，不注册为 Spring Bean，避免覆盖 starter 的业务主链白名单。</p>
 */
public class AgentsModeToolPolicy implements ModeToolPolicy {

    public static final String TOOL_REGISTER = "agents.registerAgent";
    public static final String TOOL_UPDATE = "agents.updateAgent";
    public static final String TOOL_CHANGE_STATUS = "agents.changeStatus";
    public static final String TOOL_DELETE = "agents.deleteAgent";
    public static final String TOOL_QUERY = "agents.query";

    private static final Set<String> AGENT_TOOLS;

    static {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, TOOL_REGISTER, TOOL_UPDATE, TOOL_CHANGE_STATUS, TOOL_DELETE, TOOL_QUERY);
        AGENT_TOOLS = Collections.unmodifiableSet(s);
    }

    @Override
    public Set<String> allowedTools(AgentMode mode) {
        return AGENT_TOOLS;
    }
}
