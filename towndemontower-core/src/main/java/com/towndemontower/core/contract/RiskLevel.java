package com.towndemontower.core.contract;

/**
 * [core] 风险等级 R0-R3。值为字符串，跨语言契约一致。
 */
public enum RiskLevel {
    R0, R1, R2, R3;

    public String code() {
        return name();
    }

    /** 数值序：用于取复合命令的最高风险。 */
    public int order() {
        return ordinal();
    }
}
