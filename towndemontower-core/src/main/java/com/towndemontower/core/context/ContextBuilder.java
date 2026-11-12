package com.towndemontower.core.context;
import java.util.stream.Collectors;
import java.util.Arrays;

import com.towndemontower.core.contract.RiskLevel;
import com.towndemontower.core.metrics.GovernanceMetrics;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.prompt.ModePrompts;
import com.towndemontower.core.routing.ModelProfile;
import com.towndemontower.core.routing.ModelRouter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [core] 上下文构建器：把"模式 + 工具白名单 + 任务 + 流水线状态"拼成一次模型调用的提示词。
 *
 * <p><b>缓存友好是硬约束，不是优化项</b>：前缀（角色、输出契约、工具清单）必须逐字节稳定，
 * 动态内容（任务、上一次门禁结果、当前子任务）必须全部排在后面。前缀里只要掺进一个时间戳或
 * 一个随机顺序的工具列表，整段 prompt 缓存就永久失效——而 prompt 缓存命中率在本产品里被当作
 * <b>质量指标</b>（命中率骤降通常意味着系统提示词被谁改动了），不只是成本指标。</p>
 *
 * <p>因此 {@link PromptAssembly#cacheKey} 只对前缀取哈希。命中率由
 * {@link GovernanceMetrics#cacheHitRate()} 暴露；同一进程内见过相同前缀即记为命中。</p>
 *
 * <p>另外两件事也在这里落地：
 * <ul>
 *   <li><b>工具清单 = 模式白名单</b>，模型在提示词里根本看不到其它工具；</li>
 *   <li><b>模型路由记录</b>写进 {@link PromptAssembly#model}，调用方可回填到
 *       {@link PipelineState#modelRouteRecord}，使"这次为什么用强模型"可追溯。</li>
 * </ul>
 */
public class ContextBuilder {

    private final ModelRouter router;
    private final GovernanceMetrics metrics;
    private final Set<String> seenPrefixes = ConcurrentHashMap.newKeySet();

    public ContextBuilder(ModelRouter router) {
        this(router, null);
    }

    public ContextBuilder(ModelRouter router, GovernanceMetrics metrics) {
        this.router = router;
        this.metrics = metrics;
    }

    /**
     * 构建一次调用的提示词。
     *
     * @param mode         当前模式（应由状态机给出，不由调用方指定）
     * @param allowedTools 该模式的工具白名单
     * @param taskBrief    任务简述（动态段）
     * @param state        流水线状态，可为 null
     * @param risk         本次意图的风险等级，可为 null
     */
    public PromptAssembly build(AgentMode mode, Collection<String> allowedTools,
                                String taskBrief, PipelineState state, RiskLevel risk) {
        String prefix = ModePrompts.prefix(mode, allowedTools);
        String cacheKey = sha256(prefix);

        boolean hit = !seenPrefixes.add(cacheKey);
        if (metrics != null) {
            metrics.inc(hit ? GovernanceMetrics.CACHE_HIT : GovernanceMetrics.CACHE_MISS);
        }

        String taskType = (state != null && state.gateResults != null
                && state.gateResults.startsWith("GATE_SCRIPT_FAILED")) ? "format-repair" : null;
        ModelProfile model = router == null ? null : router.route(mode, risk, taskType);

        String dynamic = dynamicSection(mode, taskBrief, state);
        return new PromptAssembly(prefix, dynamic, cacheKey, model,
                new TreeSet<>(allowedTools == null ? Arrays.asList() : allowedTools).stream().collect(Collectors.toList()), hit);
    }

    private String dynamicSection(AgentMode mode, String taskBrief, PipelineState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前阶段】").append(ModePrompts.responsibility(mode)).append('\n');
        sb.append("【任务】").append(taskBrief == null || taskBrief.trim().isEmpty() ? "（未提供）" : taskBrief).append('\n');
        if (state != null) {
            if (state.subTasks != null && state.subTasks.length > 0) {
                sb.append("【子任务】").append(String.join(" / ", state.subTasks)).append('\n');
            }
            if (state.planArtifactRef != null && !state.planArtifactRef.trim().isEmpty()) {
                sb.append("【计划引用】").append(state.planArtifactRef).append('\n');
            }
            if (state.gateResults != null && !state.gateResults.trim().isEmpty()) {
                sb.append("【上一次门禁结果】").append(state.gateResults).append('\n');
            }
            if (state.lastDecision != null && !state.lastDecision.trim().isEmpty()) {
                sb.append("【上一次裁决】").append(state.lastDecision).append('\n');
            }
        }
        return sb.toString();
    }

    /** 前缀哈希（十六进制，取前 32 字符足够区分）。 */
    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
