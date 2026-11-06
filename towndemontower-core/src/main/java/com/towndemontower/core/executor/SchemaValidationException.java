package com.towndemontower.core.executor;

/** [core] 意图参数不满足工具 Schema（缺必需参数）。 */
public class SchemaValidationException extends RuntimeException {
    public SchemaValidationException(String msg) { super(msg); }
}
