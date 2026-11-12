package com.towndemontower.core.analysis;
import java.util.Arrays;
import java.util.ArrayList;

import com.towndemontower.core.audit.AuditCrypto;

import java.util.List;

/**
 * [core] 方向 C / M1：只读行为报告（docs/36 FR-3、AC-10）。
 *
 * <p>报告只含聚合指标与脱敏标识，<b>不含</b>审计原文、工具参数、owner、凭据。
 * 字段按 docs/35 §6.1 的 wire 约定用 camelCase；零依赖手写 JSON，不引第三方库。</p>
 */
public final class BehaviorReport {

    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";
    public static final String STATUS_CHAIN_INVALID = "CHAIN_INVALID";
    public static final String STATUS_DEGRADED = "DEGRADED";

    public final String status;
    public final String ruleVersion;
    public final String generatedAt;
    public final int windowRecords;
    public final int totalRecords;
    public final int analyzableRecords;
    public final int skippedRecords;
    public final double coverageRate;
    public final boolean chainValid;
    public final boolean evidenceSufficient;
    public final List<AgentBehavior> agents;
    /** 标记结论：仅在链有效且样本充足时才会出现（FR-5）。 */
    public final List<String> findings;
    public final List<String> notes;

    public BehaviorReport(String status, String ruleVersion, String generatedAt,
                          int windowRecords, int totalRecords, int analyzableRecords,
                          int skippedRecords, double coverageRate, boolean chainValid,
                          boolean evidenceSufficient, List<AgentBehavior> agents,
                          List<String> findings, List<String> notes) {
        this.status = status;
        this.ruleVersion = ruleVersion;
        this.generatedAt = generatedAt;
        this.windowRecords = windowRecords;
        this.totalRecords = totalRecords;
        this.analyzableRecords = analyzableRecords;
        this.skippedRecords = skippedRecords;
        this.coverageRate = coverageRate;
        this.chainValid = chainValid;
        this.evidenceSufficient = evidenceSufficient;
        this.agents = new ArrayList<>(agents);
        this.findings = new ArrayList<>(findings);
        this.notes = new ArrayList<>(notes);
    }

    /** 构造一个「无结论」的空报告：默认关闭 / 证据不足 / 退化都走这里。 */
    public static BehaviorReport empty(String status, String ruleVersion, String generatedAt,
                                       AnalyzerConfig config, boolean chainValid, String note) {
        return new BehaviorReport(status, ruleVersion, generatedAt, config.windowRecords,
                0, 0, 0, 0.0, chainValid, false, Arrays.asList(), Arrays.asList(), Arrays.asList(note));
    }

    /**
     * 稳定脱敏：保留前两位 + SHA-256 前缀。既能区分不同 Agent（S-1 场景仍成立），
     * 又不回显原始 ID；复用 core 自带 {@link AuditCrypto#sha256(String)}，不引新依赖。
     */
    public static String mask(String raw) {
        if (raw == null || raw.isEmpty()) return "(unattributed)";
        String digest = AuditCrypto.sha256(raw).substring(0, 8);
        return raw.length() <= 2 ? "*" + digest : raw.substring(0, 2) + "***" + digest;
    }

    /** camelCase JSON；数值用 {@code String.valueOf} 保证跨 locale 稳定（AC-4 可复算）。 */
    public String toJson() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        sb.append("  \"status\": \"").append(esc(status)).append("\",\n");
        sb.append("  \"ruleVersion\": \"").append(esc(ruleVersion)).append("\",\n");
        sb.append("  \"generatedAt\": \"").append(esc(generatedAt)).append("\",\n");
        sb.append("  \"windowRecords\": ").append(windowRecords).append(",\n");
        sb.append("  \"totalRecords\": ").append(totalRecords).append(",\n");
        sb.append("  \"analyzableRecords\": ").append(analyzableRecords).append(",\n");
        sb.append("  \"skippedRecords\": ").append(skippedRecords).append(",\n");
        sb.append("  \"coverageRate\": ").append(String.valueOf(coverageRate)).append(",\n");
        sb.append("  \"chainValid\": ").append(chainValid).append(",\n");
        sb.append("  \"evidenceSufficient\": ").append(evidenceSufficient).append(",\n");
        sb.append("  \"agents\": [");
        for (int i = 0; i < agents.size(); i++) {
            if (i > 0) sb.append(',');
            AgentBehavior a = agents.get(i);
            sb.append("\n    {\"agentId\": \"").append(esc(a.agentId))
                    .append("\", \"samples\": ").append(a.samples)
                    .append(", \"pendingApproval\": ").append(a.pendingApproval)
                    .append(", \"r3Denied\": ").append(a.r3Denied)
                    .append(", \"denied\": ").append(a.denied)
                    .append(", \"denialRate\": ").append(String.valueOf(a.denialRate))
                    .append(", \"maxConsecutivePending\": ").append(a.maxConsecutivePending)
                    .append(", \"flagged\": ").append(a.flagged)
                    .append(", \"reasons\": ").append(strArray(a.reasons, 6)).append('}');
        }
        if (!agents.isEmpty()) sb.append("\n  ");
        sb.append("],\n");
        sb.append("  \"findings\": ").append(strArray(findings, 2)).append(",\n");
        sb.append("  \"notes\": ").append(strArray(notes, 2)).append("\n}\n");
        return sb.toString();
    }

    private static String strArray(List<String> values, int indent) {
        StringBuilder sb = new StringBuilder("[");
        String pad = new String(new char[indent]).replace('\0', ' ');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("\n").append(pad).append("\"").append(esc(values.get(i))).append("\"");
        }
        if (!values.isEmpty()) sb.append("\n").append(new String(new char[Math.max(0, indent - 2)]).replace('\0', ' '));
        return sb.append(']').toString();
    }

    /** JSON 字符串转义（沿用 bypass/SonarIssueReport 的最小集写法）。 */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    /** 单个 Agent 的聚合指标；{@code agentId} 恒为脱敏值。 */
    public static final class AgentBehavior {
        public final String agentId;
        public final int samples;
        public final int pendingApproval;
        public final int r3Denied;
        public final int denied;
        public final double denialRate;
        public final int maxConsecutivePending;
        public final boolean flagged;
        public final List<String> reasons;

        public AgentBehavior(String agentId, int samples, int pendingApproval, int r3Denied,
                             int denied, double denialRate, int maxConsecutivePending,
                             boolean flagged, List<String> reasons) {
            this.agentId = agentId;
            this.samples = samples;
            this.pendingApproval = pendingApproval;
            this.r3Denied = r3Denied;
            this.denied = denied;
            this.denialRate = denialRate;
            this.maxConsecutivePending = maxConsecutivePending;
            this.flagged = flagged;
            this.reasons = new ArrayList<>(reasons);
        }
    }
}
