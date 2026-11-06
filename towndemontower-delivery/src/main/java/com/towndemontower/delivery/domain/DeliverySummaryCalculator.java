package com.towndemontower.delivery.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * [delivery] 整体交付进度汇总计算（纯函数）。
 *
 * <p>口径见 {@link DeliverySummary}：
 * <ul>
 *   <li>整体进度 = 各交付项进度百分比之和 ÷ 总数（向下取整）；</li>
 *   <li>完成率 = 已完成数 ÷ 总数（向下取整）；</li>
 *   <li>earliestPlannedEnd = 未完成项中最早的计划完成时间（含已延期项）；</li>
 *   <li>nextUpcomingPlannedEnd = 未完成且<b>尚未到期</b>的项中最早的计划完成时间
 *       （即看板"下一个交付节点"应展示的日期）。</li>
 * </ul>
 */
public final class DeliverySummaryCalculator {

    private DeliverySummaryCalculator() {
    }

    /**
     * 基于交付项列表计算汇总。
     *
     * @param items 交付项列表（可为空）
     * @param now   当前时刻，用于判定各交付项状态
     */
    public static DeliverySummary calculate(List<DeliveryItem> items, Instant now) {
        if (items == null || items.isEmpty()) {
            return DeliverySummary.empty(now);
        }

        int total = items.size();
        int completed = 0;
        int inProgress = 0;
        int delayed = 0;
        int notStarted = 0;
        long progressSum = 0;
        LocalDateTime earliest = null;        // 未完成项中最早的计划完成时间（含已延期）
        LocalDateTime nextUpcoming = null;    // 未完成且尚未到期项中最早的计划完成时间

        for (DeliveryItem item : items) {
            DeliveryStatus st = DeliveryStatusResolver.resolve(item, now);
            switch (st) {
                case COMPLETED -> completed++;
                case IN_PROGRESS -> inProgress++;
                case DELAYED -> delayed++;
                default -> notStarted++;
            }
            progressSum += item.progressPercent();

            // 只有未完成项才参与"下一个交付节点"评选
            if (st != DeliveryStatus.COMPLETED && item.plannedEnd() != null) {
                if (earliest == null || item.plannedEnd().isBefore(earliest)) {
                    earliest = item.plannedEnd();
                }
                // 已延期项的计划完成时间在过去，对"下一个节点"没有参考意义，故排除
                boolean overdue = item.plannedEnd()
                        .isBefore(DeliverySummary.toLocal(now));
                if (!overdue
                        && (nextUpcoming == null || item.plannedEnd().isBefore(nextUpcoming))) {
                    nextUpcoming = item.plannedEnd();
                }
            }
        }

        int overall = (int) (progressSum / total);
        int ratio = (int) ((long) completed * 100 / total);
        return new DeliverySummary(total, completed, inProgress, delayed, notStarted,
                overall, ratio, earliest, nextUpcoming, DeliverySummary.toLocal(now));
    }
}
