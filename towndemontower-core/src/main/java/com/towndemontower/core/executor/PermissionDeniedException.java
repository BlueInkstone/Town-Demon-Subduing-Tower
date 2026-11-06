package com.towndemontower.core.executor;

/** [core] 权限不足，执行被拒（审计仍记录）。 */
public class PermissionDeniedException extends RuntimeException {
    public PermissionDeniedException(String msg) { super(msg); }
}
