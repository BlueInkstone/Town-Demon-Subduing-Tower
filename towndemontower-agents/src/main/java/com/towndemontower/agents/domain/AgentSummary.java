package com.towndemontower.agents.domain;

import java.time.Instant;
import java.util.Map;

/**
 * [agents] Agent 汇总（自动计算的只读快照）。
 *
 * <p>回答四个问题：
 * <ol>
 *   <li><b>有多少、能执行的有几个</b>——{@code total} / {@code registeredCount} / {@code executableCount}；</li>
 *   <li><b>角色怎么分布</b>——{@code byRole}；</li>
 *   <li><b>能力覆盖够不够</b>——{@code skillUsage}；</li>
 *   <li><b>权限账实是否相符</b>——{@code permissionCoverage}（保留权限的 Agent 中确实持有权限的比例）。</li>
 * </ol>
 */
public final class AgentSummary {

    private final int total;
    private final int activeCount;
    private final int pausedCount;
    private final int suspendedCount;
    private final int retiredCount;
    private final int registeredCount;
    private final int executableCount;
    private final Map<String, Integer> byRole;
    private final Map<String, Integer> skillUsage;
    private final double avgSkillCount;
    private final double avgPermissionCount;
    private final double permissionCoverage;
    private final Instant generatedAt;

    public AgentSummary(int total,
                        int activeCount,
                        int pausedCount,
                        int suspendedCount,
                        int retiredCount,
                        int registeredCount,
                        int executableCount,
                        Map<String, Integer> byRole,
                        Map<String, Integer> skillUsage,
                        double avgSkillCount,
                        double avgPermissionCount,
                        double permissionCoverage,
                        Instant generatedAt) {
        this.total = total;
        this.activeCount = activeCount;
        this.pausedCount = pausedCount;
        this.suspendedCount = suspendedCount;
        this.retiredCount = retiredCount;
        this.registeredCount = registeredCount;
        this.executableCount = executableCount;
        this.byRole = Map.copyOf(byRole);
        this.skillUsage = Map.copyOf(skillUsage);
        this.avgSkillCount = avgSkillCount;
        this.avgPermissionCount = avgPermissionCount;
        this.permissionCoverage = permissionCoverage;
        this.generatedAt = generatedAt;
    }

    /** 全部 Agent 数（含已注销）。 */
    public int total() {
        return total;
    }

    /** ACTIVE 数量。 */
    public int activeCount() {
        return activeCount;
    }

    /** PAUSED 数量。 */
    public int pausedCount() {
        return pausedCount;
    }

    /** SUSPENDED 数量。 */
    public int suspendedCount() {
        return suspendedCount;
    }

    /** RETIRED 数量。 */
    public int retiredCount() {
        return retiredCount;
    }

    /** 未注销数量 = ACTIVE + PAUSED + SUSPENDED。 */
    public int registeredCount() {
        return registeredCount;
    }

    /** 可执行数量：ACTIVE 且至少配了一项技能。 */
    public int executableCount() {
        return executableCount;
    }

    /** 未注销 Agent 的角色分布（角色 code → 数量）。 */
    public Map<String, Integer> byRole() {
        return byRole;
    }

    /** 技能覆盖：技能名 → 掌握该技能的未注销 Agent 数。 */
    public Map<String, Integer> skillUsage() {
        return skillUsage;
    }

    /** 未注销 Agent 的平均技能数（保留一位小数）。 */
    public double avgSkillCount() {
        return avgSkillCount;
    }

    /** 保留权限的 Agent 的平均权限数（保留一位小数）。 */
    public double avgPermissionCount() {
        return avgPermissionCount;
    }

    /** 权限覆盖率：保留权限的 Agent 中持有至少一项权限的比例（0~100，保留一位小数）。 */
    public double permissionCoverage() {
        return permissionCoverage;
    }

    /** 汇总生成时刻。 */
    public Instant generatedAt() {
        return generatedAt;
    }
}
