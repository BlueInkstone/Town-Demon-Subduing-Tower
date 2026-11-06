package com.towndemontower.core.bypass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * [V1.11] CP-10 静态扫描器验证。
 *
 * <p>两类用例缺一不可：<b>能抓</b>（每条规则的正样本）与<b>不乱抓</b>（注释/字符串里的同名文本、
 * 合法代码）。开源静态检查最典型的死法是误报太多被人关掉，所以后者比前者更重要。</p>
 */
public class BypassScanTest {

    static final Path FIXTURE = Path.of("target", "bypass-fixtures");
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        try {
            resetFixture();
            writeFixtures();
        } catch (IOException ex) {
            // 样例目录写不进去（典型原因：连续跑多个 JVM 时杀软/索引器短暂占用新建的 .java）。
            // 明确报出来并失败，不要让它表现成一个看不懂的"无 RESULT 退出码 1"。
            System.out.println("[FAIL] 准备样例失败：" + ex);
            System.out.println();
            System.out.println("RESULT: passed=" + pass + " failed=" + (fail + 1));
            System.exit(1);
            return;
        }

        BypassScanner scanner = new BypassScanner();
        BypassScanner.ScanResult r = scanner.scan(FIXTURE, true);

        ruleR1(r);
        ruleR2(r);
        ruleR3(r);
        ruleR4(r);
        ruleR5(r);
        exemptions(r);
        falsePositives(r);
        sonarReport(r);
        repoIsClean();

