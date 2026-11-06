package com.towndemontower.core.bypass;

/**
 * [core] 单趟词法净化器（CP-10 的地基，零依赖）。
 *
 * <p>为什么不直接对源码跑正则：{@code @AgentSkill} 会出现在注释、Javadoc、字符串里；
 * {@code ProcessBuilder} 可能只是日志文案。匹配前必须先把"不是代码的部分"抹平，
 * 否则扫描器会变成噪音制造机，最后被人关掉——那比没有扫描器更糟。</p>
 *
 * <p>产出两份<b>与原文等长</b>的文本（长度一致是刚需：行号、偏移量可以直接互用）：</p>
 * <ul>
 *   <li>{@link #clean()}：注释、字符串/字符字面量的<b>内容</b>、文本块全部替换为空格，
 *       换行保留 —— 用于结构化定位（修饰符、方法体、关键字）。</li>
 *   <li>{@link #masked()}：只抹掉注释，字符串<b>保留</b> —— 用于取字面量
 *       （{@code @AgentSkill(name="x")} 与 {@code GovernancePass.require("x")} 的实参）。</li>
 * </ul>
 *
 * <p>单趟线性扫描，无回溯正则，O(n)。</p>
 */
public final class JavaSourceLexer {

    private final String source;
    private final char[] clean;
    private final char[] masked;
    private final char[] comments;

    private JavaSourceLexer(String source) {
        this.source = source;
        int n = source.length();
        this.clean = source.toCharArray();
        this.masked = source.toCharArray();
        this.comments = source.toCharArray();

        int i = 0;
        while (i < n) {
            char ch = source.charAt(i);

            // 行注释
            if (ch == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && source.charAt(j) != '\n') {
                    blankBoth(j);
                    j++;
                }
                i = j;
                continue;
            }

            // 块注释
            if (ch == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int j = i;
                while (j < n) {
                    if (source.charAt(j) == '*' && j + 1 < n && source.charAt(j + 1) == '/') {
                        blankBoth(j);
                        blankBoth(j + 1);
                        j += 2;
                        break;
                    }
                    blankBoth(j);
                    j++;
                }
                i = j;
                continue;
            }

            // 文本块 """..."""
            if (ch == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                int j = i;
                while (j < n) {
                    if (source.charAt(j) == '"' && j + 2 < n
                            && source.charAt(j + 1) == '"' && source.charAt(j + 2) == '"') {
                        blankClean(j);
                        blankClean(j + 1);
                        blankClean(j + 2);
                        j += 3;
                        break;
                    }
                    blankClean(j);
                    j++;
                }
                i = j;
                continue;
            }

            // 字符串与字符字面量
            if (ch == '"' || ch == '\'') {
                char quote = ch;
                blankClean(i);
                int j = i + 1;
                while (j < n) {
                    char cj = source.charAt(j);
                    if (cj == '\\') {
                        blankClean(j);
                        if (j + 1 < n) blankClean(j + 1);
                        j += 2;
                        continue;
                    }
                    if (cj == quote) {
                        blankClean(j);
                        j++;
                        break;
                    }
                    if (cj == '\n') {   // 未闭合：到行尾为止，别把整个文件吃掉
                        break;
                    }
                    blankClean(j);
                    j++;
                }
                i = j;
                continue;
            }

            i++;
        }
    }

    public static JavaSourceLexer of(String source) {
        return new JavaSourceLexer(source);
    }

    /** 原文（保留注释与字面量，用于解析豁免注释）。 */
    public String source() {
        return source;
    }

    /** 注释与字面量内容均已抹平（换行保留）。 */
    public String clean() {
        return new String(clean);
    }

    /** 仅注释被抹平，字面量保留。 */
    public String masked() {
        return new String(masked);
    }

    /**
     * 保注释、去字面量：用于识别"豁免注释"。
     *
     * <p>{@link #source()} 不行——字符串里写着 {@code "towndemontower:allow"}（本类的规则常量
     * 就是）会被当成一条豁免声明。在这里字面量已被抹平，剩下的匹配只可能出现在注释里。</p>
     */
    public String comments() {
        return new String(comments);
    }

    public int length() {
        return source.length();
    }

    /** 注释：在"去注释"的两个视图中抹平，但在"只去字面量"的视图中保留。 */
    private void blankBoth(int i) {
        if (source.charAt(i) == '\n') return;
        clean[i] = ' ';
        masked[i] = ' ';
    }

    /** 字面量内容：三个视图里都要抹平（包括保留注释的那一份，否则字符串里的标记会被当成豁免注释）。 */
    private void blankClean(int i) {
        if (source.charAt(i) == '\n') return;
        clean[i] = ' ';
        comments[i] = ' ';
    }
}
