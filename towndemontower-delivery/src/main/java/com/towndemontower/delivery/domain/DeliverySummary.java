package com.towndemontower.delivery.domain;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * [delivery] 整体交付进度汇总（自动计算）。
 *
 * <p>汇总口径：
 * <ul>
 *   <li>{@code totalCount} —— 交付项总数；</li>
 *   <li>{@code completedCount / inProgressCount / delayedCount / notStartedCount} —— 各状态计数；</li>
 *   <li>{@code overallProgressPercent} —— 整体进度百分比 = 各交付项进度百分比之和 ÷ 总数（向下取整）。
 *       等权平均，不按阶段权重加权，保证口径简单可解释；</li>
 *   <li>{@code completedRatioPercent} —— 完成率 = 已完成数 ÷ 总数；</li>
 *   <li>{@code earliestPlannedEnd} —— 未完成项中<b>最早</b>的计划完成时间（含已延期项，
 *       即"最早本应交付却未交付"的那一项）；</li>
 *   <li>{@code nextUpcomingPlannedEnd} —— 未完成且<b>计划完成时间尚未到期</b>的项中
 *       最早的那个，即"下一个交付节点"；该字段才是看板"下一个交付节点"卡片的依据，
 *       因为已延期项的计划完成时间在过去，直接展示会误导；</li>
 *   <li>{@code updatedAt} —— 汇总生成时刻。</li>
 * </ul>
 */
public final class DeliverySummary {

    private final int totalCount;
    private final int completedCount;
    private final int inProgressCount;
    private final int delayedCount;
    private final int notStartedCount;
    private final int overallProgressPercent;
    private final int completedRatioPercent;
    private final LocalDateTime earliestPlannedEnd;
    private final LocalDateTime nextUpcomingPlannedEnd;
    private final LocalDateTime updatedAt;

    public DeliverySummary(int totalCount,
                            int completedCount,
                            int inProgressCount,
                            int delayedCount,
                            int notStartedCount,
                            int overallProgressPercent,
                            int completedRatioPercent,
                            LocalDateTime earliestPlannedEnd,
                            LocalDateTime nextUpcomingPlannedEnd,
                            LocalDateTime updatedAt) {
        this.totalCount = totalCount;
        this.completedCount = completedCount;
        this.inProgressCount = inProgressCount;
        this.delayedCount = delayedCount;
        this.notStartedCount = notStartedCount;
        this.overallProgressPercent = overallProgressPercent;
        this.completedRatioPercent = completedRatioPercent;
        this.earliestPlannedEnd = earliestPlannedEnd;
        this.nextUpcomingPlannedEnd = nextUpcomingPlannedEnd;
        this.updatedAt = updatedAt;
    }

    public int totalCount() {
        return totalCount;
    }

    public int completedCount() {
        return completedCount;
    }

    public int inProgressCount() {
        return inProgressCount;
    }

    public int delayedCount() {
        return delayedCount;
    }

    public int notStartedCount() {
        return notStartedCount;
    }

    /** 整体进度百分比（0~100，等权平均）。 */
    public int overallProgressPercent() {
        return overallProgressPercent;
    }

    /** 完成率百分比（0~100）。 */
    public int completedRatioPercent() {
        return completedRatioPercent;
    }

    /** 最近一个待交付节点的计划完成时间；无未完成项时为 null。 */
    public LocalDateTime earliestPlannedEnd() {
        return earliestPlannedEnd;
    }

    /**
     * 下一个交付节点：未完成且计划完成时间<b>尚未到期</b>的项中最早的那个。
     *
     * <p>与 {@link #earliestPlannedEnd()} 的区别：后者会把已延期项（计划完成时间在过去）
     * 也算进来，因此它回答的是"最早本应交付却未交付的是哪一项"，
     * 而本字段回答的是"下一个还没到期的交付节点是什么"——
     * 看板上的"下一个交付节点"卡片应当使用本字段。</p>
     *
     * <p>若所有未完成项均已延期（没有未到期的节点），返回 null。</p>
     */
    public LocalDateTime nextUpcomingPlannedEnd() {
        return nextUpcomingPlannedEnd;
    }

    public LocalDateTime updatedAt() {
        return updatedAt;
    }

    /** 空汇总（无任何交付项）。 */
    public static DeliverySummary empty(Instant now) {
        return new DeliverySummary(0, 0, 0, 0, 0, 0, 0, null, null, toLocal(now));
    }

    static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null
                : LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
    }
}
