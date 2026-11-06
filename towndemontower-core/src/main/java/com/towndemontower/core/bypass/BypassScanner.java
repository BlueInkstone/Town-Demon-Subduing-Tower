package com.towndemontower.core.bypass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * [core] CP-10 静态扫描引擎（零依赖，只用 JDK）。
 *
 * <p>检查四类"绕过面"，全部来自 {@code docs/18 §5 D3}：</p>
 * <ul>
 *   <li><b>R1 注解目标</b>：{@code @AgentSkill} 只能标在 {@code public}、非 {@code final}、
 *       非 {@code static}、非 {@code private} 的方法上——否则 AOP 根本织不进去，
 *       写上去等于写了张"我受治理"的假标签（BS-02）。</li>
 *   <li><b>R2 方法体副作用</b>：受治理方法体内出现 {@code ProcessBuilder} /
 *       {@code Runtime.getRuntime} / {@code Files.write} / {@code new FileOutputStream} /
 *       {@code HttpClient} / {@code DriverManager}，说明副作用从治理链下面漏出去了（BS-04）。</li>
 *   <li><b>R3 首行令牌</b>：方法体首条语句必须是 {@code GovernancePass.require("工具名")}，
 *       且工具名与注解 {@code name} 一致——这是运行时令牌能在"自调用/直调"时炸出来的前提（BS-01/03）。</li>
 *   <li><b>R4 Controller 直调</b>：Controller 直接注入 {@code *Service}（BS-03）。
 *       只报 WARN：这是当前仓库被认可的写法，一刀切阻断会让历史代码立刻红灯。</li>
 *   <li><b>R5 命令模板过宽</b>（v1.12.1）：{@code commandTemplate} 里没有任何固定词
 *       （`{cmd*}` 之类）时告警。这类模板能匹配任意命令，等于没声明——但确实有要透传的场景，
 *       所以只提示，不阻断。</li>
 * </ul>
 *
 * <p><b>豁免</b>：在方法上方紧邻的注释里写
 * {@code // towndemontower:allow CP10-R2 reason=<非空理由>}。缺理由、规则号未知的豁免
 * <b>本身算违规</b>（{@link #META}），扫描结束会把全部豁免打印出来——不允许静默跳过。</p>
 */
public final class BypassScanner {

    public static final String R1 = "CP10-R1";
    public static final String R2 = "CP10-R2";
    public static final String R3 = "CP10-R3";
    public static final String R4 = "CP10-R4";
    /** 命令模板过宽：模板里没有任何固定词，等价于没声明（v1.12.1 补强）。 */
    public static final String R5 = "CP10-R5";
    /** 豁免注释本身不合法（缺理由 / 规则号拼错）。 */
    public static final String META = "CP10-META";

    /** 豁免注释的标记。 */
    public static final String EXEMPT_MARK = "towndemontower:allow";

    private static final Pattern EXEMPT = Pattern.compile(
            "towndemontower:allow\\s+(CP10-R[1235])\\s+reason=(\\S+)");
    private static final Pattern ANNOTATION_NAME = Pattern.compile("name\\s*=\\s*\"([^\"]*)\"");
    /** 注解上的命令模板字面量（v1.12.1 / R5 需要读它）。 */
    private static final Pattern ANNOTATION_TEMPLATE = Pattern.compile(
            "commandTemplate\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern REQUIRE_CALL = Pattern.compile(
            "GovernancePass\\s*\\.\\s*require\\s*\\(");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern SERVICE_INJECTION = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9_]*Service)\\s+([a-z][A-Za-z0-9_]*)\\s*[,);]");

    /** R2 关键词（正则）+ 人类可读名。 */
    private static final Pattern[] SIDE_EFFECTS = {
            Pattern.compile("\\bProcessBuilder\\b"),
            Pattern.compile("\\bRuntime\\s*\\.\\s*getRuntime\\b"),
            Pattern.compile("\\bFiles\\s*\\.\\s*write\\b"),
            Pattern.compile("\\bnew\\s+FileOutputStream\\b"),
            Pattern.compile("\\bHttpClient\\b"),
            Pattern.compile("\\bDriverManager\\b"),
    };
    private static final String[] SIDE_EFFECT_NAMES = {
            "ProcessBuilder", "Runtime.getRuntime", "Files.write",
            "new FileOutputStream", "HttpClient", "DriverManager",
    };

    /** 扫描器自身与被测样例里必然出现这些字面量，R2 对它自己必须闭眼。 */
    private static final String SELF_PACKAGE = "com/towndemontower/core/bypass/";

    /** 一次扫描的结果。 */
    public static final class ScanResult {
        public final int files;
        public final List<BypassFinding> violations;
        public final List<BypassFinding> warnings;
        public final List<BypassFinding> suppressions;

        ScanResult(int files, List<BypassFinding> violations,
                   List<BypassFinding> warnings, List<BypassFinding> suppressions) {
            this.files = files;
            this.violations = List.copyOf(violations);
            this.warnings = List.copyOf(warnings);
            this.suppressions = List.copyOf(suppressions);
        }
    }

    /**
     * 扫描一个源码树。
     *
     * @param root        扫描根（通常是仓库根目录）
     * @param includeTest 是否把 {@code src/test/java} 一起扫（建议 true：测试里的工具类同样受治理）
     */
    public ScanResult scan(Path root, boolean includeTest) throws IOException {
        List<Path> files = collect(root, includeTest);
        List<BypassFinding> violations = new ArrayList<>();
        List<BypassFinding> warnings = new ArrayList<>();
        List<BypassFinding> suppressions = new ArrayList<>();
        for (Path f : files) {
            scanFile(root, f, violations, warnings, suppressions);
        }
        return new ScanResult(files.size(), violations, warnings, suppressions);
    }

    // ------------------------------------------------------------------
    // 文件收集
    // ------------------------------------------------------------------

    private List<Path> collect(Path root, boolean includeTest) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> inScope(root, p, includeTest))
                    .forEach(out::add);
        }
        out.sort((a, b) -> rel(root, a).compareTo(rel(root, b)));
        return out;
    }

    private boolean inScope(Path root, Path file, boolean includeTest) {
        String rel = rel(root, file);
        String wrapped = "/" + rel + "/";
        if (wrapped.contains("/target/") || wrapped.contains("/out/")
                || wrapped.contains("/out-") || wrapped.contains("/build/")
                || wrapped.contains("/.git/") || wrapped.contains("/node_modules/")) {
            return false;
        }
        if (rel.contains("/src/main/java/") || rel.startsWith("src/main/java/")) return true;
        if (!includeTest) return false;
        return rel.contains("/src/test/java/") || rel.startsWith("src/test/java/");
    }

    private String rel(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------
    // 单文件扫描
    // ------------------------------------------------------------------

    private void scanFile(Path root, Path file,
                          List<BypassFinding> violations,
                          List<BypassFinding> warnings,
                          List<BypassFinding> suppressions) throws IOException {
        String rel = rel(root, file);
        String src = Files.readString(file, StandardCharsets.UTF_8);
        JavaSourceLexer lex = JavaSourceLexer.of(src);
        String clean = lex.clean();
        String masked = lex.masked();
        int[] lineStarts = lineStarts(src);

        // 只在"保注释、去字面量"的视图里找豁免标记：字符串里的同名文本不是豁免声明
        checkExemptionSyntax(rel, lex.comments(), lineStarts, violations);

        int from = 0;
        while (true) {
            int at = clean.indexOf("@AgentSkill", from);
            if (at < 0) break;
            from = at + "@AgentSkill".length();
            if (at > 0 && isIdent(clean.charAt(at - 1))) continue;   // XxxAgentSkill 之类

            int annEnd = skipAnnotationArgs(clean, from);
            String annText = masked.substring(at, Math.min(annEnd, masked.length()));
            Matcher nm = ANNOTATION_NAME.matcher(annText);
            String declaredName = nm.find() ? nm.group(1) : null;

            int declStart = skipFollowingAnnotations(clean, annEnd);
            int paramsStart = clean.indexOf('(', declStart);
            if (paramsStart < 0) continue;
            int paramsEnd = matchParen(clean, paramsStart);
            if (paramsEnd < 0) continue;

            int semi = clean.indexOf(';', paramsEnd);
            int brace = clean.indexOf('{', paramsEnd);
            boolean abstractOrInterface = semi >= 0 && (brace < 0 || semi < brace);
            int declLine = lineOf(lineStarts, declStart);

            // ---- R1：注解目标 ----
            String modifiers = clean.substring(declStart, paramsStart);
            List<String> bad = new ArrayList<>();
            boolean isPublic = hasWord(modifiers, "public");
            if (!isPublic) bad.add("不是 public");
            if (hasWord(modifiers, "private")) bad.add("是 private");
            if (hasWord(modifiers, "static")) bad.add("是 static");
            if (hasWord(modifiers, "final")) bad.add("是 final");
            if (hasWord(modifiers, "abstract")) bad.add("是 abstract");
            Map<String, String> exemptions = exemptionsBefore(src, masked, at);
            if (!bad.isEmpty()) {
                route(BypassFinding.violation(R1, rel, declLine,
                                "@AgentSkill 只能标在 public、非 static/final/private/abstract 的方法上（当前："
                                        + String.join("、", bad) + "），否则 AOP 织不进去，注解只是假标签"),
                        exemptions, violations, warnings, suppressions);
            }

            // ---- R5：命令模板过宽（WARN，v1.12.1） ----
            Matcher tm = ANNOTATION_TEMPLATE.matcher(annText);
            if (tm.find()) {
                String template = tm.group(1).trim();
                if (!template.isEmpty() && isWideTemplate(template)) {
                    route(BypassFinding.warning(R5, rel, declLine,
                                    "命令模板 `" + template + "` 不含任何固定词，等价于没有声明："
                                            + "I5 的意义就是「只允许这一种形状」，请把子命令与选项写进模板"),
                            exemptions, violations, warnings, suppressions);
                }
            }

            if (abstractOrInterface) continue;   // 无方法体，R2/R3 无从谈起

            int bodyEnd = matchBrace(clean, brace);
            if (bodyEnd < 0) continue;
            String methodName = methodNameBefore(clean, paramsStart);
            String toolName = declaredName != null ? declaredName : methodName;

            // ---- R2：方法体副作用 ----
            if (!rel.contains(SELF_PACKAGE)) {
                int bodyAbs = brace;
                for (int i = 0; i < SIDE_EFFECTS.length; i++) {
                    Matcher m = SIDE_EFFECTS[i].matcher(clean);
                    m.region(bodyAbs, bodyEnd);
                    while (m.find()) {
                        route(BypassFinding.violation(R2, rel, lineOf(lineStarts, m.start()),
                                        "受治理方法体内出现 " + SIDE_EFFECT_NAMES[i]
                                                + "：副作用从治理链下面漏出去了，请改成调用受治理工具"),
                                exemptions, violations, warnings, suppressions);
                    }
                }
            }

            // ---- R3：首条语句必须是令牌申请 ----
            int first = skipWs(clean, brace + 1);
            Matcher req = REQUIRE_CALL.matcher(clean);
            req.region(first, Math.min(clean.length(), first + 256));
            boolean startsWithRequire = req.lookingAt();
            if (!startsWithRequire) {
                route(BypassFinding.violation(R3, rel, declLine,
                                "@AgentSkill 方法体首条语句必须是 GovernancePass.require(\"" + toolName
                                        + "\")：这是自调用/直调时能被拦下的唯一位置"),
                        exemptions, violations, warnings, suppressions);
            } else {
                int open = clean.indexOf('(', req.start());
                int close = matchParen(clean, open);
                String arg = null;
                if (close > open) {
                    Matcher lit = STRING_LITERAL.matcher(masked.substring(open + 1, close));
                    if (lit.find()) arg = lit.group(1);
                }
                if (arg == null || !arg.equals(toolName)) {
                    route(BypassFinding.violation(R3, rel, declLine,
                                    "GovernancePass.require(...) 的实参（" + arg
                                            + "）必须与 @AgentSkill(name=\"" + toolName + "\") 逐字一致"),
                            exemptions, violations, warnings, suppressions);
                }
            }
        }

        // ---- R4：Controller 直调 Service（WARN） ----
        if (rel.endsWith("Controller.java")) {
            Matcher m = SERVICE_INJECTION.matcher(clean);
            while (m.find()) {
                warnings.add(BypassFinding.warning(R4, rel, lineOf(lineStarts, m.start()),
                        "Controller 直接注入 " + m.group(1)
                                + "：建议改为调用受治理的工具入口（当前仅提示，--strict 下阻断）"));
            }
        }
    }

    /** 按严重级分流；被豁免的条目一律进 suppressions（无论 BLOCKER 还是 WARN）。 */
    private void route(BypassFinding f, Map<String, String> exemptions,
                       List<BypassFinding> violations, List<BypassFinding> warnings,
                       List<BypassFinding> suppressions) {
        String why = exemptions.get(f.ruleId);
        if (why != null) {
            suppressions.add(f.suppress(why));
            return;
        }
        if (f.severity == BypassFinding.Severity.WARN) {
            warnings.add(f);
        } else {
            violations.add(f);
        }
    }

    /**
     * 命令模板是否"过宽"：所有词都是占位符、没有任何固定词。
     *
     * <p>{@code {cmd*}} 能匹配任意命令，等于回到"什么都不声明"——I5 的约束力被一句话架空。
     * 这不算错误（有些工具确实要透传），所以只报 WARN；但必须让人看见，
     * 否则"我声明了模板"会变成一种自我安慰。</p>
     */
    private static boolean isWideTemplate(String template) {
        String[] parts = template.trim().split("\\s+");
        if (parts.length == 0) return false;
        for (String p : parts) {
            boolean placeholder = p.startsWith("{") && p.endsWith("}") && p.length() > 2;
            if (!placeholder) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 豁免
    // ------------------------------------------------------------------

    /**
     * 豁免注释本身必须合法：缺理由、规则号未知都算违规——否则"豁免"会变成万能开关。
     *
     * @param comments 只保留注释、字面量已抹平的视图（字符串里的同名文本不会误判）
     */
    private void checkExemptionSyntax(String rel, String comments, int[] lineStarts,
                                      List<BypassFinding> violations) {
        int from = 0;
        while (true) {
            int idx = comments.indexOf(EXEMPT_MARK, from);
            if (idx < 0) break;
            from = idx + EXEMPT_MARK.length();
            // 只有"注释开头"的标记才是指令；正文里提到这个名字（比如本类的规则说明）不算
            if (!isDirectiveAt(comments, idx)) continue;
            int lineEnd = comments.indexOf('\n', idx);
            if (lineEnd < 0) lineEnd = comments.length();
            String text = comments.substring(idx, lineEnd);
            if (!EXEMPT.matcher(text).find()) {
                violations.add(BypassFinding.violation(META, rel, lineOf(lineStarts, idx),
                        "豁免注释不合法：" + text.trim()
                                + "（正确写法 // towndemontower:allow CP10-R2 reason=<非空理由>）"));
            }
        }
    }

    /** 找到紧贴在注解之前的连续注释块里的全部豁免声明。 */
    private Map<String, String> exemptionsBefore(String src, String masked, int annotationStart) {
        int i = annotationStart - 1;
        while (i >= 0 && Character.isWhitespace(masked.charAt(i))) i--;
        // 注释被抹成空格，所以 i 现在停在上一段"代码"的末尾；注释块就在 (i, annotationStart) 之间
        int regionStart = i + 1;
        if (regionStart >= annotationStart) return Map.of();
        String region = src.substring(regionStart, annotationStart);
        if (!region.contains(EXEMPT_MARK)) return Map.of();

        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = EXEMPT.matcher(region);
        while (m.find()) {
            if (!isDirectiveAt(region, m.start())) continue;
            out.putIfAbsent(m.group(1), m.group(2));
        }
        return out;
    }

    /**
     * 标记是否位于注释开头（{@code // towndemontower:allow ...} 或 {@code /* towndemontower:allow ...}）。
     *
     * <p>理由：文档里提到这个指令名很常见（本类自己的说明就提到了）。要求"行首"既好写也好认，
     * 又不会把"我们支持 towndemontower:allow 这种写法"误判成一条要豁免的声明。</p>
     */
    private boolean isDirectiveAt(String text, int idx) {
        int i = idx - 1;
        while (i >= 0) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '*' || c == '/') {
                i--;
                continue;
            }
            return c == '\n' || c == '\r';
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 结构定位辅助
    // ------------------------------------------------------------------

    /** 从注解名之后开始：若有参数括号，跳到括号结束；否则原样返回。 */
    private int skipAnnotationArgs(String clean, int pos) {
        int p = skipWs(clean, pos);
        if (p < clean.length() && clean.charAt(p) == '(') {
            int close = matchParen(clean, p);
            return close >= 0 ? close + 1 : clean.length();
        }
        return pos;
    }

    /** 跳过注解与注解之间后续的其它注解（{@code @Deprecated} 之类）。 */
    private int skipFollowingAnnotations(String clean, int pos) {
        int p = pos;
        while (true) {
            p = skipWs(clean, p);
            if (p >= clean.length() || clean.charAt(p) != '@') return p;
            int q = p + 1;
            while (q < clean.length() && isIdent(clean.charAt(q))) q++;
            q = skipWs(clean, q);
            if (q < clean.length() && clean.charAt(q) == '(') {
                int close = matchParen(clean, q);
                if (close < 0) return p;
                q = close + 1;
            }
            p = q;
        }
    }

    private String methodNameBefore(String clean, int paramsStart) {
        int k = paramsStart - 1;
        while (k >= 0 && Character.isWhitespace(clean.charAt(k))) k--;
        int end = k + 1;
        while (k >= 0 && isIdent(clean.charAt(k))) k--;
        return clean.substring(k + 1, end);
    }

    private int matchParen(String s, int open) {
        return match(s, open, '(', ')');
    }

    private int matchBrace(String s, int open) {
        return match(s, open, '{', '}');
    }

    private int match(String s, int open, char l, char r) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == l) depth++;
            else if (c == r) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private int skipWs(String s, int pos) {
        int p = pos;
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        return p;
    }

    private boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private boolean hasWord(String text, String word) {
        Matcher m = Pattern.compile("\\b" + word + "\\b").matcher(text);
        return m.find();
    }

    private int[] lineStarts(String src) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < src.length(); i++) {
            if (src.charAt(i) == '\n') starts.add(i + 1);
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) out[i] = starts.get(i);
        return out;
    }

    /** 偏移 → 1 起行号。 */
    private int lineOf(int[] lineStarts, int offset) {
        int idx = Arrays.binarySearch(lineStarts, offset);
        return (idx >= 0 ? idx : -idx - 2) + 1;
    }
}
