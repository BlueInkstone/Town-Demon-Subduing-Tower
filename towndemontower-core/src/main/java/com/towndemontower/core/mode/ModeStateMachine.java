package com.towndemontower.core.mode;

import java.util.EnumMap;
import java.util.Map;

/**
 * [core] 模式转换表（纯 Java，不依赖 Spring StateMachine）。
 * 非法转换（如 AUDIT_REVIEWING 跳过重规划直接进 DEV_EXECUTION）抛
 * ModeTransitionInvalidException，并留审计。
 */
public class ModeStateMachine {
    private final Map<AgentMode, Map<ModeEvent, AgentMode>> table = new EnumMap<>(AgentMode.class);

    public ModeStateMachine() {
        add(AgentMode.ARCHITECT_PLANNING, ModeEvent.PLAN_APPROVED, AgentMode.DEV_EXECUTION);
        add(AgentMode.DEV_EXECUTION, ModeEvent.ENTER_AUDIT, AgentMode.AUDIT_REVIEWING);
        add(AgentMode.AUDIT_REVIEWING, ModeEvent.REPLAN, AgentMode.ARCHITECT_PLANNING);
        // 注意：没有 AUDIT_REVIEWING -> DEV_EXECUTION 的直连，必须 REPLAN
    }

    private void add(AgentMode from, ModeEvent event, AgentMode to) {
        table.computeIfAbsent(from, k -> new EnumMap<>(ModeEvent.class)).put(event, to);
    }

    public AgentMode transition(AgentMode current, ModeEvent event) {
        Map<ModeEvent, AgentMode> row = table.get(current);
        if (row == null || !row.containsKey(event)) {
            throw new ModeTransitionInvalidException(current, event);
        }
        return row.get(event);
    }
}
