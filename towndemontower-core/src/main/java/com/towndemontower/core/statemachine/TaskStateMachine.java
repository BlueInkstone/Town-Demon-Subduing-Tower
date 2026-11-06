package com.towndemontower.core.statemachine;

import java.util.EnumMap;
import java.util.Map;

/**
 * [core] 任务业务态转换表。与模式状态机解耦，均由事件唯一驱动。
 * EMERGENCY_STOP 不仅转 PAUSED，调用方还需发布取消令牌（starter 负责）。
 */
public class TaskStateMachine {
    private final Map<TaskStatus, Map<TaskEvent, TaskStatus>> table = new EnumMap<>(TaskStatus.class);

    public TaskStateMachine() {
        add(TaskStatus.PENDING, TaskEvent.INIT, TaskStatus.CODING);
        add(TaskStatus.CODING, TaskEvent.PLAN_APPROVED, TaskStatus.EXECUTING);
        add(TaskStatus.EXECUTING, TaskEvent.PAUSE, TaskStatus.PAUSED);
        add(TaskStatus.PAUSED, TaskEvent.RESUME, TaskStatus.EXECUTING);
        add(TaskStatus.EXECUTING, TaskEvent.EMERGENCY_STOP, TaskStatus.PAUSED);
        add(TaskStatus.EXECUTING, TaskEvent.DONE, TaskStatus.DONE);
        add(TaskStatus.CODING, TaskEvent.EMERGENCY_STOP, TaskStatus.PAUSED);
    }

    private void add(TaskStatus from, TaskEvent event, TaskStatus to) {
        table.computeIfAbsent(from, k -> new EnumMap<>(TaskEvent.class)).put(event, to);
    }

    public TaskStatus transition(TaskStatus current, TaskEvent event) {
        Map<TaskEvent, TaskStatus> row = table.get(current);
        if (row == null || !row.containsKey(event)) {
            throw new IllegalTransitionException(current, event);
        }
        return row.get(event);
    }
}
