package com.towndemontower.core.decorator;

import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.executor.SchemaValidationException;
import com.towndemontower.core.executor.SkillRegistry;

/**
 * [core] 装饰器③：Schema 校验。意图必须携带工具声明的 requiredArgs。
 */
public class SchemaDecorator implements Executor {
    private final Executor next;
    private final SkillRegistry registry;

    public SchemaDecorator(Executor next, SkillRegistry registry) {
        this.next = next;
        this.registry = registry;
    }

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        SkillDescriptor d = registry.get(intent.tool);
        if (d == null) {
            throw new SchemaValidationException("unknown tool " + intent.tool);
        }
        if (d.requiredArgs != null) {
            for (String key : d.requiredArgs) {
                if (intent.args == null || !intent.args.containsKey(key)) {
                    throw new SchemaValidationException("missing required arg: " + key);
                }
            }
        }
        return next.execute(intent, ctx);
    }
}
