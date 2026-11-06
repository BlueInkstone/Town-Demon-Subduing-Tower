package com.towndemontower.core.policy;

import com.towndemontower.core.contract.RiskLevel;

import java.util.regex.Pattern;

/**
 * [core] 朴素 Shell AST 分析器实现（V1.2 增强）：
 *  - 拆复合命令（; && || 以及管道 |）取最高风险
 *  - 抓 rm -rf / kubectl delete / drop table 等 → R3
 *  - 抓 > / >> / tee 重定向 → 至少 R1（不按只读放行）
 *  - 抓命令替换/子 shell（$(...) / `...` / (...)）→ 标记 subshell，至少 R1
 *  - 引号不配对 / 空命令 → fallback=true（按 R2）
 * 解析失败默认 R2（AST_ANALYSIS_FALLBACK_R2）。
 */
public class SimpleShellAstAnalyzer implements ShellAstAnalyzer {
    private static final Pattern DANGEROUS = Pattern.compile(
            "(rm\\s+-(rf|fr|r\\s+-f|f\\s+-r))|(kubectl\\s+delete)|(drop\\s+table)|(truncate\\s+table)");
    private static final Pattern REDIRECT = Pattern.compile("(>>|>)(\\s|$)|(\\btee\\s)");
    // 复合分隔符：; && || |（注意 || 必须排在 | 之前，避免被拆成两个单管道）
    private static final Pattern SEP = Pattern.compile("(;|&&|\\|\\||\\|)");
    private static final Pattern SUBSHELL = Pattern.compile("(\\$\\(|`|\\()");

    @Override
    public AstRisk analyze(String command) {
        if (command == null || command.trim().isEmpty()) {
            return new AstRisk(true, RiskLevel.R2, false, false, false);
        }
        if (!balancedQuotes(command)) {
            return new AstRisk(true, RiskLevel.R2, false, false, false);
        }
        boolean subshell = SUBSHELL.matcher(command).find();
        boolean compound = SEP.matcher(command).find();
        String[] segments = SEP.split(command);
        RiskLevel max = RiskLevel.R0;
        boolean dangerous = false;
        boolean redirect = false;
        for (String seg : segments) {
            if (seg.trim().isEmpty()) continue;
            if (DANGEROUS.matcher(seg).find()) {
                dangerous = true;
                max = higher(max, RiskLevel.R3);
            } else if (REDIRECT.matcher(seg).find()) {
                redirect = true;
                max = higher(max, RiskLevel.R1); // 不按只读放行
            } else {
                max = higher(max, RiskLevel.R0);
            }
        }
        return new AstRisk(false, max, dangerous, redirect, compound, subshell);
    }

    private boolean balancedQuotes(String s) {
        int dq = 0, sq = 0;
        for (char c : s.toCharArray()) {
            if (c == '"') dq++;
            else if (c == '\'') sq++;
        }
        return dq % 2 == 0 && sq % 2 == 0;
    }

    private static RiskLevel higher(RiskLevel a, RiskLevel b) {
        return a.order() >= b.order() ? a : b;
    }
}
