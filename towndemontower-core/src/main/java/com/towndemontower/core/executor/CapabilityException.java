package com.towndemontower.core.executor;

/** [core] 意图超出工具能力范围。 */
public class CapabilityException extends RuntimeException {
    public CapabilityException(String msg) { super(msg); }
}
