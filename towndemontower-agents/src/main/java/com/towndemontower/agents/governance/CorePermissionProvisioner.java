package com.towndemontower.agents.governance;

import com.towndemontower.agents.domain.AccessProvisioner;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;

import java.util.List;
import java.util.Set;

/**
 * [agents/governance] 权限开通的 core 实现：把档案里的授权意图落到
 * core {@link PermissionEngine}（数字镜像 RBAC）。
 *
 * <p><b>覆盖式授予</b>：每次 {@link #grant} 先整员回收再按新清单授予。
 * 若只增不减，Agent 从"开发"转"测试"后仍会保留 {@code fs:write}、{@code build:run}，
 * 权限只会随着改档不断膨胀——这是内部系统最常见的权限腐化路径。</p>
 *
 * <p><b>V1.9 / I3</b>：写权限只能经 {@link PermissionProvisioner} 端口（否则编译不过），
 * 因此本类的写方法都转发给端口；读（{@code currentPermissions}）仍直接查引擎，
 * 因为读不需要授权能力。构造器强制显式传入端口，避免"顺手 new 一个引擎就能授权"。</p>
 */
public class CorePermissionProvisioner implements AccessProvisioner {

    private final PermissionProvisioner port;
    private final PermissionEngine engine;

    public CorePermissionProvisioner(PermissionProvisioner port, PermissionEngine engine) {
        if (port == null) throw new IllegalArgumentException("port must not be null");
        if (engine == null) throw new IllegalArgumentException("engine must not be null");
        this.port = port;
        this.engine = engine;
    }

    @Override
    public void grant(String agentId, List<String> permissions) {
        if (agentId == null || agentId.isBlank()) return;
        // replace 的语义就是"先回收再授予"，一次调用一条审计，比"回收 + 授予"两条更清晰
        port.replace(agentId, permissions);
    }

    @Override
    public void revokeAll(String agentId) {
        if (agentId == null || agentId.isBlank()) return;
        port.revokeAll(agentId);
    }

    @Override
    public Set<String> currentPermissions(String agentId) {
        return engine.permissionsOf(agentId);
    }
}
