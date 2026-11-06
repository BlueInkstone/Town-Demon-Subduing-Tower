package com.towndemontower.core.decorator;

import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.executor.PermissionDeniedException;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.SkillRegistry;

/**
 * [core] 装饰器⑤：RBAC 权限校验（effectivePerms = ownerPerms ∩ agentPerms）。
 */
public class PermissionDecorator implements Executor {
    private final Executor next;
    private final PermissionEngine engine;
    private final SkillRegistry registry;

    public PermissionDecorator(Executor next, PermissionEngine engine, SkillRegistry registry) {
        this.next = next;
        this.engine = engine;
        this.registry = registry;
    }

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        SkillDescriptor d = registry.get(intent.tool);
        engine.assertAllowedByAgent(ctx.agentId, d);
        return next.execute(intent, ctx);
    }
}
