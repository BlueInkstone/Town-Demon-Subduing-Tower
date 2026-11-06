package com.towndemontower.core.policy;

import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.RiskLevel;
import com.towndemontower.core.contract.SkillDescriptor;

/**
 * [core] 风险分级器接口（R0-R3）。输入 SkillDescriptor + IntentObject，输出 RiskLevel。
 * 复合命令取最高；未知默认 R2；带重定向不按只读放行；AST 解析失败默认 R2。
 */
public interface RiskGate {
    RiskLevel classify(SkillDescriptor d, IntentObject intent);
}
