package com.towndemontower.delivery.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * [delivery] 交付项状态推导规则（纯函数，零依赖）。
 *
 * <p>推导优先级（自上而下，先命中先生效）：
 * <ol>
 *   <li><b>人工覆盖</b>：交付项显式指定了状态 → 直接采用（业务可覆盖自动规则，如项目挂起）。</li>
 *   <li><b>已完成</b>：进度 = 100%，或已填写实际完成时间 → COMPLETED。</li>
 *   <li><b>已延期</b>：未完成，且当前时间已超过计划完成时间 → DELAYED。</li>
 *   <li><b>进行中</b>：进度 &gt; 0，或已到计划开始时间 → IN_PROGRESS。</li>
 *   <li><b>未开始</b>：其余情况 → NOT_STARTED。</li>
 * </ol>
 *
 * <p>注意"已延期"判定使用"当前时间"而非"创建时间"，因此交付项到期的那一刻起，
 * 无需任何写操作，看板即刻呈现"已延期"。</p>
 */
public final class DeliveryStatusResolver {

    private DeliveryStatusResolver() {
    }

    /**
     * 推导交付项在给定时刻的生效状态。
     *
     * @param item 交付项（不可为 null）
     * @param now  当前时刻（不可为 null）
     */
    public static DeliveryStatus resolve(DeliveryItem item, Instant now) {
        if (item == null) throw new IllegalArgumentException("item must not be null");
        if (now == null) throw new IllegalArgumentException("now must not be null");

        // ① 人工覆盖优先
        if (item.statusOverride() != null) {
            return item.statusOverride();
        }

        // ② 已完成：进度满格或已填写实际完成时间
        if (item.progressPercent() >= 100 || item.actualEnd() != null) {
            return DeliveryStatus.COMPLETED;
        }

        // ③ 已延期：未完成且已过计划完成时间
        if (item.plannedEnd() != null && now.isAfter(item.plannedEnd().atZone(ZoneId.systemDefault()).toInstant())) {
            return DeliveryStatus.DELAYED;
        }

        // ④ 进行中：有进度，或已到计划开始时间
        if (item.progressPercent() > 0) {
            return DeliveryStatus.IN_PROGRESS;
        }
        if (item.plannedStart() != null
                && !now.isBefore(item.plannedStart().atZone(ZoneId.systemDefault()).toInstant())) {
            return DeliveryStatus.IN_PROGRESS;
        }

        // ⑤ 未开始
        return DeliveryStatus.NOT_STARTED;
    }
}
