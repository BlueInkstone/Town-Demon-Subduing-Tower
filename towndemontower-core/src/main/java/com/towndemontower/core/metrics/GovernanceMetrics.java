package com.towndemontower.core.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [core] 治理指标计数器（纯内存、零依赖）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>只记录"事实"，不记录"判断"</b>：放行/拒绝/待审批是事实，可直接计数；
 *       而"这次拒绝是不是错的"只有人或下游评测能判定，因此
 *       {@link #FALSE_REJECTION} / {@link #MIS_EXECUTION} 提供<b>外部标注</b>入口，
 *       不在这里猜。</li>
 *   <li><b>错误拒绝率</b> = 被标注为误拒的次数 / 总拒绝次数。要求先把被拒的调用
 *       留存（审计里有 code），再人工/评测回填，否则分母有、分子永远为 0。</li>
 *   <li>所有计数器都以错误码/事件名作为 key，与 {@code ErrorCodes} 保持同名，
 *       避免"指标名"与"错误码"两套词表。</li>
 * </ul>
 */
public class GovernanceMetrics {

    /** 进入治理链的调用总数（含放行/拒绝/待审批）。 */
    public static final String TOTAL = "total";
    /** 被拒绝的调用数（不含待审批）。 */
    public static final String DENIED = "denied";
    /** 因 R2 转入人工审批的调用数（"过度保守"的分母）。 */
    public static final String PENDING_APPROVAL = "pendingApproval";
    /** 审批通过数。 */
    public static final String APPROVED = "approved";
    /** 审批超时（默认拒绝）数。 */
    public static final String APPROVAL_EXPIRED = "approvalExpired";

    /** 外部标注：被误拒（本该放行却拒了）。 */
    public static final String FALSE_REJECTION = "falseRejection";
    /** 外部标注：误执行（本该拒绝却执行了）。 */
    public static final String MIS_EXECUTION = "misExecution";

    /** 提示词前缀缓存命中/未命中。 */
    public static final String CACHE_HIT = "promptCacheHit";
    public static final String CACHE_MISS = "promptCacheMiss";
    /** 调用方试图用参数覆盖已被状态机固定的模式（应恒为 0）。 */
    public static final String MODE_OVERRIDE_IGNORED = "modeOverrideIgnored";
    /** 快照恢复次数。 */
    public static final String SNAPSHOT_RESTORED = "SNAPSHOT_RESTORED";
    /** 非受治理调用（无通行令牌，应恒为 0）。 */
    public static final String UNGOVERNED_INVOCATION = "UNGOVERNED_INVOCATION";
    /**
     * 显式弃权发牌次数（V1.11）：治理被配置成关闭且选择放行时，切面会开一个弃权作用域。
     *
     * <p>与 {@link #UNGOVERNED_INVOCATION} 的区别：前者是"配置上允许不做治理"，
     * 后者是"本该受治理却被绕过"。两者都不该长期非零，但需要分开计数——
     * 先把它们混成一个数，就再也分不清"是运维关的"还是"有人绕的"。</p>
     */
    public static final String EXPLICIT_BYPASS = "explicitBypass";

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public void inc(String key) {
        inc(key, 1);
    }

    public void inc(String key, long delta) {
        if (key == null || key.isEmpty()) return;
        counters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
    }

    public long get(String key) {
        AtomicLong v = counters.get(key);
        return v == null ? 0 : v.get();
    }

    /** 稳定排序的快照，便于稳定输出与断言。 */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counters.keySet().stream().sorted().forEach(k -> out.put(k, counters.get(k).get()));
        return Collections.unmodifiableMap(out);
    }

    /** 拒绝率 = 拒绝 / 总调用。 */
    public double denialRate() {
        return ratio(get(DENIED), get(TOTAL));
    }

    /** 错误拒绝率 = 被标注误拒 / 拒绝总数。没有标注时恒为 0（不猜）。 */
    public double falseRejectionRate() {
        return ratio(get(FALSE_REJECTION), get(DENIED));
    }

    /** 过度保守率 = 转人工审批 / 总调用。 */
    public double overConservatismRate() {
        return ratio(get(PENDING_APPROVAL), get(TOTAL));
    }

    /** 误执行率 = 被标注误执行 / 已执行数（执行数 = 总数 - 拒绝 - 待审批）。 */
    public double misExecutionRate() {
        long executed = Math.max(0, get(TOTAL) - get(DENIED) - get(PENDING_APPROVAL));
        return ratio(get(MIS_EXECUTION), executed);
    }

    /** 提示词前缀缓存命中率。 */
    public double cacheHitRate() {
        long hit = get(CACHE_HIT);
        long miss = get(CACHE_MISS);
        return ratio(hit, hit + miss);
    }

    public void reset() {
        counters.clear();
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) return 0.0;
        return Math.round(numerator * 1000.0 / denominator) / 10.0;
    }
}
