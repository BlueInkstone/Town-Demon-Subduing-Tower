package com.towndemontower.core.statemachine;

/**
 * [core] 业务态（唯一入口）。状态机管"现在能不能做"，安全闸门管"动作是否允许"，二者 AND。
 */
public enum TaskStatus {
    PENDING, CODING, EXECUTING, PAUSED, DONE
}
