package com.towndemontower.core.executor;

/** [core] 意图格式非法（建议文本 / 非意图 JSON）。 */
public class IntentFormatException extends RuntimeException {
    public IntentFormatException(String msg) { super(msg); }
}
