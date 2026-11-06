package com.towndemontower.agents.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * [agents] 汇总计算（纯函数）。
 *
 * <p>口径说明：
 * <ul>
 *   <li>角色分布、平均技能数<b>只统计未注销 Agent</b>（ACTIVE + PAUSED + SUSPENDED）。
 *       已注销的不进分母——否则每注销一个，平均能力就会被永久拉低；</li>
 *   <li>权限相关指标（平均权限数、权限覆盖率）的分母是<b>保留权限的 Agent</b>（ACTIVE + PAUSED）。
 *       SUSPENDED 的档案里仍留着权限配置但已被回收，把它们算进分母会得到
 *       "覆盖率 100% 但实际没人有权"的假象；</li>
 *   <li>"可执行"要求同时满足 ACTIVE 与至少一项技能：挂着角色但没有任何工具使用权的 Agent，
 *       派了任务也执行不了；</li>
 *   <li>平均值与覆盖率统一保留一位小数，避免出现 33.33333% 这类噪声。</li>
 * </ul>
 */
public final class AgentSummaryCalculator {

    private AgentSummaryCalculator() {
    }

    public static AgentSummary calculate(List<Agent> agents, Instant now) {
        List<Agent> all = agents == null ? List.of() : agents;

        int active = 0, paused = 0, suspended = 0, retired = 0;
        int registered = 0, executable = 0, withPerms = 0, retainsBase = 0;
        int skillSum = 0, permSum = 0;
        Map<String, Integer> byRole = new LinkedHashMap<>();
        Map<String, Integer> skillUsage = new TreeMap<>();

        for (Agent a : all) {
            switch (a.status()) {
                case ACTIVE -> active++;
                case PAUSED -> paused++;
                case SUSPENDED -> suspended++;
                case RETIRED -> retired++;
            }
            if (!a.status().isRegistered()) continue;

            registered++;
            if (a.canAct() && a.skillCount() > 0) executable++;
            skillSum += a.skillCount();
            byRole.merge(a.role().code(), 1, Integer::sum);
            for (String s : a.skills()) {
                skillUsage.merge(s, 1, Integer::sum);
            }

            // 权限指标只统计"保留权限"的状态：SUSPENDED 与 RETIRED 的权限已被回收
            if (a.retainsPermissions()) {
                retainsBase++;
                permSum += a.permissionCount();
                if (a.permissionCount() > 0) withPerms++;
            }
        }

        return new AgentSummary(
                all.size(), active, paused, suspended, retired,
                registered, executable,
                byRole, skillUsage,
                round1(registered == 0 ? 0 : (double) skillSum / registered),
                round1(retainsBase == 0 ? 0 : (double) permSum / retainsBase),
                round1(retainsBase == 0 ? 0 : (double) withPerms * 100 / retainsBase),
                now == null ? Instant.now() : now);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
