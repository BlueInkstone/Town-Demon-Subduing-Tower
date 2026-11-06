package com.towndemontower.core.policy;

import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.RiskLevel;
import com.towndemontower.core.contract.SkillDescriptor;

/**
 * [core] 默认风险分级器。
 *  1) 工具自带 risk 为基线；未知/空 → R2（unknown-default R2）
 *  2) 若意图含 shell command 参数，过 AST：
 *       - 解析失败(fallback) → R2，记 astFallbackCount
 *       - 取 AST 等级与基线较高者；重定向使只读升级为 R1
 *  3) R3 即使带 --force 也不降级
 */
public class DefaultRiskGate implements RiskGate {
    private final ShellAstAnalyzer ast;
    private volatile int astFallbackCount = 0;

    public DefaultRiskGate(ShellAstAnalyzer ast) {
        this.ast = ast;
    }

    public int getAstFallbackCount() {
        return astFallbackCount;
    }

    @Override
    public RiskLevel classify(SkillDescriptor d, IntentObject intent) {
        RiskLevel base = parseRisk(d.risk);
        if (base == null) {
            base = RiskLevel.R2; // 未知默认 R2
        }
        Object cmd = intent.args == null ? null : intent.args.get("command");
        if (cmd instanceof String && !((String) cmd).trim().isEmpty()) {
            AstRisk a = ast.analyze((String) cmd);
            if (a.fallback) {
                astFallbackCount++;
                return RiskLevel.R2; // AST 解析失败默认 R2
            }
            base = higher(base, a.level);
            if (a.subshell) {
                base = higher(base, RiskLevel.R1); // 子 shell/命令替换至少 R1 谨慎处理
            }
        }
        return base;
    }

    private static RiskLevel parseRisk(String r) {
        if (r == null) return null;
        switch (r) {
            case "R0": return RiskLevel.R0;
            case "R1": return RiskLevel.R1;
            case "R2": return RiskLevel.R2;
            case "R3": return RiskLevel.R3;
            default: return null;
        }
    }

    private static RiskLevel higher(RiskLevel a, RiskLevel b) {
        return a.order() >= b.order() ? a : b;
    }
}
