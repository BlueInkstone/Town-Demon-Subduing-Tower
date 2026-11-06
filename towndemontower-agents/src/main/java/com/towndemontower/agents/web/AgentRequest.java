package com.towndemontower.agents.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [agents/web] 注册 / 改档 / 状态流转请求体。
 *
 * <p>更新语义（部分更新）：字段为 {@code null} 表示"保持原值"；清单字段传空数组表示"清空该清单"。</p>
 *
 * <p><b>清单字段的三态</b>：
 * <ul>
 *   <li>字段不传 / 传 {@code null} —— 注册时套用角色模板；改档时保持原值；</li>
 *   <li>传 {@code []} —— 明确置空（例如收回该 Agent 全部技能）；</li>
 *   <li>传具体值 —— 覆盖。</li>
 * </ul></p>
 */
@Schema(description = "Agent 注册 / 改档请求体")
public class AgentRequest {

    /** 显示名（注册必填，注册表内唯一）。 */
    @Schema(description = "显示名（注册必填，注册表内唯一）", example = "build-runner-01",
            requiredMode = Schema.RequiredMode.REQUIRED)
    public String name;

    /** 角色：PM / ARCHITECT / DEVELOPER / QA / OPS。 */
    @Schema(description = "角色；注册必填", example = "DEVELOPER",
            allowableValues = {"PM", "ARCHITECT", "DEVELOPER", "QA", "OPS"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    public String role;

    /** 技能清单（core 工具名）。null 表示套用角色模板 / 保持原值。 */
    @Schema(description = "可用技能（core 工具名）；不传则注册时套用角色模板、改档时保持原值；传空数组表示清空",
            example = "[\"writeFile\", \"runCompile\"]")
    public List<String> skills;

    /** 权限清单（域:动作）。null 表示套用角色模板 / 保持原值。 */
    @Schema(description = "RBAC 权限；不传则套用角色模板或保持原值；传空数组表示清空",
            example = "[\"fs:write\", \"build:run\"]")
    public List<String> permissions;

    /** 模型档位：STRONG / FAST / SMALL / GUARDIAN。 */
    @Schema(description = "模型档位；不传则套用角色模板或保持原值",
            allowableValues = {"STRONG", "FAST", "SMALL", "GUARDIAN"})
    public String modelTier;

    /** 注册时间（ISO-8601 本地时间）；不传则取当前时刻。 */
    @Schema(description = "注册时间（ISO-8601）；不传取当前时刻", example = "2026-11-02T09:00:00")
    public LocalDateTime registeredAt;

    /** 备注。 */
    @Schema(description = "备注", example = "core 装饰器链与执行器测试")
    public String note;

    /** 仅状态流转接口使用：ACTIVE / PAUSED / SUSPENDED / RETIRED。 */
    @Schema(description = "目标状态（仅状态流转接口使用）",
            allowableValues = {"ACTIVE", "PAUSED", "SUSPENDED", "RETIRED"})
    public String status;
}
