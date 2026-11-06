package com.towndemontower.core.statemachine;

public class IllegalTransitionException extends RuntimeException {
    public IllegalTransitionException(TaskStatus from, TaskEvent event) {
        super("ILLEGAL_TASK_TRANSITION: " + from + " --" + event);
    }
}
