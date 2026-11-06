package com.towndemontower.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * [starter] 标记一个 Spring Bean 方法为"受治理的 Agent 技能"。
 *
 * <p>切面 {@link AgentSkillAspect} 拦截所有带此注解的方法：把方法调用翻译为 core 的
 * {@code IntentObject} + {@code ExecutionContext}，并路由进 core 装饰器链（模式白名单 → 意图格式
 * → Schema → 能力 → 权限 → 风险闸门 → 快照 → 沙箱执行）。治理通过则执行真实方法，
 * 拒绝则抛出 {@link GovernanceDeniedException}。</p>
 *
 * <p>注解的元数据直接映射为 core 的 {@code SkillDescriptor}（工具名 / 风险 / 权限 / 能力 /
 * 必需参数 / 副作用 / sandbox），随首次调用懒注册进 core 的 {@code SkillRegistry}。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentSkill {

    /** 工具名，必须唯一，作为 core SkillRegistry 的 key，也是 IntentObject.tool。 */
    String name();

    /** 风险等级 "R0".."R3"，缺省 R2（未知默认 R2）。 */
    String risk() default "R2";

    /** 所需权限（数字镜像 RBAC），随注册自动授权给配置的 agent。 */
    String[] requiredPerms() default {};

    /** 能力范围。 */
    String[] capabilities() default {};

    /** Schema 校验：意图必须携带的参数键（与方法的形参名对应）。 */
    String[] requiredArgs() default {};

    /** 是否有副作用（写操作）。 */
    boolean sideEffect() default true;

    /** sandbox 策略，如 read-only / ephemeral / none。 */
    String sandbox() default "ephemeral";

    /**
     * 命令模板（V1.12 / I5），声明"这个工具只允许这种形状的命令"。
     *
     * <p>例：{@code "systemctl restart {service}"}、{@code "docker logs {container} {args*}"}。
     * 声明后，{@code command} 参数会先套模板：字面量位置固定、占位符只能落在声明的槽位、
     * 不允许多余的词、参数值不允许含 shell 控制操作符。不符即拒绝
     * （{@code COMMAND_TEMPLATE_MISMATCH}），不再依赖 AST 黑名单去猜。</p>
     *
     * <p>缺省空串＝不声明，行为与 v1.11 一致（AST 黑名单 + R 级别取高）。</p>
     *
     * <p><b>注意</b>：core 预置的工具名（{@code restartService} / {@code deployService} /
     * {@code deleteData} / {@code writeFile} 等）在 {@code SkillRegistry} 构造时就已注册，
     * 切面的懒注册遇到"已存在"会跳过，因此写在这些名字上的模板<b>不会</b>生效。
     * 要么用业务自己的工具名，要么显式重新 {@code register} 覆盖描述符。</p>
     */
    String commandTemplate() default "";

    /**
     * 是否要求"执行计划已批准"（V1.13 / Plan-and-Execute）。
     *
     * <p>{@code true} 时，调用之前会检查 {@code PlanApprovalService} 里该
     * (agentId, taskId) 的最新计划是否 APPROVED；否则返回 {@code PLAN_APPROVAL_REQUIRED}，
     * 并提示当前计划单号与状态。</p>
     *
     * <p>适用对象：<b>落地执行类</b>工具（改文件、跑构建、发部署）。方案产出类工具
     * （如 {@code splitTask} / {@code writeDesign}）本身不该受计划约束，否则永远开不了头。</p>
     */
    boolean requiresPlanApproval() default false;

    /** 置信度 0.0–1.0，注入 IntentObject.confidence。 */
    double confidence() default 1.0;

    String reason() default "";

    String expectedEffect() default "";

    String rollbackHint() default "";
}
