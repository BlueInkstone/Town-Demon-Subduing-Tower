package com.towndemontower.core.contract;

/**
 * [core] 工具元数据（语言中立）。risk 取值 "R0".."R3"。
 */
public class SkillDescriptor {
    public final String name;
    public final String risk;            // "R0".."R3"
    public final String[] requiredPerms;
    public final String[] capabilities;
    public final String[] requiredArgs;  // Schema 校验：意图必须携带的参数键
    public final boolean sideEffect;
    public final String sandboxPolicy;
    /**
     * 命令模板（V1.12 / I5），如 {@code "systemctl restart {service}"}；{@code null}/空表示不声明。
     *
     * <p>声明之后，{@code RiskGateDecorator} 会先拿实际命令去套模板：形状不符直接拒绝
     * （{@code COMMAND_TEMPLATE_MISMATCH}），不再依赖 AST 黑名单去猜"这条命令危不危险"。
     * 未声明时行为与 v1.11 完全一致（AST 黑名单 + R 级别取高），保证存量工具零改动。</p>
     */
    public final String commandTemplate;

    /**
     * 是否需要"执行计划已批准"（V1.13 / Plan-and-Execute）。
     *
     * <p>{@code true} 时，{@code RiskGateDecorator} 会在裁决前检查
     * {@code PlanApprovalService} 里该 (agent, task) 的最新计划是否 APPROVED；
     * 不是就返回 {@code PLAN_APPROVAL_REQUIRED}。缺省 {@code false}，存量工具零影响。</p>
     */
    public final boolean requiresPlanApproval;

    public SkillDescriptor(String name, String risk, String[] requiredPerms,
                           String[] capabilities, String[] requiredArgs,
                           boolean sideEffect, String sandboxPolicy) {
        this(name, risk, requiredPerms, capabilities, requiredArgs, sideEffect, sandboxPolicy, null, false);
    }

    /**
     * 带命令模板的完整构造（V1.12）。
     *
     * <p>保留 7 参构造是为了不让存量调用点（delivery/agents 的技能目录、各测试的桩）跟着改——
     * 接口约定不变是本期的硬约束。</p>
     */
    public SkillDescriptor(String name, String risk, String[] requiredPerms,
                           String[] capabilities, String[] requiredArgs,
                           boolean sideEffect, String sandboxPolicy, String commandTemplate) {
        this(name, risk, requiredPerms, capabilities, requiredArgs, sideEffect,
                sandboxPolicy, commandTemplate, false);
    }

    /** 完整构造（V1.13）：命令模板 + 计划审批要求。 */
    public SkillDescriptor(String name, String risk, String[] requiredPerms,
                           String[] capabilities, String[] requiredArgs,
                           boolean sideEffect, String sandboxPolicy, String commandTemplate,
                           boolean requiresPlanApproval) {
        this.name = name;
        this.risk = risk;
        this.requiredPerms = requiredPerms;
        this.capabilities = capabilities;
        this.requiredArgs = requiredArgs;
        this.sideEffect = sideEffect;
        this.sandboxPolicy = sandboxPolicy;
        this.commandTemplate = commandTemplate;
        this.requiresPlanApproval = requiresPlanApproval;
    }

    public boolean requiresApproval() {
        return "R2".equals(risk) || "R3".equals(risk);
    }

    /** 是否声明了命令模板（null / 空白都算没声明，避免"空字符串模板"被当成"什么都匹配"）。 */
    public boolean hasCommandTemplate() {
        return commandTemplate != null && !commandTemplate.trim().isEmpty();
    }
}
