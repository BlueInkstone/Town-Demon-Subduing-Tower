package com.towndemontower.core.bypass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * [core] CP-10 命令行入口（零依赖，{@code java -cp} 直接跑）。
 *
 * <pre>
 *   java -cp out com.towndemontower.core.bypass.BypassScanMain \
 *        [--root &lt;dir&gt;] [--report &lt;file.json&gt;] [--strict] [--main-only]
 * </pre>
 *
 * <p>退出码：{@code 0} 通过（可能含 WARN）、{@code 1} 存在违规、{@code 2} 用法/IO 错误。
 * 有违规就返回非 0，因此它可以原样当门禁用，不需要额外包装。</p>
 *
 * <p>本仓库<b>不提供</b> CI 配置文件：接法（GitHub Actions / Jenkins / 本地 pre-commit）
 * 由接入方按自己的流水线决定，这里只保证"有一个可执行的判据"。</p>
 */
public final class BypassScanMain {

    public static void main(String[] args) {
        Path root = Path.of(".");
        Path report = null;
        boolean strict = false;
        boolean includeTest = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> {
                    if (++i >= args.length) usage("--root 需要一个目录参数");
                    root = Path.of(args[i]);
                }
                case "--report" -> {
                    if (++i >= args.length) usage("--report 需要一个文件路径");
                    report = Path.of(args[i]);
                }
                case "--strict" -> strict = true;
                case "--main-only" -> includeTest = false;
                case "-h", "--help" -> {
                    usage(null);
                    return;
                }
                default -> usage("未知参数：" + args[i]);
            }
        }

        if (!Files.isDirectory(root)) {
            System.err.println("扫描根不存在或不是目录：" + root.toAbsolutePath());
            System.exit(2);
        }

        BypassScanner.ScanResult result;
        try {
            result = new BypassScanner().scan(root, includeTest);
        } catch (IOException e) {
            System.err.println("扫描失败：" + e.getMessage());
            System.exit(2);
            return;
        }

        print(result, strict, includeTest);

        if (report != null) {
            List<BypassFinding> issues = new ArrayList<>(result.violations);
            if (strict) issues.addAll(result.warnings);
            try {
                Path parent = report.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(report, SonarIssueReport.toJson(issues), StandardCharsets.UTF_8);
                System.out.println("SonarQube 通用问题报告已写入：" + report);
            } catch (IOException e) {
                System.err.println("报告写入失败：" + e.getMessage());
                System.exit(2);
                return;
            }
        }

        boolean failed = !result.violations.isEmpty() || (strict && !result.warnings.isEmpty());
        System.exit(failed ? 1 : 0);
    }

    private static void print(BypassScanner.ScanResult r, boolean strict, boolean includeTest) {
        System.out.println("== Town Demon-Subduing Tower · CP-10 绕过面扫描 ==");
        System.out.println("扫描文件：" + r.files + "（范围：src/main/java"
                + (includeTest ? " + src/test/java" : "，未含测试源码") + "）");

        if (!r.violations.isEmpty()) {
            System.out.println();
            System.out.println("违规（" + r.violations.size() + "）：");
            for (BypassFinding f : r.violations) System.out.println("  " + f);
        }

        if (!r.warnings.isEmpty()) {
            System.out.println();
            System.out.println("警告（" + r.warnings.size() + (strict ? "，--strict 下计为失败" : "，默认不阻断") + "）：");
            for (BypassFinding f : r.warnings) System.out.println("  " + f);
        }

        System.out.println();
        if (r.suppressions.isEmpty()) {
            System.out.println("豁免：无");
        } else {
            System.out.println("豁免（" + r.suppressions.size() + "，逐条列出以防静默）：");
            for (BypassFinding f : r.suppressions) {
                System.out.println("  " + f.ruleId + " " + f.location() + " reason=" + f.reason);
            }
        }

        boolean failed = !r.violations.isEmpty() || (strict && !r.warnings.isEmpty());
        System.out.println();
        System.out.println("RESULT: files=" + r.files + " violations=" + r.violations.size()
                + " warnings=" + r.warnings.size() + " suppressions=" + r.suppressions.size()
                + " -> " + (failed ? "FAILED" : "PASSED"));
    }

    private static void usage(String error) {
        if (error != null) System.err.println(error);
        System.err.println("用法：BypassScanMain [--root <dir>] [--report <file.json>] [--strict] [--main-only]");
    }

    private BypassScanMain() {
    }
}
