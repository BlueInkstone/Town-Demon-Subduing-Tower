package com.towndemontower.core.bypass;

/**
 * [core] 一条扫描结果（违规 / 警告 / 被豁免的条目）。
 *
 * <p>三个字段是硬要求：<b>规则号、文件、行号</b>——没有行号的报告没人会去修，
 * 没有规则号的豁免会变成"万能豁免"。</p>
 */
public final class BypassFinding {

    public enum Severity {
        /** 计入违规，扫描失败。 */
        BLOCKER,
        /** 只提示，默认不阻断（{@code --strict} 下升为阻断）。 */
        WARN
    }

    public final String ruleId;
    /** 相对扫描根的路径，统一用 {@code /} 分隔（Sonar 报告也用它）。 */
    public final String file;
    public final int line;
    public final String message;
    public final Severity severity;
    /** 是否被显式豁免（豁免条目也会打印，绝不静默）。 */
    public final boolean suppressed;
    /** 豁免理由（未豁免时为 null）。 */
    public final String reason;

    public BypassFinding(String ruleId, String file, int line, String message,
                         Severity severity, boolean suppressed, String reason) {
        this.ruleId = ruleId;
        this.file = file;
        this.line = line;
        this.message = message;
        this.severity = severity;
        this.suppressed = suppressed;
        this.reason = reason;
    }

    public static BypassFinding violation(String ruleId, String file, int line, String message) {
        return new BypassFinding(ruleId, file, line, message, Severity.BLOCKER, false, null);
    }

    public static BypassFinding warning(String ruleId, String file, int line, String message) {
        return new BypassFinding(ruleId, file, line, message, Severity.WARN, false, null);
    }

    public BypassFinding suppress(String why) {
        return new BypassFinding(ruleId, file, line, message, severity, true, why);
    }

    /** {@code file:line} 形式，方便直接点在 IDE 里跳转。 */
    public String location() {
        return file + ":" + line;
    }

    @Override
    public String toString() {
        return "[" + (severity == Severity.BLOCKER ? "VIOLATION" : "WARN") + "] "
                + ruleId + " " + location() + " " + message
                + (suppressed ? "（已豁免：" + reason + "）" : "");
    }
}
