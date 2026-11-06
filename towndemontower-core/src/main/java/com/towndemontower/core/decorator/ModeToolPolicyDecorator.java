package com.towndemontower.core.decorator;

import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.mode.ModeToolPolicy;

/**
 * [core] 装饰器①：模式工具白名单（第三层 AND）。
 * 当前 AgentMode 不允许的工具 → MODE_TOOL_NOT_ALLOWED（模型看不到，但伪造名也拦）。
 */
public class ModeToolPolicyDecorator implements Executor {
    private final Executor next;
    private final ModeToolPolicy policy;

    public ModeToolPolicyDecorator(Executor next, ModeToolPolicy policy) {
        this.next = next;
        this.policy = policy;
    }

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        if (!policy.allowedTools(ctx.mode).contains(intent.tool)) {
            return SkillResult.denied(ErrorCodes.MODE_TOOL_NOT_ALLOWED,
                    "tool " + intent.tool + " not allowed in mode " + ctx.mode);
        }
        return next.execute(intent, ctx);
    }
}
