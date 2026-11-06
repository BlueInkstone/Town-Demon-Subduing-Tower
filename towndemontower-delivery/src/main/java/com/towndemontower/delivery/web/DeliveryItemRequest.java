package com.towndemontower.delivery.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * [delivery/web] 交付项新增 / 更新请求体。
 *
 * <p>更新时字段为 null 表示"保持原值"（部分更新语义）。</p>
 *
 * <p>{@code status} 字段的特殊取值：
 * <ul>
 *   <li>null / 空串 —— 新增时表示"自动推导"；更新时表示"保持原值"；</li>
 *   <li>{@code AUTO} —— 清除人工指定的状态，恢复"自动推导"（仅更新有意义）。</li>
 * </ul>
 */
@Schema(description = "交付项新增 / 更新请求体（更新时 null 字段表示保持原值）")
public class DeliveryItemRequest {

    /** 清除人工指定状态、恢复自动推导的指令值。 */
    public static final String CLEAR_STATUS = "AUTO";

    /** 交付阶段名称（新增必填）。 */
    @Schema(description = "交付阶段名称", example = "Phase 1.3 · 交付进度看板", requiredMode = Schema.RequiredMode.REQUIRED)
    public String phaseName;
    /** 负责人（新增必填）。 */
    @Schema(description = "负责人", example = "谢圣", requiredMode = Schema.RequiredMode.REQUIRED)
    public String owner;
    /** 计划开始时间，ISO-8601，如 2026-11-02T09:00:00。 */
    @Schema(description = "计划开始时间（ISO-8601）", example = "2026-11-02T09:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    public LocalDateTime plannedStart;
    /** 计划完成时间，ISO-8601。 */
    @Schema(description = "计划完成时间（ISO-8601）", example = "2026-11-30T18:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    public LocalDateTime plannedEnd;
    /** 实际完成时间，ISO-8601（可空）。 */
    @Schema(description = "实际完成时间（ISO-8601，可空）", example = "2026-11-28T18:00:00")
    public LocalDateTime actualEnd;
    /** 进度百分比 0~100。 */
    @Schema(description = "当前进度百分比，取值 0~100", example = "60", minimum = "0", maximum = "100")
    public Integer progressPercent;
    /** 人工指定状态（可空，空则自动推导）：NOT_STARTED / IN_PROGRESS / DELAYED / COMPLETED；AUTO 表示清除覆盖。 */
    @Schema(description = "人工指定状态；不传或传 AUTO 则由服务端自动推导",
            example = "NOT_STARTED",
            allowableValues = {"NOT_STARTED", "IN_PROGRESS", "DELAYED", "COMPLETED", "AUTO"})
    public String status;

    /**
     * 判断 status 是否为"清除人工覆盖"指令。
     *
     * @param raw 请求中的 status 原始值
     */
    public static boolean isClearStatusDirective(String raw) {
        return raw != null && CLEAR_STATUS.equalsIgnoreCase(raw.trim());
    }
}
