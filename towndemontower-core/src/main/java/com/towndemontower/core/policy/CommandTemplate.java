package com.towndemontower.core.policy;

import com.towndemontower.core.contract.IntentObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [core] 命令模板（V1.12 / I5）：把"命令长什么样"从<b>事后猜测</b>变成<b>事前声明</b>。
 *
 * <p><b>为什么需要它</b>：`docs/18 §I5` 指出，原来的风险判定是"参数名叫 command → 丢给
 * {@link SimpleShellAstAnalyzer} 正则黑名单"。这条路从根上不可靠：黑名单只能列举已知的危险写法，
 * `find . -delete`、`base64 -d | sh`、`xargs rm`、`$(...)` 的变体永远列不完；而它的误报又逼着人
 * 不断放宽规则。反过来做才对——工具说清楚"我只允许这一种形状的命令"，超出形状的一律拒绝，
 * 于是"能不能构造出危险命令"这个问题被替换成"命令是否符合声明"，后者是可判定的。</p>
 *
 * <p><b>语法</b>（按空白切词，单词级匹配）：</p>
 * <pre>
 *   systemctl restart {service}          字面量 + 一个占位符
 *   journalctl -u {unit} --since {since} 字面量位置固定，参数只能落在声明的槽位
 *   docker logs {container} {args*}      {name*} 匹配一个或多个词（至少一个）
 * </pre>
 *
 * <p><b>三条硬规则</b>（缺一条这个机制就形同虚设）：</p>
 * <ol>
 *   <li><b>不允许多余词</b>：命令比模板长即失败。追加的 `; rm -rf /`、`| sh`、`-c ...`
 *       都会让词数对不上，直接拒。</li>
 *   <li><b>占位符值不含控制操作符</b>：`; | &amp; $ ` &lt; &gt; ( ) { } ! ' " \` 与空白控制字符一律拒绝，
 *       且不允许以 `~`（家目录展开）、`#`（注释）开头。<b>单值占位符</b>额外禁止以 `-` 开头
 *       （选项注入：`rm {target}` 配 `target=-rf /`）；<b>{@code {name*}}</b> 是显式声明的
 *       "尾部参数区"，允许选项，但仍禁止控制操作符。</li>
 *   <li><b>不支持引号与转义</b>：命令必须是"空格分隔的裸词"序列。带引号的命令交给模板匹配
 *       只会得到拒绝——这是有意的：I5 要的是"形状完全可预测"，不是"尽力理解用户想干什么"。</li>
 * </ol>
 *
 * <p>解析结果按模板字符串缓存在静态表里（模板是代码里写死的，数量有限），匹配路径不分配正则、
 * 不做字符串拼接，单次匹配为词数级的回溯搜索，最坏 O(词数 × 占位符数)。</p>
 */
public final class CommandTemplate {

    /** 约定：命令放在意图参数的 {@code command} 键下（与 {@link DefaultRiskGate} 保持一致）。 */
    public static final String COMMAND_ARG = "command";

    /** 单个参数值长度上限：防止超长串撑爆日志或后续 shell 拼接。 */
    private static final int MAX_PARAM_LEN = 256;
    /** 命令总长上限。 */
    private static final int MAX_COMMAND_LEN = 2048;
    /** 参数个数上限：超过视为模板写错了。 */
    private static final int MAX_PARAM_COUNT = 16;

    /** 参数值中禁止出现的字符（shell 控制操作符 + 引号 + 空白控制符）。 */
    private static final String FORBIDDEN_IN_PARAM = ";|&$`<>(){}\n\r\t\"'\\";

    private static final ConcurrentHashMap<String, CommandTemplate> CACHE = new ConcurrentHashMap<>();

    /** 匹配结果。失败时 {@link #reason} 说明原因（用于审计与拒绝文案）。 */
    public static final class Match {
        public final boolean matched;
        public final String reason;
        public final Map<String, String> params;

        private Match(boolean matched, String reason, Map<String, String> params) {
            this.matched = matched;
            this.reason = reason;
            this.params = params;
        }

        static Match fail(String reason) {
            return new Match(false, reason, Collections.emptyMap());
        }

        static Match ok(Map<String, String> params) {
            return new Match(true, "matched", Collections.unmodifiableMap(new LinkedHashMap<>(params)));
        }
    }

    private enum Kind { LITERAL, PARAM, PARAM_REST }

    private static final class Token {
        final Kind kind;
        final String text;

