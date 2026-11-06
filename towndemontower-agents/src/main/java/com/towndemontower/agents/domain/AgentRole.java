package com.towndemontower.agents.domain;

/**
 * [agents] Agent 角色。
 *
 * <p>角色取值与交付流程 <b>PM → Arch → Dev → QA → Ops</b> 对齐：
 * 交付看板上的每个阶段都能落到某个角色的 Agent 上，
 * 注册表与交付路线图因此能对上号，而不是两套互不相干的数据。</p>
 *
 * <p><b>为什么是枚举而不是自由文本</b>：角色决定默认技能与默认权限的模板
 * （见 {@link RoleTemplate}），若允许任意字符串，"给新 Agent 配什么权限"就没有确定答案。</p>
 */
public enum AgentRole {

    /** 产品经理。 */
    PM("产品经理", "PM", "需求 / 验收 / 进度对齐"),

    /** 架构师。 */
    ARCHITECT("架构师", "ARCH", "架构 / 契约 / 技术决策"),

    /** 开发工程师。 */
    DEVELOPER("开发工程师", "DEV", "编码 / 自测 / 缺陷修复"),

    /** 测试工程师。 */
    QA("测试工程师", "QA", "用例 / 回归 / 质量门禁"),

    /** 运维工程师。 */
    OPS("运维工程师", "OPS", "部署 / 监控 / 应急");

    private final String label;
    private final String code;
    private final String duty;

    AgentRole(String label, String code, String duty) {
        this.label = label;
        this.code = code;
        this.duty = duty;
    }

    /** 中文角色名。 */
    public String label() {
        return label;
    }

    /** 短代码，用于展示与检索。 */
    public String code() {
        return code;
    }

    /** 一句话职责，供接口展示。 */
    public String duty() {
        return duty;
    }

    /**
     * 宽松解析：接受枚举名、中文角色名、短代码（均不区分大小写）。
     * 解析失败返回 null，由调用方给出统一错误文案。
     */
    public static AgentRole parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        for (AgentRole r : values()) {
            if (r.name().equalsIgnoreCase(s) || r.code.equalsIgnoreCase(s) || r.label.equals(s)) {
                return r;
            }
        }
        return null;
    }
}
