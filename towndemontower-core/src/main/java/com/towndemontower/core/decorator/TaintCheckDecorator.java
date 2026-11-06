package com.towndemontower.core.decorator;

import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.executor.SkillRegistry;

/**
 * [core] 装饰器⑦：污点追踪。读取过不可信来源的会话（tainted=true），
 * 其后续写/外发操作需额外策略校验，否则 TAINTED_WRITE_BLOCKED。
 */
public class TaintCheckDecorator implements Executor {
    private final Executor next;
    private final SkillRegistry registry;

    public TaintCheckDecorator(Executor next, SkillRegistry registry) {
        this.next = next;
        this.registry = registry;
    }

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        if (ctx.tainted) {
            SkillDescriptor d = registry.get(intent.tool);
            if (d != null && d.sideEffect) {
                return SkillResult.denied(ErrorCodes.TAINTED_WRITE_BLOCKED,
                        "tainted session blocked from write action " + intent.tool);
            }
        }
        return next.execute(intent, ctx);
    }
}
