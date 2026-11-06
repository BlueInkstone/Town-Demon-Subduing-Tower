package com.towndemontower.core.decorator;

import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.executor.IntentFormatException;

/**
 * [core] 装饰器②：意图格式校验 + 置信度策略。
 *  - 结构非法/建议文本 → IntentFormatException（外层转 INTENT_FORMAT_INVALID）
 *  - 置信度 < 0.5 → 返回 NEED_MORE_INFO（非拒绝，让系统决定是否向人确认）
 */
public class IntentFormatDecorator implements Executor {
    private final Executor next;

    public IntentFormatDecorator(Executor next) {
        this.next = next;
    }

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        if (intent == null || intent.tool == null || intent.tool.trim().isEmpty()) {
            throw new IntentFormatException("intent.tool missing");
        }
        if (intent.confidence < 0.0 || intent.confidence > 1.0) {
            throw new IntentFormatException("confidence out of range");
        }
        if (isSuggestionText(intent)) {
            throw new IntentFormatException("suggestion text not allowed: " + intent.reason);
        }
        if (intent.confidence < 0.5) {
            return SkillResult.needMoreInfo("confidence=" + intent.confidence + " < 0.5, provide more info");
        }
        return next.execute(intent, ctx);
    }

    /** 检测"建议你执行 X"类建议文本（解析层拒收，逼模型只输出意图对象）。 */
    private boolean isSuggestionText(IntentObject intent) {
        String r = intent.reason == null ? "" : intent.reason;
        return r.contains("建议") || r.toLowerCase().contains("suggest")
                || r.toLowerCase().contains("you may consider");
    }
}
