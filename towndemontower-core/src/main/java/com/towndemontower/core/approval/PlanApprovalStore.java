package com.towndemontower.core.approval;

import java.util.List;

/**
 * [core] 计划单存储 SPI（V1.14）。
 *
 * <p>每次状态流转 {@link #save} 一条完整快照，启动时 {@link #load} 回放。
 * 用"快照追加"而不是"就地更新"，是为了让崩溃/半写留下的痕迹可以被识别，
 * 也便于审计侧对账（计划状态什么时候变过，日志里有顺序）。</p>
 *
 * <p>实现必须做到：要么完整写入，要么抛出异常；<b>不允许静默丢记录</b>。
 * 加载时遇到无法解析的行必须拒绝启动（fail-closed），不能跳过损坏记录继续。</p>
 */
public interface PlanApprovalStore {

    /** 追加一条计划快照。 */
    void save(PlanApproval approval);

    /** 加载全部快照（按写入顺序）。 */
    List<PlanApproval> load();
}