        Token(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private final String template;
    private final List<Token> tokens;
    private final List<String> paramNames;

    /**
     * 解析（带缓存）。
     *
     * @throws IllegalArgumentException 模板为空 / 占位符非法 / 同名占位符重复 / 字面量含控制操作符
     */
    public static CommandTemplate of(String template) {
        if (template == null || template.trim().isEmpty()) {
            throw new IllegalArgumentException("命令模板不能为空");
        }
        String key = template.trim();
        CommandTemplate cached = CACHE.get(key);
        if (cached != null) return cached;
        CommandTemplate created = new CommandTemplate(key);
        CommandTemplate prev = CACHE.putIfAbsent(key, created);
        return prev != null ? prev : created;
    }

    private CommandTemplate(String template) {
        this.template = template;
        List<Token> ts = new ArrayList<>();
        List<String> names = new ArrayList<>();

        for (String raw : template.split("\\s+")) {
            if (raw.startsWith("{") && raw.endsWith("}") && raw.length() > 2) {
                String inner = raw.substring(1, raw.length() - 1);
                boolean rest = inner.endsWith("*");
                String name = rest ? inner.substring(0, inner.length() - 1) : inner;
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("占位符名称非法：" + raw);
                }
                if (names.contains(name)) {
                    throw new IllegalArgumentException("占位符重复：" + name);
                }
                if (names.size() >= MAX_PARAM_COUNT) {
                    throw new IllegalArgumentException("占位符超过 " + MAX_PARAM_COUNT + " 个：" + template);
                }
                names.add(name);
                ts.add(new Token(rest ? Kind.PARAM_REST : Kind.PARAM, name));
                continue;
            }
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (FORBIDDEN_IN_PARAM.indexOf(c) >= 0) {
                    throw new IllegalArgumentException("模板字面量含控制操作符 '" + c + "'：" + raw);
                }
            }
            ts.add(new Token(Kind.LITERAL, raw));
        }

        if (ts.isEmpty()) throw new IllegalArgumentException("命令模板不能为空");
        this.tokens = new ArrayList<>(ts);
        this.paramNames = new ArrayList<>(names);
    }

    /** 模板原文。 */
    public String template() {
        return template;
    }

    /** 占位符名称（按出现顺序，只读）。 */
    public List<String> paramNames() {
        return paramNames;
    }

    /**
     * 拿实际命令去套模板。
     *
     * @param command 待校验命令，可为 null（null/空白 → 不匹配）
     */
    public Match match(String command) {
        if (command == null || command.trim().isEmpty()) {
            return Match.fail("命令为空");
        }
        String trimmed = command.trim();
        if (trimmed.length() > MAX_COMMAND_LEN) {
            return Match.fail("命令过长（>" + MAX_COMMAND_LEN + " 字符）");
        }
        String[] parts = trimmed.split("\\s+");
        Map<String, String> bound = new LinkedHashMap<>();
        if (walk(0, 0, parts, bound)) {
            return Match.ok(bound);
        }
        return Match.fail("命令形状与模板 `" + template + "` 不符（实际：" + abbreviate(trimmed) + "）");
    }

    /** 从意图参数里取命令；缺席或不是字符串时返回 null。 */
    public static String commandOf(IntentObject intent) {
        if (intent == null || intent.args == null) return null;
        Object v = intent.args.get(COMMAND_ARG);
        return v instanceof String ? (String) v : null;
    }

    // ------------------------------------------------------------------
    // 匹配：逐词回溯
    // ------------------------------------------------------------------

    private boolean walk(int ti, int ci, String[] parts, Map<String, String> bound) {
        if (ti == tokens.size()) {
            return ci == parts.length;      // 命令必须被完整消费：多一个词都算不匹配
        }
        Token t = tokens.get(ti);

        if (t.kind == Kind.LITERAL) {
            if (ci >= parts.length || !t.text.equals(parts[ci])) return false;
            return walk(ti + 1, ci + 1, parts, bound);
        }

        if (t.kind == Kind.PARAM) {
            if (ci >= parts.length || !isSafeParam(parts[ci], false)) return false;
            if (bound.containsKey(t.text)) return false;
            bound.put(t.text, parts[ci]);
            if (walk(ti + 1, ci + 1, parts, bound)) return true;
            bound.remove(t.text);
            return false;
        }

        // PARAM_REST：至少一个词，从最短开始试（短匹配更容易让后面的字面量对上）
        if (bound.containsKey(t.text)) return false;
        StringBuilder acc = new StringBuilder();
        for (int k = 1; ci + k <= parts.length; k++) {
            String word = parts[ci + k - 1];
            // {name*} 是"尾部参数区"，允许以 - 开头的选项；控制操作符仍然禁止。
            if (!isSafeParam(word, true)) return false;     // 这个词不能当参数，加长只会更糟
            if (k > 1) acc.append(' ');
            acc.append(word);
            bound.put(t.text, acc.toString());
            if (walk(ti + 1, ci + k, parts, bound)) return true;
            bound.remove(t.text);
        }
        return false;
    }

    /**
     * 参数值是否可以安全地作为"一个词"代入命令。
     *
     * @param allowFlags {@code true} 时允许以 {@code -} 开头（仅 {@code {name*}} 这种显式声明的
     *                   尾部参数区）；单值占位符一律禁止，避免"值位置被塞进一个选项"——
     *                   那是 `rm {target}` + `target=-rf /` 这类注入的典型形态
     */
    private static boolean isSafeParam(String v, boolean allowFlags) {
        if (v == null || v.isEmpty() || v.length() > MAX_PARAM_LEN) return false;
        char first = v.charAt(0);
        if (!allowFlags && first == '-') return false;             // 选项注入
        if (first == '~' || first == '#') return false;            // 家目录展开 / 注释
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
            if (FORBIDDEN_IN_PARAM.indexOf(c) >= 0) return false;
        }
        return true;
    }

    private static String abbreviate(String s) {
        return s.length() <= 64 ? s : s.substring(0, 61) + "...";
    }

    @Override
    public String toString() {
        return "CommandTemplate{" + template + "}";
    }
}
