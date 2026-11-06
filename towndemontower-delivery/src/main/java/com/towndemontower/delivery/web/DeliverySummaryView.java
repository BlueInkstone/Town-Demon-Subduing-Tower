package com.towndemontower.delivery.web;

import com.towndemontower.delivery.domain.DeliverySummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * [delivery/web] 整体交付进度汇总视图（看板顶部卡片区数据源）。
 */
@Schema(description = "整体交付进度汇总")
public class DeliverySummaryView {

    @Schema(description = "交付项总数", example = "13")
    public int totalCount;
    @Schema(description = "已完成数量", example = "6")
    public int completedCount;
    @Schema(description = "进行中数量", example = "1")
    public int inProgressCount;
    @Schema(description = "已延期数量", example = "1")
    public int delayedCount;
    @Schema(description = "未开始数量", example = "5")
    public int notStartedCount;
    /** 整体进度百分比（0~100）。 */
    @Schema(description = "整体进度百分比（各交付项进度的等权平均，向下取整）", example = "49")
    public int overallProgressPercent;
    /** 完成率百分比（0~100）。 */
    @Schema(description = "完成率百分比（已完成数 / 总数）", example = "46")
    public int completedRatioPercent;
    /** 未完成项中最早的计划完成时间（含已延期项）。 */
    @Schema(description = "未完成项中最早的计划完成时间（含已延期项）；无未完成项时为 null")
    public LocalDateTime earliestPlannedEnd;
    /** 下一个交付节点：未完成且尚未到期的项中最早的计划完成时间。 */
    @Schema(description = "下一个交付节点：未完成且尚未到期项中最早的计划完成时间；无则 null")
    public LocalDateTime nextUpcomingPlannedEnd;
    @Schema(description = "汇总生成时刻")
    public LocalDateTime updatedAt;

    public static DeliverySummaryView from(DeliverySummary s) {
        DeliverySummaryView v = new DeliverySummaryView();
        v.totalCount = s.totalCount();
        v.completedCount = s.completedCount();
        v.inProgressCount = s.inProgressCount();
        v.delayedCount = s.delayedCount();
        v.notStartedCount = s.notStartedCount();
        v.overallProgressPercent = s.overallProgressPercent();
        v.completedRatioPercent = s.completedRatioPercent();
        v.earliestPlannedEnd = s.earliestPlannedEnd();
        v.nextUpcomingPlannedEnd = s.nextUpcomingPlannedEnd();
        v.updatedAt = s.updatedAt();
        return v;
    }
}
