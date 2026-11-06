package com.towndemontower.core.decorator;

import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.executor.CapabilityException;
import com.towndemontower.core.executor.SkillRegistry;

/**
 * [core] 装饰器④：能力范围校验。意图请求的能力须在工具 capabilities 内。
 */
public class CapabilityDecorator implements Executor {
    private final Executor next;
    private final SkillRegistry registry;

    public CapabilityDecorator(Executor next, SkillRegistry registry) {
        this.next = next;
        this.registry = registry;
    }

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        SkillDescriptor d = registry.get(intent.tool);
        if (d.capabilities != null && intent.args != null) {
            Object need = intent.args.get("needCapability");
            if (need instanceof String) {
                boolean ok = false;
                for (String c : d.capabilities) if (c.equals(need)) { ok = true; break; }
                if (!ok) {
                    throw new CapabilityException("capability " + need + " not within " + d.name);
                }
            }
        }
        return next.execute(intent, ctx);
    }
}
