package com.towndemontower.core.approval;
import java.util.Arrays;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * [core] 结构化的执行计划（V1.14）。
 *
 * <p>v1.13 的计划只有一段摘要，闸门只能按 {@code (agent, task)} 放行——批了一个工具，
 * 同一任务里的其他工具也一起放行。v1.14 允许计划列出<b>步骤</b>，每一步可点名工具；
 * 只要计划列出了工具，闸门就只放行这些工具（{@link #coversTool}）。</p>
 *
 * <p><b>兼容旧语义</b>：没有任何步骤（{@link #text}）时 {@code coversTool} 恒为
 * {@code true}，行为与 v1.13 完全一致。这样存量调用方不用改，想要收窄的调用方显式传步骤即可。</p>
 */
public final class PlanArtifact {

    /** 计划摘要（人读；可能含敏感信息，审计链不写它）。 */
    public final String summary;
    /** 执行步骤；空表表示"只有摘要、不限定工具"（v1.13 旧语义）。 */
    public final List<PlanStep> steps;

    private PlanArtifact(String summary, List<PlanStep> steps) {
        this.summary = summary == null ? "" : summary.trim();
        List<PlanStep> copy = new ArrayList<>();
        if (steps != null) {
            for (PlanStep s : steps) {
                if (s != null) copy.add(s);
            }
        }
        this.steps = Collections.unmodifiableList(copy);
    }

    /** 只有摘要的旧格式计划。 */
    public static PlanArtifact text(String summary) {
        return new PlanArtifact(summary, Arrays.asList());
    }

    /** 带步骤的结构化计划（步骤里的明确工具名会收窄放行范围）。 */
    public static PlanArtifact of(String summary, List<PlanStep> steps) {
        return new PlanArtifact(summary, steps);
    }

    /** 计划里点名的工具（去重、保留声明顺序）。 */
    public Set<String> tools() {
        Set<String> out = new LinkedHashSet<>();
        for (PlanStep s : steps) {
            if (!s.tool.isEmpty()) out.add(s.tool);
        }
        return Collections.unmodifiableSet(out);
    }

    /** 是否收窄了工具范围（没有任何明确工具名时退回任务级语义）。 */
    public boolean hasToolScope() {
        return !tools().isEmpty();
    }

    /**
     * 这份计划是否覆盖某工具。
     *
     * <p>没有工具范围 → 覆盖一切（旧语义）；有范围 → 必须在名单里。
     * 只在某个步骤里写了意图、没写工具名，<b>不会</b>顺带放行任何工具。</p>
     */
    public boolean coversTool(String toolName) {
        if (toolName == null || toolName.trim().isEmpty()) return false;
        Set<String> scope = tools();
        if (scope.isEmpty()) return true;
        return scope.contains(toolName.trim());
    }

    /** 人类可读的工具范围，用于拒绝文案。 */
    public String describeTools() {
        Set<String> scope = tools();
        return scope.isEmpty() ? "（未限定，按任务级放行）" : String.join(", ", scope);
    }

    @Override
    public String toString() {
        return "PlanArtifact{summary=" + summary + ", steps=" + steps.size()
                + ", tools=" + describeTools() + "}";
    }
}
