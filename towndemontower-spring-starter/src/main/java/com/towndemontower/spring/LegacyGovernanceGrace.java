package com.towndemontower.spring;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.metrics.GovernanceMetrics;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * [starter] 旧用法过渡：{@code enabled=false} 从"全部放行"改成"全部拒绝"是**破坏性变更**，
 * 老接入方升级后可能在第一次调用就炸，且看不出为什么。
 *
 * <p>因此保留一个<b>一次性</b>宽限窗口：进程内第一次因为"治理被关闭"而本应被熔断的调用，
 * 不拦——放过、告警、留痕；从第二次起一律拒绝。</p>
 *
 * <p><b>为什么这样设计</b>：</p>
 * <ul>
 *   <li><b>只给一次</b>：宽限是"让你发现配置有问题"，不是"给你继续跑的通道"。
 *       计数器是 {@link AtomicBoolean#compareAndSet}，并发首调也只有一个能过。</li>
 *   <li><b>必须有痕迹</b>：放行前先写审计 {@code LEGACY_GOVERNANCE_GRACE}（工具名 + agent + 原因）
 *       并打 SEVERE 级别日志。没有审计的宽限是后门，不是过渡。</li>
 *   <li><b>重启才重新武装</b>：没有"每天一次""每小时一次"，也不是按工具分别计次。</li>
 *   <li><b>可关闭</b>：{@code towndemontower.legacy-grace=false} 即刻变成硬拒绝。</li>
 *   <li><b>没有审计就不开</b>：{@code audit == null}（手工装配的老用法）时直接不武装——
 *       留不下痕迹的放行一律不做。</li>
 * </ul>
 */
public final class LegacyGovernanceGrace {

    /** 审计动作名。按这个串检索即可查到"哪一次调用是宽限放行的"。 */
    public static final String AUDIT_CODE = "LEGACY_GOVERNANCE_GRACE";

    private static final Logger LOG = Logger.getLogger(LegacyGovernanceGrace.class.getName());

    private final AuditStore audit;
    private final GovernanceMetrics metrics;
    private final boolean armed;
    private final AtomicBoolean consumed = new AtomicBoolean(false);
    private final AtomicInteger allowed = new AtomicInteger();

    public LegacyGovernanceGrace(AuditStore audit, GovernanceMetrics metrics, boolean armed) {
        this.audit = audit;
        this.metrics = metrics;
        // 没有审计通道就不宽限：放行必须可查，否则等价于静默后门
        this.armed = armed && audit != null;
    }

    /**
     * 尝试消耗本次宽限。
     *
     * @return {@code true} 表示这是本进程唯一一次宽限放行（调用方应放行）；
     *         {@code false} 表示宽限已用完 / 未武装（调用方应走熔断拒绝）
     */
    public boolean tryConsume(String tool, String agent, String reason) {
        if (!armed) return false;
        if (!consumed.compareAndSet(false, true)) return false;

        allowed.incrementAndGet();
        String payload = tool + "|agent=" + agent + "|reason=" + reason;
        audit.appendAuto(AUDIT_CODE, payload);
        if (metrics != null) {
            metrics.inc(AUDIT_CODE);
        }
        LOG.severe("[towndemontower] 治理已关闭（enabled=false），本次调用按过渡策略放行一次："
                + payload + "。后续调用将全部拒绝（fail-closed）。请显式设置 "
                + "towndemontower.disabled-behavior=allow 表示「知情弃权」，或重新开启治理。");
        return true;
    }

    /** 是否处于可宽限状态（配置允许 + 有审计通道）。 */
    public boolean armed() {
        return armed;
    }

    /** 宽限是否已被用掉。 */
    public boolean consumed() {
        return consumed.get();
    }

    /** 本进程已宽限放行的次数（正常为 0 或 1）。 */
    public int allowedCount() {
        return allowed.get();
    }
}
