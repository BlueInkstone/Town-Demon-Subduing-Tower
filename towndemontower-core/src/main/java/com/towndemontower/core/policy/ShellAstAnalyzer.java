package com.towndemontower.core.policy;

/**
 * [core] Shell 命令 AST 静态分析器接口。不替代 R0-R3，是 RiskGate 的前置补充。
 * 解析失败返回 fallback=true，调用方按 R2 处理。
 */
public interface ShellAstAnalyzer {
    AstRisk analyze(String command);
}
