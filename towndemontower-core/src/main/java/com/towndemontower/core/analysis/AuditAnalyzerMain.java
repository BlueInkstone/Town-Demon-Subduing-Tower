package com.towndemontower.core.analysis;

import com.towndemontower.core.contract.TimeProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * [core] 方向 C / M1 离线 CLI（零依赖，{@code java -cp out} 直接跑）。
 *
 * <pre>
 *   java -cp out com.towndemontower.core.analysis.AuditAnalyzerMain \
 *        --file &lt;audit.log&gt; [--out &lt;report.json&gt;] \
 *        [--window N] [--min-samples N] [--pending-threshold N] \
 *        [--r3-threshold N] [--denial-rate 0.0..1.0]
 * </pre>
 *
 * <p>退出码：{@code 0} 报告已生成（含「证据不足 / 链无效」，此时不出结论）、
 * {@code 2} 用法或 IO 错误。<b>不是门禁</b>：有标记 Agent 也不返回非 0，
 * 因为 M1 只出报告，不做任何裁决。</p>
 *
 * <p>默认关闭（FR-4）：框架内不装配、不定时运行；运行本 CLI 即一次显式启用。
 * 密钥只从环境变量 {@code TOWNDEMONTOWER_AUDIT_KEY} 读取，不接受命令行传密钥。</p>
 */
public final class AuditAnalyzerMain {

    private AuditAnalyzerMain() {
    }

    public static void main(String[] args) {
        Path file = null;
        Path out = null;
        // 运行 CLI = 显式启用；库层 defaults() 仍是 enabled=false
        AnalyzerConfig config = AnalyzerConfig.defaults().withEnabled(true);

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--file" -> {
                    if (++i >= args.length) { usage("--file 需要一个文件路径"); return; }
                    file = Path.of(args[i]);
                }
                case "--out" -> {
                    if (++i >= args.length) { usage("--out 需要一个文件路径"); return; }
                    out = Path.of(args[i]);
                }
                case "--window" -> {
                    if (++i >= args.length) { usage("--window 需要整数"); return; }
                    config = config.withWindowRecords(intArg(args[i], "--window"));
                }
                case "--min-samples" -> {
                    if (++i >= args.length) { usage("--min-samples 需要整数"); return; }
                    config = config.withMinSamples(intArg(args[i], "--min-samples"));
                }
                case "--pending-threshold" -> {
                    if (++i >= args.length) { usage("--pending-threshold 需要整数"); return; }
                    config = config.withPendingApprovalThreshold(intArg(args[i], "--pending-threshold"));
                }
                case "--r3-threshold" -> {
                    if (++i >= args.length) { usage("--r3-threshold 需要整数"); return; }
                    config = config.withR3DeniedThreshold(intArg(args[i], "--r3-threshold"));
                }
                case "--denial-rate" -> {
                    if (++i >= args.length) { usage("--denial-rate 需要 0.0..1.0"); return; }
                    config = config.withDenialRateThreshold(doubleArg(args[i], "--denial-rate"));
                }
                case "-h", "--help" -> {
                    usage(null);
                    return;
                }
                default -> {
                    usage("未知参数：" + args[i]);
                    return;
                }
            }
        }

        if (file == null) { usage("必须指定 --file"); return; }

        AuditLogReader.Loaded loaded;
        try {
            loaded = AuditLogReader.read(file);
        } catch (IOException e) {
            System.err.println("审计读取失败：" + e.getMessage());
            System.exit(2);
            return;
        }

        BehaviorReport report = new AuditBehaviorAnalyzer(config, TimeProvider.system())
                .analyze(loaded.records, loaded.chainValid, loaded.problem);

        System.out.println("== 方向 C / M1 · 只读行为报告 ==");
        System.out.println("规则版本: " + report.ruleVersion + "  生成时间(UTC): " + report.generatedAt);
        System.out.println("状态: " + report.status);
        System.out.println("可分析/窗口内: " + report.analyzableRecords + "/" + report.totalRecords
                + "  跳过: " + report.skippedRecords);
        System.out.println("Agent 数: " + report.agents.size() + "  标记结论: " + report.findings.size());
        for (String n : report.notes) System.out.println("  · " + n);
        for (String f : report.findings) System.out.println("  ! " + f);
        if (!report.evidenceSufficient) {
            System.err.println("证据不足或链校验失败：本次不产出降权结论（报告仍已生成）");
        }

        if (out != null) {
            try {
                Path parent = out.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(out, report.toJson(), StandardCharsets.UTF_8);
                System.out.println("JSON 报告已写入：" + out);
            } catch (IOException e) {
                System.err.println("报告写入失败：" + e.getMessage());
                System.exit(2);
                return;
            }
        }
        System.exit(0);
    }

    private static int intArg(String raw, String flag) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            usage(flag + " 需要整数：" + raw);
            System.exit(2);
            return 0; // 不可达，仅为编译器
        }
    }

    private static double doubleArg(String raw, String flag) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            usage(flag + " 需要 0.0..1.0：" + raw);
            System.exit(2);
            return 0.0;
        }
    }

    private static void usage(String error) {
        if (error != null) System.err.println("参数错误：" + error);
        System.err.println("用法: java -cp out com.towndemontower.core.analysis.AuditAnalyzerMain");
        System.err.println("        --file <audit.log> [--out <report.json>]");
        System.err.println("        [--window N] [--min-samples N] [--pending-threshold N]");
        System.err.println("        [--r3-threshold N] [--denial-rate 0.0..1.0]");
        System.err.println("密钥仅从环境变量 TOWNDEMONTOWER_AUDIT_KEY 读取；M1 只读，不写审计。");
        if (error != null) System.exit(2);
    }
}
