package com.towndemontower.core.decorator;

import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.executor.SnapshotService;

/**
 * [core] 装饰器⑧：执行前快照。写操作（sideEffect）前三样落盘，失败抛异常由外层转 SNAPSHOT_FAILED。
 */
public class SnapshotDecorator implements Executor {
    private final Executor next;
    private final SnapshotService snapshotService;
    private final SkillRegistry registry;

    public SnapshotDecorator(Executor next, SnapshotService snapshotService, SkillRegistry registry) {
        this.next = next;
        this.snapshotService = snapshotService;
        this.registry = registry;
    }

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        SkillDescriptor d = registry.get(intent.tool);
        if (d != null && d.sideEffect) {
            snapshotService.beforeWrite(intent, d);
        }
        return next.execute(intent, ctx);
    }
}
