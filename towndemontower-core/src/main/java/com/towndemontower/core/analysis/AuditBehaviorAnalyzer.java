package com.towndemontower.core.analysis;

import com.towndemontower.core.audit.AuditRecord;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.TimeProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [core] 方向 C / M1：只读行为分析器（docs/36 FR-1 ~ FR-6）。
 *
 * <p>纯函数：输入 {@link AuditRecord} 列表与链校验结果，输出 {@link BehaviorReport}。
 * <b>不写审计、不改权限、不改模式、不改闸门裁决</b>；这是 AC-2 的硬约束。</p>
 *
 * <p>统计口径只认 Java 规范码（docs/35 §6.3）：
 * {@code PENDING_APPROVAL} 计「转人工」、{@code R3_DENIED} 计「高危拒绝」；
 * 其余非 {@code OK} 码计「拒绝」，与 {@code AuditDecorator} 的指标口径一致。
 * SDK 别名（{@code APPROVAL_REQUIRED} / {@code RISK_REJECTED} 等）只会被算作普通拒绝，
 * <b>不会</b>混进这两个专项计数 —— 这正是 docs/36 L-2 要求的「禁止隐式把别名码混算」。</p>
 */
public final class AuditBehaviorAnalyzer {

    public static final String RULE_VERSION = "M1-1";

    private static final String ACTION_SKILL_INVOKED = "SKILL_INVOKED";
    /** 成功码在 Java core 里是 SkillResult.success 的字面量，未收进 ErrorCodes。 */
    private static final String CODE_OK = "OK";
    private static final String UNATTRIBUTED = "(unattributed)";

    private final AnalyzerConfig config;
    private final TimeProvider time;

    public AuditBehaviorAnalyzer(AnalyzerConfig config, TimeProvider time) {
        if (config == null || time == null)
            throw new IllegalArgumentException("config 与 time 均不能为空");
        this.config = config;
        this.time = time;
    }

    /** AC-1：默认关闭时不读审计内容、不产出任何结论。 */
    public BehaviorReport analyze(List<AuditRecord> records, boolean chainValid) {
        return analyze(records, chainValid, null);
    }

    /**
     * @param chainProblem 链校验失败的脱敏原因（来自 {@link AuditLogReader}），可为 null
     */
    public BehaviorReport analyze(List<AuditRecord> records, boolean chainValid, String chainProblem) {
        String now = time.now().toString();
        if (!config.enabled) {
            return BehaviorReport.empty(BehaviorReport.STATUS_DISABLED, RULE_VERSION, now,
                    config, chainValid, "分析器默认关闭；未显式启用，不产生任何结论");
        }
        try {
            return doAnalyze(records, chainValid, chainProblem, now);
        } catch (RuntimeException ex) {
            // FR-6 / AC-6：失败退化。只记异常类型，绝不回显异常文本（可能含审计原文）
            return BehaviorReport.empty(BehaviorReport.STATUS_DEGRADED, RULE_VERSION, now,
                    config, chainValid, "分析器内部异常：" + ex.getClass().getSimpleName()
                            + "；本次不产出结论，治理链裁决不受影响");
        }
    }

