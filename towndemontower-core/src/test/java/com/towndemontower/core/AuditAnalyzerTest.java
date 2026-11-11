package com.towndemontower.core;

import com.towndemontower.core.analysis.AnalyzerConfig;
import com.towndemontower.core.analysis.AuditBehaviorAnalyzer;
import com.towndemontower.core.analysis.AuditLogReader;
import com.towndemontower.core.analysis.BehaviorReport;
import com.towndemontower.core.audit.AuditRecord;
import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.TimeProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

/**
 * [core] 方向 C / M1 自运行验收（零 token、零 Spring、零外部依赖）。
 *
 * <pre>
 *   java -cp out com.towndemontower.core.AuditAnalyzerTest
 * </pre>
 *
 * 覆盖 docs/36 的 AC-1 / AC-2 / AC-4 / AC-5 / AC-6 / AC-10，
 * 以及 FR-2（只认 Java 规范码）、FR-4（窗口与最小样本）、NFR-8（确定性输出）。
 */
public class AuditAnalyzerTest {

    private static final TimeProvider FIXED = TimeProvider.fixed(Instant.parse("2026-11-11T00:00:00Z"));
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws IOException {
        System.out.println("=== 方向 C / M1 只读行为分析器验收（纯 Java / 零 Spring） ===");

        testDefaultsDisabled();          // AC-1
        testConfigValidation();          // FR-4
        testGoldenCounts();              // AC-4 / FR-2
        testDeterministicJson();         // NFR-8
        testNoWritesToRecords();         // AC-2
        testChainInvalidNoConclusions(); // AC-5
        testInsufficientSamples();       // AC-5
        testDegradedPaths();             // AC-6
        testSdkAliasesNotCounted();      // FR-2 / docs/36 L-2
        testNormalizeAliases();          // 方向 D：跨语言统计口径打通
        testWindowAndSkipped();          // FR-4 / 覆盖率
        testMasking();                   // AC-10
        testReaderRoundTripAndTamper();  // FR-5 / AC-10
        testFlaggingThresholds();        // FR-2 / FR-4

        System.out.println("--- " + passed + "/" + (passed + failed) + " passed ---");
        if (failed > 0) System.exit(1);
    }

    /** AC-1：默认关闭时不读审计内容、不产出结论。 */
    private static void testDefaultsDisabled() {
        AnalyzerConfig defaults = AnalyzerConfig.defaults();
        BehaviorReport r = new AuditBehaviorAnalyzer(defaults, FIXED)
                .analyze(goldenRecords(), true);
        check("默认关闭", !defaults.enabled);
        check("关闭态状态为 DISABLED", BehaviorReport.STATUS_DISABLED.equals(r.status));
        check("关闭态零记录", r.totalRecords == 0 && r.analyzableRecords == 0);
        check("关闭态零结论", r.findings.isEmpty() && r.agents.isEmpty());
        check("启用只改 enabled", defaults.withEnabled(true).enabled
                && !AnalyzerConfig.defaults().enabled);
    }

    /** FR-4：非法配置直接拒绝，不 silently 放宽。 */
    private static void testConfigValidation() {
        check("window=0 被拒", throwsIae(() -> AnalyzerConfig.defaults().withWindowRecords(0)));
        check("minSamples=0 被拒", throwsIae(() -> AnalyzerConfig.defaults().withMinSamples(0)));
        check("负阈值被拒", throwsIae(() -> AnalyzerConfig.defaults().withPendingApprovalThreshold(-1)));
        check("拒绝率>1 被拒", throwsIae(() -> AnalyzerConfig.defaults().withDenialRateThreshold(1.5)));
    }

    /** AC-4：统计口径可人工复算；指标与 AuditDecorator 口径一致。 */
    private static void testGoldenCounts() {
        BehaviorReport r = analyze(goldenRecords(), true, enabled(2, 2, 1, 0.9));
        check("可分析样本 8", r.analyzableRecords == 8);
        check("窗口内总数 8", r.totalRecords == 8);
        check("链有效且证据充足", r.chainValid && r.evidenceSufficient);
        check("状态 OK", BehaviorReport.STATUS_OK.equals(r.status));
        check("Agent 数 2", r.agents.size() == 2);

        BehaviorReport.AgentBehavior a = r.agents.get(0); // AGT-0001 排在前面
        check("samples=5", a.samples == 5);
        check("PENDING_APPROVAL=2", a.pendingApproval == 2);
        check("R3_DENIED=1", a.r3Denied == 1);
        check("denied=2（PENDING 不算拒绝）", a.denied == 2);
        check("连续待审批=2", a.maxConsecutivePending == 2);
        check("拒绝率=0.4", Math.abs(a.denialRate - 0.4) < 1e-9);

        BehaviorReport.AgentBehavior b = r.agents.get(1);
        check("第二 Agent samples=3", b.samples == 3);
        check("第二 Agent 无拒绝", b.denied == 0 && b.r3Denied == 0);
        check("第二 Agent 不标记", !b.flagged);
    }