        System.out.println("\nRESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------------- R1 ---------------- */
    static void ruleR1(BypassScanner.ScanResult r) {
        check("R1-private 被拦", hits(r.violations, "PrivateTool.java", BypassScanner.R1) == 1);
        check("R1-static 被拦", hits(r.violations, "StaticTool.java", BypassScanner.R1) == 1);
        check("R1-final 被拦", hits(r.violations, "FinalTool.java", BypassScanner.R1) == 1);
        check("R1-abstract 被拦", hits(r.violations, "AbstractTool.java", BypassScanner.R1) == 1);
        check("R1-合法方法不报", hits(r.violations, "GoodTool.java", BypassScanner.R1) == 0);
    }

    /* ---------------- R2 ---------------- */
    static void ruleR2(BypassScanner.ScanResult r) {
        check("R2-ProcessBuilder 被拦", hits(r.violations, "SubProcessTool.java", BypassScanner.R2) >= 1);
        check("R2-Files.write 被拦", hits(r.violations, "FileWriteTool.java", BypassScanner.R2) >= 1);
        check("R2-字符串里的关键字不报", hits(r.violations, "LiteralTool.java", BypassScanner.R2) == 0);
        check("R2-合法方法不报", hits(r.violations, "GoodTool.java", BypassScanner.R2) == 0);
    }

    /* ---------------- R3 ---------------- */
    static void ruleR3(BypassScanner.ScanResult r) {
        check("R3-缺首行被拦", hits(r.violations, "NoRequireTool.java", BypassScanner.R3) == 1);
        check("R3-名字不一致被拦", hits(r.violations, "WrongNameTool.java", BypassScanner.R3) == 1);
        check("R3-注释在首行之前仍放行", hits(r.violations, "CommentFirstTool.java", BypassScanner.R3) == 0);
        check("R3-合法方法不报", hits(r.violations, "GoodTool.java", BypassScanner.R3) == 0);
    }

    /* ---------------- R4 ---------------- */
    static void ruleR4(BypassScanner.ScanResult r) {
        check("R4-Controller 注入 Service 只报警告",
                hits(r.warnings, "OrderController.java", BypassScanner.R4) >= 1
                        && hits(r.violations, "OrderController.java", BypassScanner.R4) == 0);
    }

    /* ---------------- R5（v1.12.1） ---------------- */
    static void ruleR5(BypassScanner.ScanResult r) {
        check("R5-纯通配模板被提示", hits(r.warnings, "WideTemplateTool.java", BypassScanner.R5) == 1);
        check("R5-多占位符无字面量被提示",
                hits(r.warnings, "NoLiteralTemplateTool.java", BypassScanner.R5) == 1);
        check("R5-有固定词的模板不提示",
                hits(r.warnings, "NarrowTemplateTool.java", BypassScanner.R5) == 0);
        check("R5-未声明模板不提示",
                hits(r.warnings, "NoTemplateTool.java", BypassScanner.R5) == 0);
        check("R5-过宽只提示不阻断",
                hits(r.violations, "WideTemplateTool.java", BypassScanner.R5) == 0);
    }

    /* ---------------- 豁免 ---------------- */
    static void exemptions(BypassScanner.ScanResult r) {
        check("豁免-合法豁免生效", hits(r.suppressions, "SuppressedTool.java", BypassScanner.R2) == 1
                && hits(r.violations, "SuppressedTool.java", BypassScanner.R2) == 0);
        check("豁免-缺理由本身算违规",
                hits(r.violations, "BadExemptionTool.java", BypassScanner.META) == 1);
        check("豁免-缺理由时不豁免原违规",
                hits(r.violations, "BadExemptionTool.java", BypassScanner.R2) >= 1);
        check("豁免-规则号不存在算违规",
                hits(r.violations, "WrongRuleTool.java", BypassScanner.META) == 1);
        check("豁免-字符串里的标记不算豁免",
                hits(r.violations, "LiteralTool.java", BypassScanner.META) == 0);
        check("豁免-R5 也支持显式豁免",
                hits(r.suppressions, "ExemptWideTool.java", BypassScanner.R5) == 1
                        && hits(r.warnings, "ExemptWideTool.java", BypassScanner.R5) == 0);
        check("豁免-文档里提到指令名不算豁免声明",
                hits(r.violations, "DocMention.java", BypassScanner.META) == 0
                        && hits(r.violations, "DocMention.java", BypassScanner.R2) == 0);
    }

    /* ---------------- 误报 ---------------- */
    static void falsePositives(BypassScanner.ScanResult r) {
        check("误报-注释里的假注解不报", hits(r.violations, "FakeAnnotation.java", BypassScanner.R1) == 0);
        check("误报-字符串里的假注解不报", hits(r.violations, "FakeAnnotation.java", BypassScanner.R3) == 0);
        // 精确计数：多一条就说明有误报，少一条就说明漏抓
        check("误报-样例集违规数精确为 12（实际 " + r.violations.size() + "）",
                r.violations.size() == 12);
    }

    /* ---------------- Sonar 报告 ---------------- */
    static void sonarReport(BypassScanner.ScanResult r) {
        String json = SonarIssueReport.toJson(r.violations);
        check("Sonar-含引擎标识", json.contains("\"engineId\": \"towndemontower\""));
        check("Sonar-含规则定义", json.contains("\"id\": \"" + BypassScanner.R1 + "\""));
        check("Sonar-含问题与行号", json.contains("\"textRange\": {\"startLine\": "));
        check("Sonar-转义双引号", json.contains("\\\""));
    }

    /* ---------------- 自扫（最硬的一条） ---------------- */
    static void repoIsClean() throws IOException {
        Path repo = Path.of("..");
        if (!Files.isDirectory(repo.resolve("towndemontower-core"))) {
            System.out.println("[SKIP] repo-self-scan：当前工作目录不是模块目录，跳过");
            return;
        }
        BypassScanner.ScanResult r = new BypassScanner().scan(repo, true);
        check("自扫-扫描器不误伤自己（含字符串字面量豁免标记）", r.violations.isEmpty());
        if (!r.violations.isEmpty()) {
            for (BypassFinding f : r.violations) System.out.println("    " + f);
        }
        check("自扫-确实扫到了源码", r.files > 50);
    }

    /* ---------------- 工具 ---------------- */

    static int hits(List<BypassFinding> list, String fileSuffix, String rule) {
        int n = 0;
        for (BypassFinding f : list) {
            if (f.file.endsWith(fileSuffix) && f.ruleId.equals(rule)) n++;
        }
        return n;
    }

    static void resetFixture() throws IOException {
        if (!Files.exists(FIXTURE)) return;
        try (Stream<Path> s = Files.walk(FIXTURE)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        }
    }

    static void writeFixtures() throws IOException {
        String base = "src/main/java/demo/";

        write(base + "GoodTool.java",
                "package demo;\n"
                        + "public class GoodTool {\n"
                        + "    @AgentSkill(name = \"restartService\", risk = \"R1\")\n"
                        + "    public String restart(String service) {\n"
                        + "        GovernancePass.require(\"restartService\");\n"
                        + "        return \"restarted:\" + service;\n"
                        + "    }\n"
                        + "}\n");

        write(base + "PrivateTool.java",
                "package demo;\n"
                        + "public class PrivateTool {\n"
                        + "    @AgentSkill(name = \"hidden\", risk = \"R1\")\n"
                        + "    private String hidden() {\n"
                        + "        GovernancePass.require(\"hidden\");\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        write(base + "StaticTool.java",
                "package demo;\n"
                        + "public class StaticTool {\n"
                        + "    @AgentSkill(name = \"s\", risk = \"R1\")\n"
                        + "    public static String s() {\n"
                        + "        GovernancePass.require(\"s\");\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        write(base + "FinalTool.java",
                "package demo;\n"
                        + "public class FinalTool {\n"
                        + "    @AgentSkill(name = \"f\", risk = \"R1\")\n"
                        + "    public final String f() {\n"
                        + "        GovernancePass.require(\"f\");\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        write(base + "AbstractTool.java",
                "package demo;\n"
                        + "public abstract class AbstractTool {\n"
                        + "    @AgentSkill(name = \"a\", risk = \"R1\")\n"
                        + "    public abstract String a();\n"
                        + "}\n");

        write(base + "SubProcessTool.java",
                "package demo;\n"
                        + "public class SubProcessTool {\n"
                        + "    @AgentSkill(name = \"run\", risk = \"R1\")\n"
                        + "    public String run(String cmd) throws Exception {\n"
                        + "        GovernancePass.require(\"run\");\n"
                        + "        new ProcessBuilder(cmd).start();\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        write(base + "FileWriteTool.java",
                "package demo;\n"
                        + "public class FileWriteTool {\n"
                        + "    @AgentSkill(name = \"save\", risk = \"R1\")\n"
                        + "    public String save(String p) throws Exception {\n"
                        + "        GovernancePass.require(\"save\");\n"
                        + "        Files.write(Path.of(p), new byte[0]);\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        write(base + "LiteralTool.java",
                "package demo;\n"
                        + "public class LiteralTool {\n"
                        + "    @AgentSkill(name = \"log\", risk = \"R0\")\n"
                        + "    public String log(String m) {\n"
                        + "        GovernancePass.require(\"log\");\n"
                        + "        // 这里提到 ProcessBuilder 只是注释\n"
                        + "        String note = \"towndemontower:allow CP10-R2 reason=fake\";\n"
                        + "        return \"ProcessBuilder Files.write Runtime.getRuntime HttpClient DriverManager\" + note;\n"
                        + "    }\n"
                        + "}\n");

        write(base + "NoRequireTool.java",
                "package demo;\n"
                        + "public class NoRequireTool {\n"
                        + "    @AgentSkill(name = \"write\", risk = \"R1\")\n"
                        + "    public String write(String p) {\n"
                        + "        return \"wrote:\" + p;\n"
                        + "    }\n"
                        + "}\n");

        write(base + "WrongNameTool.java",
                "package demo;\n"
                        + "public class WrongNameTool {\n"
                        + "    @AgentSkill(name = \"write\", risk = \"R1\")\n"
                        + "    public String write(String p) {\n"
                        + "        GovernancePass.require(\"writ\");\n"
                        + "        return \"wrote:\" + p;\n"
                        + "    }\n"
                        + "}\n");

        write(base + "CommentFirstTool.java",
                "package demo;\n"
                        + "public class CommentFirstTool {\n"
                        + "    @AgentSkill(name = \"c\", risk = \"R1\")\n"
                        + "    public String c() {\n"
                        + "        // 说明一下为什么先申请令牌\n"
                        + "        GovernancePass.require(\"c\");\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        write(base + "FakeAnnotation.java",
                "package demo;\n"
                        + "public class FakeAnnotation {\n"
                        + "    // @AgentSkill(name = \"fake\") 只是文档\n"
                        + "    private String doc = \"@AgentSkill(name=\\\"fake\\\")\";\n"
                        + "}\n");

        write(base + "SuppressedTool.java",
                "package demo;\n"
                        + "public class SuppressedTool {\n"
                        + "    // towndemontower:allow CP10-R2 reason=这个工具本身就是执行器，构造进程是它的职责\n"
                        + "    @AgentSkill(name = \"exec\", risk = \"R1\")\n"
                        + "    public String exec(String cmd) throws Exception {\n"
                        + "        GovernancePass.require(\"exec\");\n"
                        + "        new ProcessBuilder(cmd).start();\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        write(base + "BadExemptionTool.java",
                "package demo;\n"
                        + "public class BadExemptionTool {\n"
                        + "    // towndemontower:allow CP10-R2\n"
                        + "    @AgentSkill(name = \"exec2\", risk = \"R1\")\n"
                        + "    public String exec2(String cmd) throws Exception {\n"
                        + "        GovernancePass.require(\"exec2\");\n"
                        + "        new ProcessBuilder(cmd).start();\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        write(base + "WrongRuleTool.java",
                "package demo;\n"
                        + "public class WrongRuleTool {\n"
                        + "    // towndemontower:allow CP10-R9 reason=写错了规则号\n"
                        + "    @AgentSkill(name = \"x\", risk = \"R1\")\n"
                        + "    public String x() {\n"
                        + "        GovernancePass.require(\"x\");\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        // ---- R5（v1.12.1）：命令模板过宽 ----
        write(base + "WideTemplateTool.java",
                "package demo;\n"
                        + "public class WideTemplateTool {\n"
                        + "    @AgentSkill(name = \"wide\", risk = \"R1\", commandTemplate = \"{cmd*}\")\n"
                        + "    public String wide(String command) {\n"
                        + "        GovernancePass.require(\"wide\");\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        write(base + "NoLiteralTemplateTool.java",
                "package demo;\n"
                        + "public class NoLiteralTemplateTool {\n"
                        + "    @AgentSkill(name = \"two\", risk = \"R1\", commandTemplate = \"{a} {b}\")\n"
                        + "    public String two(String command) {\n"
                        + "        GovernancePass.require(\"two\");\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        write(base + "NarrowTemplateTool.java",
                "package demo;\n"
                        + "public class NarrowTemplateTool {\n"
                        + "    @AgentSkill(name = \"narrow\", risk = \"R1\",\n"
                        + "            commandTemplate = \"systemctl restart {service}\")\n"
                        + "    public String narrow(String command) {\n"
                        + "        GovernancePass.require(\"narrow\");\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        write(base + "NoTemplateTool.java",
                "package demo;\n"
                        + "public class NoTemplateTool {\n"
                        + "    @AgentSkill(name = \"plain\", risk = \"R1\")\n"
                        + "    public String plain(String command) {\n"
                        + "        GovernancePass.require(\"plain\");\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        write(base + "ExemptWideTool.java",
                "package demo;\n"
                        + "public class ExemptWideTool {\n"
                        + "    // towndemontower:allow CP10-R5 reason=该工具就是 docker 透传，形状由容器自身保证\n"
                        + "    @AgentSkill(name = \"passthrough\", risk = \"R1\", commandTemplate = \"{cmd*}\")\n"
                        + "    public String passthrough(String command) {\n"
                        + "        GovernancePass.require(\"passthrough\");\n"
                        + "        return \"ok\";\n"
                        + "    }\n"
                        + "}\n");

        write(base + "DocMention.java",
                "package demo;\n"
                        + "/**\n"
                        + " * 使用说明：本框架支持 towndemontower:allow CP10-R2 reason=xxx 这种写法，\n"
                        + " * 但只有写在注释行首才算数。\n"
                        + " */\n"
                        + "public class DocMention {\n"
                        + "}\n");

        write("src/main/java/demo/web/OrderController.java",
                "package demo.web;\n"
                        + "public class OrderController {\n"
                        + "    private final OrderService service;\n"
                        + "    public OrderController(OrderService service) {\n"
                        + "        this.service = service;\n"
                        + "    }\n"
                        + "}\n");

        // src/test/java 也需要被扫到（工具类同样受治理）
        write("src/test/java/demo/TestTool.java",
                "package demo;\n"
                        + "public class TestTool {\n"
                        + "    @AgentSkill(name = \"t\", risk = \"R1\")\n"
                        + "    public String t() {\n"
                        + "        return \"no require\";\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * 写样例文件（带重试）。
     *
     * <p>连续跑多个 JVM 时，刚创建的 {@code .java} 会被杀软/索引器短暂持有，
     * 单次写入偶发 {@code AccessDeniedException}。重试三次即可，重试仍失败就明确报错——
     * 不重试的话表现为"偶发 rc=1 且没有 RESULT"，排查成本远高于加这十行。</p>
     */
    static void write(String rel, String content) throws IOException {
        Path p = FIXTURE.resolve(rel);
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Files.createDirectories(p.getParent());
                Files.writeString(p, content, StandardCharsets.UTF_8);
                return;
            } catch (IOException ex) {
                last = ex;
                try {
                    Thread.sleep(60L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        throw last;
    }
}
