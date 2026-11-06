package com.towndemontower.delivery.domain;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * [delivery] 交付项（一个交付阶段）。不可变值对象，字段与看板表格一一对应。
 *
 * <p>字段：
 * <ul>
 *   <li>{@code id} —— 交付项唯一标识；</li>
 *   <li>{@code phaseName} —— 交付阶段名称（如 "Phase 1.3 交付看板"）；</li>
 *   <li>{@code owner} —— 负责人；</li>
 *   <li>{@code plannedStart} —— 计划开始时间；</li>
 *   <li>{@code plannedEnd} —— 计划完成时间；</li>
 *   <li>{@code actualEnd} —— 实际完成时间（可空，未完成时为空）；</li>
 *   <li>{@code progressPercent} —— 当前进度百分比，取值 0~100；</li>
 *   <li>{@code statusOverride} —— 人工指定状态（可空，为空时按规则自动推导）。</li>
 * </ul>
 *
 * <p>{@code status} 不是存储字段而是<b>推导字段</b>：由
 * {@link DeliveryStatusResolver#resolve(DeliveryItem, Instant)} 结合"当前时间"算出。
 * 这样同一份数据在不同时刻会自然呈现不同状态（例如到期未完成即"已延期"），
 * 不依赖后台定时任务去刷新状态字段。</p>
 */
public final class DeliveryItem {

    private final String id;
    private final String phaseName;
    private final String owner;
    private final LocalDateTime plannedStart;
    private final LocalDateTime plannedEnd;
    private final LocalDateTime actualEnd;
    private final int progressPercent;
    private final DeliveryStatus statusOverride;

    public DeliveryItem(String id,
                         String phaseName,
                         String owner,
                         LocalDateTime plannedStart,
                         LocalDateTime plannedEnd,
                         LocalDateTime actualEnd,
                         int progressPercent,
                         DeliveryStatus statusOverride) {
        this.id = id;
        this.phaseName = phaseName;
        this.owner = owner;
        this.plannedStart = plannedStart;
        this.plannedEnd = plannedEnd;
        this.actualEnd = actualEnd;
        this.progressPercent = progressPercent;
        this.statusOverride = statusOverride;
    }

    /** 由创建参数构造（新交付项，id 由服务层生成，状态默认自动推导）。 */
    public static DeliveryItem of(String phaseName,
                                   String owner,
                                   LocalDateTime plannedStart,
                                   LocalDateTime plannedEnd,
                                   LocalDateTime actualEnd,
                                   int progressPercent) {
        return new DeliveryItem(null, phaseName, owner, plannedStart, plannedEnd,
                actualEnd, progressPercent, null);
    }

    public String id() {
        return id;
    }

    public String phaseName() {
        return phaseName;
    }

    public String owner() {
        return owner;
    }

    public LocalDateTime plannedStart() {
        return plannedStart;
    }

    public LocalDateTime plannedEnd() {
        return plannedEnd;
    }

    public LocalDateTime actualEnd() {
        return actualEnd;
    }

    public int progressPercent() {
        return progressPercent;
    }

    /** 人工覆盖状态；null 表示按规则自动推导。 */
    public DeliveryStatus statusOverride() {
        return statusOverride;
    }

    /** 当前生效状态：人工覆盖优先，否则按规则推导。 */
    public DeliveryStatus status(Instant now) {
        return DeliveryStatusResolver.resolve(this, now);
    }

    /**
     * 返回替换指定字段后的新实例（不可变语义，便于安全更新）。null 表示"保持原值"。
     *
     * <p><b>注意</b>：{@code statusOverride} 为 null 时同样表示"保持原值"，
     * 因此本方法<b>无法清除</b>人工指定的状态。清除请用 {@link #withStatusOverride(DeliveryStatus)}
     * 显式传入 null。</p>
     */
    public DeliveryItem with(String phaseName,
                              String owner,
                              LocalDateTime plannedStart,
                              LocalDateTime plannedEnd,
                              LocalDateTime actualEnd,
                              Integer progressPercent,
                              DeliveryStatus statusOverride) {
        return new DeliveryItem(
                id,
                phaseName != null ? phaseName : this.phaseName,
                owner != null ? owner : this.owner,
                plannedStart != null ? plannedStart : this.plannedStart,
                plannedEnd != null ? plannedEnd : this.plannedEnd,
                actualEnd != null ? actualEnd : this.actualEnd,
                progressPercent != null ? progressPercent : this.progressPercent,
                statusOverride != null ? statusOverride : this.statusOverride);
    }

    /**
     * 返回替换人工覆盖状态后的新实例。
     *
     * @param newOverride 新的覆盖状态；传 null 表示<b>清除</b>人工覆盖、恢复自动推导
     */
    public DeliveryItem withStatusOverride(DeliveryStatus newOverride) {
        return new DeliveryItem(id, phaseName, owner, plannedStart, plannedEnd,
                actualEnd, progressPercent, newOverride);
    }

    /** 返回带指定 id 的新实例（供服务层落库时补齐 id）。 */
    public DeliveryItem withId(String newId) {
        return new DeliveryItem(newId, phaseName, owner, plannedStart, plannedEnd,
                actualEnd, progressPercent, statusOverride);
    }

    @Override
    public String toString() {
        return "DeliveryItem{id=" + id
                + ", phase=" + phaseName
                + ", owner=" + owner
                + ", progress=" + progressPercent
                + "%}";
    }
}
