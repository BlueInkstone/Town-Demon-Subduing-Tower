package com.towndemontower.core.executor;

/** [core] 写操作前快照失败，禁止继续写。 */
public class SnapshotFailedException extends RuntimeException {
    public SnapshotFailedException(String msg) { super(msg); }
}
