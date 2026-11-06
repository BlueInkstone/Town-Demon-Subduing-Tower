package com.towndemontower.core.contract;

import java.util.Map;

/**
 * [core] 模型唯一合法输出：结构化意图对象。
 * 禁止输出"建议你执行 X"类建议文本（由 IntentValidator / IntentParser 拦截）。
 * 字段均为语言中立类型，JSON 序列化，无 Optional / CompletableFuture。
 */
public class IntentObject {
    public final String tool;
    public final Map<String, Object> args;
    public final double confidence; // 0.0 - 1.0
    public final String reason;
    public final String expectedEffect;
    public final String rollbackHint;

    public IntentObject(String tool, Map<String, Object> args, double confidence,
                        String reason, String expectedEffect, String rollbackHint) {
        this.tool = tool;
        this.args = args;
        this.confidence = confidence;
        this.reason = reason;
        this.expectedEffect = expectedEffect;
        this.rollbackHint = rollbackHint;
    }
}
