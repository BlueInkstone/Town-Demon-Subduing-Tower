package com.towndemontower.agents.domain;

/**
 * [agents] Agent 使用的模型档位。
 *
 * <p>取值与 core {@code routing.ModelProfile.capability} 的语义一一对应
 * （strong / fast / small / guardian），因此档案登记的档位可以直接
 * 交给 {@code ModelRouter} 做路由，不需要再做一次映射。</p>
 *
 * <p>档位不是"越强越好"：文档整理、用例生成用 {@code FAST} 足够，
 * 把 {@code STRONG} 用在所有 Agent 上只会让成本失控。</p>
 */
public enum ModelTier {

    /** 强推理：架构决策、疑难缺陷定位。 */
    STRONG("强推理", "strong"),

    /** 快速：日常编码、文档、用例生成。 */
    FAST("快速", "fast"),

    /** 轻量：格式化、摘要、批量改写。 */
    SMALL("轻量", "small"),

    /** 守护：只做审批前预检与风险识别（对应 GuardianPrecheck）。 */
    GUARDIAN("守护", "guardian");

    private final String label;
    private final String capability;

    ModelTier(String label, String capability) {
        this.label = label;
        this.capability = capability;
    }

    public String label() {
        return label;
    }

    /** 与 core ModelProfile.capability 对齐的取值。 */
    public String capability() {
        return capability;
    }

    /** 宽松解析：枚举名或 capability 字符串。解析失败返回 null。 */
    public static ModelTier parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        for (ModelTier t : values()) {
            if (t.name().equalsIgnoreCase(s) || t.capability.equalsIgnoreCase(s)) return t;
        }
        return null;
    }
}