    /** NFR-8：相同输入 + 相同规则版本 + 固定时钟 → 逐字节相同的报告。 */
    private static void testDeterministicJson() {
        AnalyzerConfig c = enabled(2, 2, 1, 0.9);
        String one = analyze(goldenRecords(), true, c).toJson();
        String two = analyze(goldenRecords(), true, c).toJson();
        check("报告确定性", one.equals(two));
        check("JSON 含 camelCase 字段", one.contains("\"analyzableRecords\"")
                && one.contains("\"pendingApproval\"") && one.contains("\"ruleVersion\""));
        check("JSON 含规则版本", one.contains(AuditBehaviorAnalyzer.RULE_VERSION));
    }

    /** AC-2：只读，输入记录列表与每条记录内容都不被改动。 */
    private static void testNoWritesToRecords() {
        List<AuditRecord> records = new ArrayList<>(goldenRecords());
        List<AuditRecord> snapshot = new ArrayList<>(records);
        analyze(records, true, enabled(2, 2, 1, 0.9));
        check("记录列表未被修改", snapshot.equals(records));
        check("记录对象内容未被修改", snapshot.get(0).equals(records.get(0)));
    }

    /** AC-5：链校验失败时仍出报告，但绝不产出降权结论。 */
    private static void testChainInvalidNoConclusions() {
        BehaviorReport r = analyze(goldenRecords(), false, enabled(2, 2, 1, 0.9));
        check("状态 CHAIN_INVALID", BehaviorReport.STATUS_CHAIN_INVALID.equals(r.status));
        check("链无效时零标记", r.findings.isEmpty() && r.agents.stream().noneMatch(a -> a.flagged));
        check("链无效时 evidenceSufficient=false", !r.evidenceSufficient);
        check("链无效时仍保留聚合数", r.analyzableRecords == 8);
    }

    /** AC-5：样本不足 → 证据不足，不产出结论。 */
    private static void testInsufficientSamples() {
        BehaviorReport r = analyze(goldenRecords(), true,
                AnalyzerConfig.defaults().withEnabled(true).withMinSamples(100));
        check("状态 INSUFFICIENT_EVIDENCE",
                BehaviorReport.STATUS_INSUFFICIENT_EVIDENCE.equals(r.status));
        check("样本不足时零标记", r.findings.isEmpty() && r.agents.stream().noneMatch(a -> a.flagged));
        check("样本不足时 evidenceSufficient=false", !r.evidenceSufficient);
    }

    /** AC-6：不可用 / 内部异常都退化为「无结论」，且不泄漏异常文本。 */
    private static void testDegradedPaths() {
        AnalyzerConfig c = enabled(2, 2, 1, 0.9);
        BehaviorReport nullInput = new AuditBehaviorAnalyzer(c, FIXED).analyze(null, true);
        check("null 输入退化", BehaviorReport.STATUS_DEGRADED.equals(nullInput.status)
                && nullInput.findings.isEmpty());

        // 故障注入：读取记录时抛异常，模拟分析器内部故障
        List<AuditRecord> throwing = new AbstractList<>() {
            @Override public AuditRecord get(int index) {
                throw new IllegalStateException("boom SECRET-DETAIL");
            }
            @Override public int size() { return 10; }
        };
        BehaviorReport degraded = new AuditBehaviorAnalyzer(c, FIXED).analyze(throwing, true);
        check("内部异常退化", BehaviorReport.STATUS_DEGRADED.equals(degraded.status)
                && degraded.findings.isEmpty());
        check("退化不回显异常文本", !degraded.toJson().contains("SECRET-DETAIL")
                && !degraded.toJson().contains("boom"));
    }

