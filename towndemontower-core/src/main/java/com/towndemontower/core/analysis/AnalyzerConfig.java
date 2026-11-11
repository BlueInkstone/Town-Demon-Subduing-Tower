package com.towndemontower.core.analysis;

/**
 * [core] 方向 C / M1：只读行为分析器的规则配置（docs/36 FR-4）。
 *
 * <p><b>默认关闭</b>：{@link #defaults()} 的 {@code enabled=false}；分析器在该状态下
 * 不产出结论、不读取审计内容、不写任何文件。框架内没有 Bean、没有定时任务、没有端点，
 * 因此「不调用」与「未开启」是同一件事（NFR-10）。离线 CLI 会显式调用
 * {@link #withEnabled(boolean)} —— 运行 CLI 本身就是这次显式启用。</p>
 *
 * <p>不可变：{@code withXxx} 返回新实例，避免多处共享可变配置。</p>
 */
public final class AnalyzerConfig {

    public static final int DEFAULT_WINDOW_RECORDS = 1000;
    public static final int DEFAULT_MIN_SAMPLES = 5;
    public static final int DEFAULT_PENDING_APPROVAL_THRESHOLD = 3;
    public static final int DEFAULT_R3_DENIED_THRESHOLD = 1;
    public static final double DEFAULT_DENIAL_RATE_THRESHOLD = 0.5;

    public final boolean enabled;
    /**
     * 最近 N 条记录。Java 侧 {@code AuditRecord} 只有 seq/action/payload/prevHash/hash，
     * <b>不含时间戳</b>，所以窗口只能按序号而非时间范围；这是 docs/36 FR-1 的最合理解释。
     */
    public final int windowRecords;
    /** 可分析样本低于该值视为「证据不足」，不产出结论（FR-5）。 */
    public final int minSamples;
    public final int pendingApprovalThreshold;
    public final int r3DeniedThreshold;
    public final double denialRateThreshold;

    private AnalyzerConfig(boolean enabled, int windowRecords, int minSamples,
                           int pendingApprovalThreshold, int r3DeniedThreshold,
                           double denialRateThreshold) {
        if (windowRecords <= 0) throw new IllegalArgumentException("windowRecords 必须为正数");
        if (minSamples <= 0) throw new IllegalArgumentException("minSamples 必须为正数");
        if (pendingApprovalThreshold < 0 || r3DeniedThreshold < 0)
            throw new IllegalArgumentException("阈值不能为负数");
        if (Double.isNaN(denialRateThreshold)
                || denialRateThreshold < 0.0 || denialRateThreshold > 1.0)
            throw new IllegalArgumentException("denialRateThreshold 必须在 0..1");
        this.enabled = enabled;
        this.windowRecords = windowRecords;
        this.minSamples = minSamples;
        this.pendingApprovalThreshold = pendingApprovalThreshold;
        this.r3DeniedThreshold = r3DeniedThreshold;
        this.denialRateThreshold = denialRateThreshold;
    }

    /** 默认关闭；只被显式调用方（离线 CLI / 测试）启用。 */
    public static AnalyzerConfig defaults() {
        return new AnalyzerConfig(false, DEFAULT_WINDOW_RECORDS, DEFAULT_MIN_SAMPLES,
                DEFAULT_PENDING_APPROVAL_THRESHOLD, DEFAULT_R3_DENIED_THRESHOLD,
                DEFAULT_DENIAL_RATE_THRESHOLD);
    }

    public AnalyzerConfig withEnabled(boolean v) {
        return new AnalyzerConfig(v, windowRecords, minSamples, pendingApprovalThreshold,
                r3DeniedThreshold, denialRateThreshold);
    }

    public AnalyzerConfig withWindowRecords(int v) {
        return new AnalyzerConfig(enabled, v, minSamples, pendingApprovalThreshold,
                r3DeniedThreshold, denialRateThreshold);
    }

    public AnalyzerConfig withMinSamples(int v) {
        return new AnalyzerConfig(enabled, windowRecords, v, pendingApprovalThreshold,
                r3DeniedThreshold, denialRateThreshold);
    }

    public AnalyzerConfig withPendingApprovalThreshold(int v) {
        return new AnalyzerConfig(enabled, windowRecords, minSamples, v,
                r3DeniedThreshold, denialRateThreshold);
    }

    public AnalyzerConfig withR3DeniedThreshold(int v) {
        return new AnalyzerConfig(enabled, windowRecords, minSamples, pendingApprovalThreshold,
                v, denialRateThreshold);
    }

    public AnalyzerConfig withDenialRateThreshold(double v) {
        return new AnalyzerConfig(enabled, windowRecords, minSamples, pendingApprovalThreshold,
                r3DeniedThreshold, v);
    }
}
