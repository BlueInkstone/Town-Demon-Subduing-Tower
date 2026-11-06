package com.towndemontower.agents.web;

import com.towndemontower.agents.domain.Agent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * [agents/web] Agent 对外 JSON 视图。
 *
 * <p>同时给出两组权限：
 * <ul>
 *   <li>{@code permissions} —— 档案里的<b>应然</b>权限（配置了什么）；</li>
 *   <li>{@code effectivePermissions} —— RBAC 中的<b>实然</b>权限（现在真正生效什么）。</li>
 * </ul>
 * 停用 / 已注销 Agent 的"应然"保留、"实然"为空，是正常现象；
 * 保留权限的状态下两者不一致则说明授权同步出了问题，用 {@code permissionsInSync} 直接标出。</p>
 */
@Schema(description = "Agent 视图（含应然/实然权限与状态）")
public class AgentView {

    @Schema(description = "Agent ID", example = "AGT-0003")
    public String id;
    @Schema(description = "显示名", example = "build-runner-01")
    public String name;
    @Schema(description = "角色枚举名", allowableValues = {"PM", "ARCHITECT", "DEVELOPER", "QA", "OPS"})
    public String role;
    @Schema(description = "角色中文名", example = "开发工程师")
    public String roleLabel;
    @Schema(description = "角色短代码", example = "DEV")
    public String roleCode;
    @Schema(description = "状态枚举名", allowableValues = {"ACTIVE", "PAUSED", "SUSPENDED", "RETIRED"})
    public String status;
    @Schema(description = "状态中文名", example = "可用")
    public String statusLabel;
    @Schema(description = "当前是否允许执行任务")
    public boolean canAct;
    @Schema(description = "档案中的技能清单")
    public List<String> skills;
    @Schema(description = "档案中的应然权限")
    public List<String> permissions;
    @Schema(description = "RBAC 中实际生效的权限")
    public List<String> effectivePermissions;
    @Schema(description = "应然与实然权限是否一致")
    public boolean permissionsInSync;
    @Schema(description = "模型档位", allowableValues = {"STRONG", "FAST", "SMALL", "GUARDIAN"})
    public String modelTier;
    @Schema(description = "模型档位中文名", example = "快速")
    public String modelTierLabel;
    @Schema(description = "注册时间")
    public LocalDateTime registeredAt;
    @Schema(description = "注册天数", example = "0")
    public long registeredDays;
    @Schema(description = "备注")
    public String note;
    @Schema(description = "创建时刻")
    public Instant createdAt;
    @Schema(description = "最后变更时刻")
    public Instant updatedAt;

    /**
     * 构造视图。
     *
     * @param agent     Agent 档案
     * @param now       当前时刻（用于计算注册天数）
     * @param effective RBAC 中实际生效的权限
     */
    public static AgentView from(Agent agent, Instant now, Set<String> effective) {
        AgentView v = new AgentView();
        v.id = agent.id();
        v.name = agent.name();
        v.role = agent.role().name();
        v.roleLabel = agent.role().label();
        v.roleCode = agent.role().code();
        v.status = agent.status().name();
        v.statusLabel = agent.status().label();
        v.canAct = agent.canAct();
        v.skills = new ArrayList<>(agent.skills());
        v.permissions = new ArrayList<>(agent.permissions());
        v.effectivePermissions = effective == null ? List.of() : new ArrayList<>(effective);
        // 保留权限的状态要求账实一致；停用/已注销则要求实然权限已清空
        v.permissionsInSync = agent.retainsPermissions()
                ? Set.copyOf(v.effectivePermissions).equals(Set.copyOf(v.permissions))
                : v.effectivePermissions.isEmpty();
        v.modelTier = agent.modelTier().name();
        v.modelTierLabel = agent.modelTier().label();
        v.registeredAt = agent.registeredAt();
        v.registeredDays = agent.registeredAt() == null || now == null
                ? 0
                : Math.max(0, ChronoUnit.DAYS.between(
                        agent.registeredAt(), LocalDateTime.ofInstant(now, ZoneId.systemDefault())));
        v.note = agent.note();
        v.createdAt = agent.createdAt();
        v.updatedAt = agent.updatedAt();
        return v;
    }
}
