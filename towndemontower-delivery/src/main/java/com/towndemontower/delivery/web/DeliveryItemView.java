package com.towndemontower.delivery.web;

import com.towndemontower.delivery.domain.DeliveryItem;
import com.towndemontower.delivery.domain.DeliveryStatus;
import com.towndemontower.delivery.domain.DeliveryStatusResolver;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * [delivery/web] 交付项对外 JSON 视图。
 *
 * <p>与领域模型解耦：领域状态是"推导"的，视图里直接给出推导结果 {@code status}
 * 与中文标签 {@code statusLabel}，前端无需自行推导。</p>
 */
@Schema(description = "交付项视图（含服务端推导后的状态）")
public class DeliveryItemView {

    @Schema(description = "交付项 id", example = "DLV-0001")
    public String id;
    @Schema(description = "交付阶段名称", example = "Phase 1.3 · 交付进度看板")
    public String phaseName;
    @Schema(description = "负责人", example = "谢圣")
    public String owner;
    @Schema(description = "计划开始时间（ISO-8601）")
    public LocalDateTime plannedStart;
    @Schema(description = "计划完成时间（ISO-8601）")
    public LocalDateTime plannedEnd;
    @Schema(description = "实际完成时间（ISO-8601，未完成时为 null）")
    public LocalDateTime actualEnd;
    @Schema(description = "当前进度百分比", example = "60")
    public int progressPercent;
    /** 生效状态（枚举名）。 */
    @Schema(description = "生效状态（服务端实时推导）",
            allowableValues = {"NOT_STARTED", "IN_PROGRESS", "DELAYED", "COMPLETED"})
    public String status;
    /** 生效状态中文标签。 */
    @Schema(description = "生效状态中文标签", example = "进行中")
    public String statusLabel;
    /** 是否存在人工覆盖的状态。 */
    @Schema(description = "是否存在人工覆盖的状态")
    public boolean manualStatus;
    /** 人工覆盖的原始状态（枚举名）；无覆盖时为 null，供前端回显与"恢复自动推导"。 */
    @Schema(description = "人工覆盖的原始状态；无覆盖时为 null",
            allowableValues = {"NOT_STARTED", "IN_PROGRESS", "DELAYED", "COMPLETED"})
    public String statusOverride;

    /** 按给定时刻推导状态后构造视图。 */
    public static DeliveryItemView from(DeliveryItem item, Instant now) {
        DeliveryItemView v = new DeliveryItemView();
        v.id = item.id();
        v.phaseName = item.phaseName();
        v.owner = item.owner();
        v.plannedStart = item.plannedStart();
        v.plannedEnd = item.plannedEnd();
        v.actualEnd = item.actualEnd();
        v.progressPercent = item.progressPercent();
        DeliveryStatus st = DeliveryStatusResolver.resolve(item, now);
        v.status = st.name();
        v.statusLabel = st.label();
        v.manualStatus = item.statusOverride() != null;
        v.statusOverride = item.statusOverride() == null ? null : item.statusOverride().name();
        return v;
    }
}
