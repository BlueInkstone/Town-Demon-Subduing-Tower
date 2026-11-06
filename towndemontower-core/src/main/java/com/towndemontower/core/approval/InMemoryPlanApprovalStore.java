package com.towndemontower.core.approval;

import java.util.List;

/**
 * [core] 默认内存存储（V1.14）。
 *
 * <p>用于单测、脚本和内嵌场景；进程重启后计划单丢失（闸门仍 fail-closed，
 * 重新提交即可）。需要跨重启的接入方显式装配 {@link FilePlanApprovalStore}。</p>
 */
public final class InMemoryPlanApprovalStore implements PlanApprovalStore {

    @Override
    public void save(PlanApproval approval) {
        // 内存实现无需额外动作：PlanApprovalService 自己持有当前状态。
    }

    @Override
    public List<PlanApproval> load() {
        return List.of();
    }
}
