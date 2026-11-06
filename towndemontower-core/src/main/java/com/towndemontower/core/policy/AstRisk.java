package com.towndemontower.core.policy;

import com.towndemontower.core.contract.RiskLevel;

/**
 * [core] AST 静态分析输出。fallback=true 表示解析失败（调用方按 R2 处理）。
 * subshell=true 表示命令含命令替换/子 shell（$(...)、`...`、(...)），需至少 R1 谨慎处理。
 */
public class AstRisk {
    public final boolean fallback;     // 解析失败
    public final RiskLevel level;      // 该命令（含复合取最高）的风险等级
    public final boolean dangerous;    // 命中 rm -rf / delete / drop 等
    public final boolean redirect;     // 命中 > / >> / tee
    public final boolean compound;     // 复合命令 ; && || |
    public final boolean subshell;     // 含命令替换 / 子 shell

    public AstRisk(boolean fallback, RiskLevel level, boolean dangerous, boolean redirect, boolean compound) {
        this(fallback, level, dangerous, redirect, compound, false);
    }

    public AstRisk(boolean fallback, RiskLevel level, boolean dangerous, boolean redirect,
                   boolean compound, boolean subshell) {
        this.fallback = fallback;
        this.level = level;
        this.dangerous = dangerous;
        this.redirect = redirect;
        this.compound = compound;
        this.subshell = subshell;
    }
}