    /** FR-2 / docs/36 L-2：SDK 别名不得混进 Java 规范码的专项计数。 */
    private static void testSdkAliasesNotCounted() {
        List<AuditRecord> records = List.of(
                rec(1, "APPROVAL_REQUIRED", "AGT-0001"),
                rec(2, "RISK_REJECTED", "AGT-0001"),
                rec(3, "OK", "AGT-0001"),
                rec(4, "OK", "AGT-0001"),
                rec(5, "OK", "AGT-0001"));
        AnalyzerConfig c = AnalyzerConfig.defaults().withEnabled(true)
                .withMinSamples(2).withPendingApprovalThreshold(99)
                .withR3DeniedThreshold(99).withDenialRateThreshold(0.99);
        BehaviorReport.AgentBehavior a = analyze(records, true, c).agents.get(0);
        check("别名不算 PENDING_APPROVAL", a.pendingApproval == 0);
        check("别名不算 R3_DENIED", a.r3Denied == 0);
        check("别名仍算普通拒绝", a.denied == 2);
        check("NEED_MORE_INFO 沿用 AuditDecorator 口径算拒绝",
                analyze(List.of(rec(1, ErrorCodes.NEED_MORE_INFO, "A"),
                        rec(2, "OK", "A"), rec(3, "OK", "A")), true, c).agents.get(0).denied == 1);
    }

    /** 方向 D：显式开启后 SDK 别名按规范码统计；默认关闭保持 D-3「只认 Java 规范码」。 */
    private static void testNormalizeAliases() {
        List<AuditRecord> records = List.of(
                rec(1, "APPROVAL_REQUIRED", "AGT-0001"),
                rec(2, "APPROVAL_REQUIRED", "AGT-0001"),
                rec(3, "RISK_REJECTED", "AGT-0001"),
                rec(4, "CAPABILITY_EXCEEDED", "AGT-0001"),
                rec(5, "OK", "AGT-0001"));
        AnalyzerConfig base = enabled(2, 2, 1, 0.9);

        BehaviorReport off = analyze(records, true, base);
        check("默认关闭时别名不计专项",
                off.agents.get(0).pendingApproval == 0 && off.agents.get(0).r3Denied == 0);
        check("默认开关为 false", !AnalyzerConfig.defaults().normalizeAliases);

        BehaviorReport on = analyze(records, true, base.withNormalizeAliases(true));
        check("打通后 APPROVAL_REQUIRED 计 PENDING_APPROVAL",
                on.agents.get(0).pendingApproval == 2);
        check("打通后 RISK_REJECTED 计 R3_DENIED", on.agents.get(0).r3Denied == 1);
        check("打通后别名仍按统一口径计拒绝", on.agents.get(0).denied == 2);
        check("打通后报告注明口径",
                on.notes.stream().anyMatch(n -> n.contains("统计口径已打通")));
        check("打通不改变窗口与样本数",
                on.analyzableRecords == 5 && on.totalRecords == 5);

        // §6.3 未评审的码保持原值，只算普通拒绝
        BehaviorReport unmapped = analyze(List.of(
                rec(1, "EXECUTION_ERROR", "A"), rec(2, "OK", "A"), rec(3, "OK", "A")),
                true, base.withNormalizeAliases(true));
        check("未映射码仍保留为普通拒绝", unmapped.agents.get(0).denied == 1);
    }

