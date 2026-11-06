package com.towndemontower.agents.web;

import com.towndemontower.agents.domain.AgentRole;
import com.towndemontower.agents.domain.AgentSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [agents/web] 注册表汇总视图。
 */
@Schema(description = "Agent 汇总：数量、状态分布、角色分布、技能覆盖与权限覆盖率")
public class AgentSummaryView {

    @Schema(description = "Agent 总数（含已注销）")
    public int total;
    @Schema(description = "ACTIVE 数量")
    public int activeCount;
    @Schema(description = "PAUSED 数量")
    public int pausedCount;
    @Schema(description = "SUSPENDED 数量")
    public int suspendedCount;
    @Schema(description = "RETIRED 数量")
    public int retiredCount;
    @Schema(description = "未注销数量（ACTIVE+PAUSED+SUSPENDED）")
    public int registeredCount;
    @Schema(description = "可执行数量（ACTIVE 且有技能）")
    public int executableCount;
    @Schema(description = "未注销 Agent 的角色分布，按角色定义顺序排列")
    public List<Map<String, Object>> byRole;
    @Schema(description = "技能覆盖：技能名 → 掌握该技能的未注销 Agent 数")
    public Map<String, Integer> skillUsage;
    @Schema(description = "未注销 Agent 的平均技能数")
    public double avgSkillCount;
    @Schema(description = "保留权限的 Agent 的平均权限数")
    public double avgPermissionCount;
    @Schema(description = "权限覆盖率（%）：保留权限的 Agent 中持有至少一项生效权限的比例")
    public double permissionCoverage;
    @Schema(description = "汇总生成时刻")
    public Instant generatedAt;

    public static AgentSummaryView from(AgentSummary summary) {
        AgentSummaryView v = new AgentSummaryView();
        v.total = summary.total();
        v.activeCount = summary.activeCount();
        v.pausedCount = summary.pausedCount();
        v.suspendedCount = summary.suspendedCount();
        v.retiredCount = summary.retiredCount();
        v.registeredCount = summary.registeredCount();
        v.executableCount = summary.executableCount();
        v.skillUsage = summary.skillUsage();
        v.avgSkillCount = summary.avgSkillCount();
        v.avgPermissionCount = summary.avgPermissionCount();
        v.permissionCoverage = summary.permissionCoverage();
        v.generatedAt = summary.generatedAt();

        // 按角色定义顺序输出（含 0 个的角色），前端无需自行排序或补零
        List<Map<String, Object>> roles = new ArrayList<>();
        for (AgentRole r : AgentRole.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.code());
            m.put("label", r.label());
            m.put("count", summary.byRole().getOrDefault(r.code(), 0));
            roles.add(m);
        }
        v.byRole = roles;
        return v;
    }
}