    private BehaviorReport doAnalyze(List<AuditRecord> records, boolean chainValid,
                                     String chainProblem, String now) {
        if (records == null) {
            return BehaviorReport.empty(BehaviorReport.STATUS_DEGRADED, RULE_VERSION, now,
                    config, chainValid, "审计记录不可用；本次不产出结论");
        }

        // 窗口按「最近 N 条」：Java AuditRecord 无时间戳，见 AnalyzerConfig.windowRecords
        int from = Math.max(0, records.size() - config.windowRecords);
        Map<String, Acc> byAgent = new LinkedHashMap<>();
        int analyzable = 0;
        int skipped = 0;
        for (int i = from; i < records.size(); i++) {
            Parsed p = parse(records.get(i));
            if (p == null) {
                skipped++;
                continue;
            }
            analyzable++;
            Acc a = byAgent.computeIfAbsent(p.agentId, k -> new Acc(k));
            a.samples++;
            if (ErrorCodes.PENDING_APPROVAL.equals(p.code)) {
                a.pendingApproval++;
                a.streak++;
                a.maxConsecutivePending = Math.max(a.maxConsecutivePending, a.streak);
            } else {
                a.streak = 0;
            }
            if (ErrorCodes.R3_DENIED.equals(p.code)) a.r3Denied++;
            // 与 AuditDecorator 一致：PENDING_APPROVAL 单独计，不算拒绝；其余非 OK 都算拒绝
            if (!CODE_OK.equals(p.code) && !ErrorCodes.PENDING_APPROVAL.equals(p.code)) a.denied++;
        }

        int total = records.size() - from;
        double coverage = total == 0 ? 0.0 : (double) analyzable / total;
        boolean sufficient = chainValid && analyzable >= config.minSamples;
        String status = !chainValid ? BehaviorReport.STATUS_CHAIN_INVALID
                : (sufficient ? BehaviorReport.STATUS_OK
                              : BehaviorReport.STATUS_INSUFFICIENT_EVIDENCE);

        List<Acc> sorted = new ArrayList<>(byAgent.values());
        sorted.sort(Comparator.comparing(a -> a.agentId)); // 保证报告确定性（AC-4 / NFR-8）

        List<BehaviorReport.AgentBehavior> agents = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        for (Acc a : sorted) {
            double rate = a.samples == 0 ? 0.0 : (double) a.denied / a.samples;
            String masked = BehaviorReport.mask(a.agentId);
            List<String> reasons = new ArrayList<>();
            boolean flagged = false;
            // FR-5：链无效 / 样本不足 / 单 Agent 样本太少 → 一律不标记、不产出降权结论
            if (chainValid && sufficient && a.samples >= config.minSamples) {
                if (a.pendingApproval >= config.pendingApprovalThreshold) {
                    reasons.add("PENDING_APPROVAL " + a.pendingApproval + " 次（阈值 "
                            + config.pendingApprovalThreshold + "）");
                    flagged = true;
                }
                if (a.r3Denied >= config.r3DeniedThreshold) {
                    reasons.add("R3_DENIED " + a.r3Denied + " 次（阈值 "
                            + config.r3DeniedThreshold + "）");
                    flagged = true;
                }
                if (rate >= config.denialRateThreshold) {
                    reasons.add("拒绝率 " + String.valueOf(rate) + "（阈值 "
                            + String.valueOf(config.denialRateThreshold) + "）");
                    flagged = true;
                }
            }
            agents.add(new BehaviorReport.AgentBehavior(masked, a.samples, a.pendingApproval,
                    a.r3Denied, a.denied, rate, a.maxConsecutivePending, flagged, reasons));
            if (flagged) {
                // findings 只含脱敏标识与聚合数，绝不带 payload / owner / 参数
                findings.add("agent " + masked + " 需要人工关注：" + String.join("；", reasons));
            }
        }

        List<String> notes = new ArrayList<>();
        notes.add("只认 Java 规范码；SDK 别名不计入 PENDING_APPROVAL / R3_DENIED 专项统计");
        notes.add("窗口按最近 " + config.windowRecords + " 条记录：Java 审计记录不含时间戳");
        notes.add("报告不含审计原文、参数值与凭据；agentId 已脱敏");
        notes.add("链校验失败或样本不足时不产出任何降权结论");
        notes.add("M1 为纯只读离线分析器，不写审计；退化仅体现在报告状态与 CLI 输出（AC-6 的审计事件留待 M2）");
        if (skipped > 0) {
            notes.add(skipped + " 条记录非 SKILL_INVOKED 或格式不可解析，已计入覆盖率分母但不参与指标");
        }
        if (chainProblem != null) notes.add("链校验失败：" + chainProblem);

        return new BehaviorReport(status, RULE_VERSION, now, config.windowRecords, total,
                analyzable, skipped, coverage, chainValid, sufficient, agents, findings, notes);
    }

    /**
     * 解析 {@code AuditDecorator} 写入的 payload：
     * {@code tool|code|task=...|mode=...|agent=...|owner=...}。
     * 其它 action 或形状不符的记录一律跳过（计入覆盖率分母），不猜测、不抛异常。
     */
    private static Parsed parse(AuditRecord r) {
        if (r == null || !ACTION_SKILL_INVOKED.equals(r.action) || r.payload == null) return null;
        String[] parts = r.payload.split("\\|", -1);
        if (parts.length < 2) return null;
        String code = parts[1].trim();
        if (code.isEmpty()) return null;
        String agent = UNATTRIBUTED;
        // 从第三段起找 agent=；owner= 与参数值一律不进报告
        for (int i = 2; i < parts.length; i++) {
            if (parts[i].startsWith("agent=")) {
                String v = parts[i].substring(6).trim();
                if (!v.isEmpty()) agent = v;
                break;
            }
        }
        return new Parsed(agent, code);
    }

    private static final class Parsed {
        final String agentId;
        final String code;

        Parsed(String agentId, String code) {
            this.agentId = agentId;
            this.code = code;
        }
    }

    private static final class Acc {
        final String agentId;
        int samples;
        int pendingApproval;
        int r3Denied;
        int denied;
        int streak;
        int maxConsecutivePending;

        Acc(String agentId) {
            this.agentId = agentId;
        }
    }
}