    /** FR-4：窗口按最近 N 条；非 SKILL_INVOKED / 形状不符计入跳过与覆盖率分母。 */
    private static void testWindowAndSkipped() {
        List<AuditRecord> records = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            if (i == 119) {
                records.add(new AuditRecord(i, "MODE_TRANSITION", "PLANNING->EXECUTION", "p", "h"));
            } else {
                records.add(rec(i, "OK", "AGT-0001"));
            }
        }
        BehaviorReport r = analyze(records, true,
                AnalyzerConfig.defaults().withEnabled(true).withWindowRecords(10).withMinSamples(2));
        check("窗口取最近 10 条", r.totalRecords == 10);
        check("非 SKILL_INVOKED 被跳过", r.skippedRecords == 1 && r.analyzableRecords == 9);
        check("覆盖率=0.9", Math.abs(r.coverageRate - 0.9) < 1e-9);
        check("跳过原因写入 notes", r.notes.stream().anyMatch(n -> n.contains("格式不可解析")));
    }

    /** AC-10：报告不含审计原文、工具参数、owner 与凭据；agentId 已脱敏。 */
    private static void testMasking() {
        String json = analyze(goldenRecords(), true, enabled(2, 2, 1, 0.9)).toJson();
        check("不回显原始 agentId", !json.contains("AGT-0001") && !json.contains("AGT-0002"));
        check("不回显 owner", !json.contains("owner-SECRET"));
        check("不回显 task/参数", !json.contains("task-SECRET") && !json.contains("tool1"));
        check("不回显 mode", !json.contains("DEV_EXECUTION"));
        check("脱敏值稳定", BehaviorReport.mask("AGT-0001").equals(BehaviorReport.mask("AGT-0001")));
        check("脱敏值不等同原文", !BehaviorReport.mask("AGT-0001").contains("AGT-0001"));
        check("空归属有固定占位", "(unattributed)".equals(BehaviorReport.mask("")));
    }

    /** FR-5：只读加载 FileAuditStore 日志并校验链；篡改后链无效且不回显原始行。 */
    private static void testReaderRoundTripAndTamper() throws IOException {
        Path dir = Files.createTempDirectory("tdt-m1-reader-");
        Path file = dir.resolve("audit.log");
        try {
            FileAuditStore store = new FileAuditStore(file, null); // 显式 SHA-256，避免依赖环境变量
            for (int i = 0; i < 6; i++) {
                store.appendAuto("SKILL_INVOKED", "tool" + i + "|OK|agent=AGT-0001|owner=x");
            }
            AuditLogReader.Loaded ok = AuditLogReader.read(file, null);
            check("只读加载 6 条", ok.chainValid && ok.records.size() == 6);
            check("加载出的 payload 可用于解析", ok.records.get(0).payload.contains("|OK|"));
            check("加载过程不写审计文件", Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isBlank() && !l.startsWith("#SEGMENT")).count() == 6);

            // 篡改一条 payload：链校验必须失败
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).isBlank() && !lines.get(i).startsWith("#SEGMENT")) {
                    lines.set(i, lines.get(i).replace("|OK|", "|TAMPERED|"));
                    break;
                }
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
            AuditLogReader.Loaded bad = AuditLogReader.read(file, null);
            check("篡改后链无效", !bad.chainValid && bad.problem != null);
            check("失败原因不回显原始行", !bad.problem.contains("tool0")
                    && !bad.problem.contains("TAMPERED"));
            BehaviorReport report = new AuditBehaviorAnalyzer(enabled(2, 2, 1, 0.9), FIXED)
                    .analyze(bad.records, bad.chainValid, bad.problem);
            check("篡改后不出结论", report.findings.isEmpty()
                    && BehaviorReport.STATUS_CHAIN_INVALID.equals(report.status));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir.resolve("audit.log.anchor"));
            Files.deleteIfExists(dir);
        }
    }

    /** FR-2/FR-4：阈值触发标记，且结论只含聚合数与脱敏标识。 */
    private static void testFlaggingThresholds() {
        BehaviorReport r = analyze(goldenRecords(), true, enabled(2, 2, 1, 0.9));
        check("AGT-0001 被标记", r.agents.get(0).flagged);
        check("标记结论数 1", r.findings.size() == 1);
        check("结论不含原始 ID", !r.findings.get(0).contains("AGT-0001"));
        check("结论含规范码名", r.findings.get(0).contains("PENDING_APPROVAL")
                || r.findings.get(0).contains("R3_DENIED"));
        check("阈值放宽后不标记", analyze(goldenRecords(), true,
                enabled(2, 99, 99, 0.99)).findings.isEmpty());
    }

    // ------------------------------------------------------------------

    private static BehaviorReport analyze(List<AuditRecord> records, boolean chainValid,
                                         AnalyzerConfig config) {
        return new AuditBehaviorAnalyzer(config, FIXED).analyze(records, chainValid);
    }

    private static AnalyzerConfig enabled(int minSamples, int pending, int r3, double rate) {
        return AnalyzerConfig.defaults().withEnabled(true).withMinSamples(minSamples)
                .withPendingApprovalThreshold(pending).withR3DeniedThreshold(r3)
                .withDenialRateThreshold(rate);
    }

    /** 与 AuditDecorator 写入格式一致：tool|code|task=...|mode=...|agent=...|owner=... */
    private static AuditRecord rec(long seq, String code, String agent) {
        return new AuditRecord(seq, "SKILL_INVOKED",
                "tool" + seq + "|" + code + "|task=task-SECRET|mode=DEV_EXECUTION|agent=" + agent
                        + "|owner=owner-SECRET",
                "prevHash", "hashValue");
    }

    private static List<AuditRecord> goldenRecords() {
        return List.of(
                rec(1, "OK", "AGT-0001"),
                rec(2, ErrorCodes.PENDING_APPROVAL, "AGT-0001"),
                rec(3, ErrorCodes.PENDING_APPROVAL, "AGT-0001"),
                rec(4, ErrorCodes.R3_DENIED, "AGT-0001"),
                rec(5, "PERMISSION_DENIED", "AGT-0001"),
                rec(6, "OK", "AGT-0002"),
                rec(7, ErrorCodes.PENDING_APPROVAL, "AGT-0002"),
                rec(8, "OK", "AGT-0002"));
    }

    private static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }
}
