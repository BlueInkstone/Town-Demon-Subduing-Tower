package com.towndemontower.core.executor;

import com.towndemontower.core.audit.AuditStore;

import java.util.Collection;
import java.util.Objects;

/**
 * [core] {@link PermissionProvisioner} 的唯一实现。
 *
 * <p>放在 {@code core.executor} 包内，是为了能调用 {@link PermissionEngine} 的
 * 包内可见写方法——这是"收权"能成立的关键：<b>实现与引擎同包，外部只见端口</b>。</p>
 *
 * <p>审计语义：只在<b>真的改变了状态</b>时写记录（空授予、回收 0 条都不写），
 * 避免日志被无效操作淹没；但 actor 与目标主体一定记录，便于对账"谁在什么时候放权"。</p>
 */
final class EnginePermissionProvisioner implements PermissionProvisioner {

    private final PermissionEngine engine;
    private final AuditStore audit;
    private final String actor;

    EnginePermissionProvisioner(PermissionEngine engine, AuditStore audit, String actor) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.audit = audit;
        this.actor = actor;
    }

    @Override
    public void grant(String agentId, String... perms) {
        if (agentId == null || perms == null || perms.length == 0) return;
        engine.grant(agentId, perms);
        record("PERMISSION_GRANTED", agentId, String.join(",", perms));
    }

    @Override
    public void grant(String agentId, Collection<String> perms) {
        if (perms == null || perms.isEmpty()) return;
        grant(agentId, perms.toArray(new String[0]));
    }

    @Override
    public int revoke(String agentId, String... perms) {
        int removed = engine.revoke(agentId, perms);
        if (removed > 0) record("PERMISSION_REVOKED", agentId,
                String.join(",", perms) + "|removed=" + removed);
        return removed;
    }

    @Override
    public int revokeAll(String agentId) {
        int removed = engine.revokeAll(agentId);
        if (removed > 0) record("PERMISSION_REVOKED_ALL", agentId, "removed=" + removed);
        return removed;
    }

    @Override
    public void replace(String agentId, Collection<String> perms) {
        engine.revokeAll(agentId);
        if (perms == null || perms.isEmpty()) {
            record("PERMISSION_REPLACED", agentId, "removed=all|granted=-");
            return;
        }
        engine.grant(agentId, perms.toArray(new String[0]));
        record("PERMISSION_REPLACED", agentId, "granted=" + String.join(",", perms));
    }

    @Override
    public String actor() {
        return actor;
    }

    private void record(String action, String agentId, String detail) {
        if (audit == null) return;
        audit.appendAuto(action, "actor=" + (actor == null ? "-" : actor)
                + "|agent=" + agentId + "|" + detail);
    }
}
