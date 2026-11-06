package com.towndemontower.spring;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.decorator.GovernancePass;
import com.towndemontower.core.decorator.UngovernedInvocationException;
import com.towndemontower.core.executor.IntentArgsValidator;
import com.towndemontower.core.executor.IntentFormatException;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;
import com.towndemontower.core.executor.SkillInvoker;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.metrics.GovernanceMetrics;
import com.towndemontower.spring.tx.GovernanceDecisionRecorder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [starter] AOP 切面：拦截所有 {@link AgentSkill} 方法，把一次普通的方法调用翻译为 core 的
 * {@link IntentObject} + {@link ExecutionContext}，路由进 core 装饰器链。治理通过 → 执行真实方法
 * 并返回其结果；被拒 → 抛 {@link GovernanceDeniedException}（携带 code / 审批单号）。
 *
 * <p>职责边界（薄适配层）：
 * ① 翻译：方法名/参数 → 结构化意图；② 装配桥接：把本次调用的 {@code pjp.proceed()} 经
 * {@link DelegatingSkillInvoker} 注入链尾；③ 懒注册：首次见到某工具名时，把注解元数据登记为
 * core 的 {@link SkillDescriptor} 并（默认）把声明权限自动授权给配置好的 agent；④ 结果映射：
 * 把 core 的 {@link SkillResult} 翻译回方法返回值或治理异常。
 * <b>治理规则本身一律不动</b>——全部来自 core 默认策略。</p>
 */
@Aspect
public class AgentSkillAspect {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(AgentSkillAspect.class.getName());


    private final Executor executor;
    private final SkillRegistry registry;
    private final PermissionEngine permissionEngine;
    private final DelegatingSkillInvoker delegatingInvoker;
    private final TownDemontowerProperties props;
    /** 模式会话：模式由状态机推进，不接受调用方指定（V1.5）。 */
    private final GovernedModeSession modeSession;
    /** 审计：输入校验拒绝也要留痕（不经过链，因此需要直接写）。 */
    private final AuditStore audit;
    /** 指标：统计输入拒绝次数。 */
    private final GovernanceMetrics metrics;
    /** 事务一致性记录器（可选）：存在 DataSource 时由自动配置注入，否则为 null（保持旧行为）。 */
    private final ObjectProvider<GovernanceDecisionRecorder> recorderProvider;
    /** 旧用法过渡宽限：进程内唯一一次"告警后放行"，之后熔断。 */
    private final LegacyGovernanceGrace grace;

    /**
     * 授权端口（V1.9 / I3）。
     *
     * <p>{@code PermissionEngine} 的写方法已收为包内可见，自动授权只能走本端口。
     * 用 setter 而不是构造器参数，是为了不改变既有 6/9 参构造签名（最小侵入）；
     * 未注入时不静默降级——见 {@link #ensureRegistered}：启用自动授权却拿不到端口，
     * 直接拒绝该工具的自动授权并记 WARN，不让"以为授上了"成为隐患。</p>
     */
    private volatile PermissionProvisioner permissionProvisioner;

    /** 已懒注册过的工具名，避免每次调用重复登记 / 授权。 */
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    /**
     * 兼容构造器（V1.5 及更早的手工装配方式）。
     *
     * <p>不传审计 / 指标 / 模式会话：输入校验拒绝时不额外留痕（链内拒绝仍由
     * {@code AuditDecorator} 记录），模式退回按配置项解析。行为与改造前一致，
     * 保留它是为了不强迫外部手工 new 本类的代码跟着改签名。</p>
     */
    public AgentSkillAspect(Executor executor, SkillRegistry registry,
                            PermissionEngine permissionEngine,
                            DelegatingSkillInvoker delegatingInvoker,
                            TownDemontowerProperties props,
                            ObjectProvider<GovernanceDecisionRecorder> recorderProvider) {
        this(executor, registry, permissionEngine, delegatingInvoker, props,
                null, null, null, recorderProvider);
    }

    public AgentSkillAspect(Executor executor, SkillRegistry registry,
                            PermissionEngine permissionEngine,
                            DelegatingSkillInvoker delegatingInvoker,
                            TownDemontowerProperties props,
                            GovernedModeSession modeSession,
                            AuditStore audit,
                            GovernanceMetrics metrics,
                            ObjectProvider<GovernanceDecisionRecorder> recorderProvider) {
        this.executor = executor;
        this.registry = registry;
        this.permissionEngine = permissionEngine;
        this.delegatingInvoker = delegatingInvoker;
        this.props = props;
        this.modeSession = modeSession;
        this.audit = audit;
        this.metrics = metrics;
        this.recorderProvider = recorderProvider;
        this.grace = new LegacyGovernanceGrace(audit, metrics,
                props != null && props.isLegacyGrace());
    }

    /** 注入授权端口（由自动配置调用）。不注入时"自动授权"功能不可用。 */
    public void setPermissionProvisioner(PermissionProvisioner provisioner) {
        this.permissionProvisioner = provisioner;
    }

    /** 供测试/诊断读取过渡宽限的当前状态。 */
    public LegacyGovernanceGrace grace() {
        return grace;
    }

    @Around("@annotation(agentSkill)")
    public Object govern(ProceedingJoinPoint pjp, AgentSkill agentSkill) throws Throwable {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Method method = ms.getMethod();
        String toolName = agentSkill.name().isEmpty() ? method.getName() : agentSkill.name();

        // 治理关闭时只有两条出口（V1.6 / V1.7）：
        // ① 显式弃权（disabled-behavior=allow）：不治理、直接执行；自检记 CRITICAL DISABLED_ALLOW。
        // ② 其余情况应熔断；但首次本应被拦的调用可消耗一次过渡宽限——先告警留痕、再放行，
        //    之后同一进程内的调用一律走到链尾的 RejectAllExecutor 被拒。
        if (!props.isEnabled()) {
            if ("allow".equalsIgnoreCase(props.getDisabledBehavior())) {
                return proceedUngoverned(pjp, "DISABLED_ALLOW");
            }
            if (grace.tryConsume(toolName, props.getAgentId(), "ENABLED_FALSE")) {
                return proceedUngoverned(pjp, LegacyGovernanceGrace.AUDIT_CODE);
            }
        }

        // ① 懒注册工具元数据 + 自动授权声明权限
        ensureRegistered(toolName, agentSkill);

        // ② 方法实参 → 意图参数（按形参名映射，需 -parameters 编译）
        Map<String, Object> args = buildArgs(ms, pjp.getArgs());

        // ②.1 输入校验（默认开启）：长度、控制字符、路径穿越、参数个数。
        //     放在进链之前，因此必须自己留痕——否则"被拒的非法输入"在审计里查不到。
        try {
            IntentArgsValidator.validate(args, props.getMaxArgLength(), props.getMaxArgs(),
                    props.isRejectControlChars(), props.isRejectPathTraversal());
        } catch (IntentFormatException ex) {
            if (audit != null) {
                audit.appendAuto("SKILL_INVOKED", toolName + "|" + ErrorCodes.INPUT_REJECTED
                        + "|" + ex.getMessage());
            }
            if (metrics != null) {
                metrics.inc(ErrorCodes.INPUT_REJECTED);
            }
            throw new GovernanceDeniedException(ErrorCodes.INPUT_REJECTED, ex.getMessage());
        }

        IntentObject intent = new IntentObject(
                toolName, args,
                agentSkill.confidence(),
                agentSkill.reason(),
                agentSkill.expectedEffect(),
                agentSkill.rollbackHint());

        // 模式来自服务端会话（状态机推进），不接受调用方指定；配置里的 mode 只作为初始值，
        // 若运行期被人改配置期望"切模式"，不生效但留痕（MODE_OVERRIDE_IGNORED）。
        com.towndemontower.core.mode.AgentMode mode =
                modeSession != null ? modeSession.current() : props.getModeEnum();
        if (modeSession != null) {
            modeSession.reportOverrideAttempt(props.getModeEnum());
        }

        ExecutionContext ctx = new ExecutionContext(
                props.getSessionId(), props.getTaskId(), props.getTenantId(),
                props.getAgentId(), props.getOwnerUserId(),
                props.isTainted(), mode);

        // ③ 把本次调用的真实执行（pjp.proceed()）注入链尾 DelegatingSkillInvoker
        final Throwable[] thrown = new Throwable[1];
        SkillInvoker perCall = (d, i) -> {
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                thrown[0] = t;          // 暂存，交给外层原样抛出，保留真实异常类型
                return null;
            }
        };
        DelegatingSkillInvoker.CURRENT.set(perCall);
        try {
            // ③ 执行治理链：存在事务一致性记录器（已配 DataSource）则走 @Transactional 落库 + Outbox，
            //    否则退回旧的纯内存执行（轻量场景兼容）。
            GovernanceDecisionRecorder recorder = recorderProvider.getIfAvailable();
            SkillResult r = (recorder != null)
                    ? recorder.decide(executor, intent, ctx, agentSkill.risk())
                    : executor.execute(intent, ctx);

            if (thrown[0] != null) {     // 真实方法执行期间抛了异常
                // V1.11 / D2：方法体的 require(...) 没拿到令牌 —— 说明这次调用绕过了治理链
                // （自调用或直调下层 Service）。对外统一成治理拒绝码，调用方按 getCode() 分流即可，
                // 不必认识 core 内部的异常类型。
                if (thrown[0] instanceof UngovernedInvocationException ungoverned) {
                    throw new GovernanceDeniedException(ungoverned.getCode(), ungoverned.getMessage());
                }
                if (thrown[0] instanceof RuntimeException re) throw re;
                throw new RuntimeException(thrown[0]);
            }
            if (r.success) {
                return r.output;
            }
            // 治理拒绝 / 待审批：统一转为治理异常
            throw new GovernanceDeniedException(r.code, r.message, r.approvalTicketId);
        } finally {
            DelegatingSkillInvoker.CURRENT.remove();
        }
    }

    /**
     * 在"治理被配置成关闭且选择放行"时执行真实方法。
     *
     * <p>V1.11 起受治理方法首行要令牌，而这档配置下根本没有链、也就没有发牌点。用一个显式弃权
     * 作用域包住 {@code proceed()}：方法照常能跑，但每次进作用域都会写一条
     * {@code GOVERNANCE_BYPASS_ISSUED} 审计并计入 {@code explicitBypass}。
     * 不这么做的话，{@code disabled-behavior=allow} 与过渡宽限就成了一条"一配就全炸"的档位。</p>
     */
    private Object proceedUngoverned(ProceedingJoinPoint pjp, String reason) throws Throwable {
        try (AutoCloseable ignored = GovernancePass.bypassScope(reason, audit, metrics)) {
            return pjp.proceed();
        }
    }

    /** 首次见到工具名时：登记 SkillDescriptor，并按开关把声明权限授权给 agent。 */
    private void ensureRegistered(String toolName, AgentSkill ann) {
        if (registered.contains(toolName)) return;
        synchronized (registry) {
            if (!registry.contains(toolName)) {
                SkillDescriptor d = new SkillDescriptor(
                        toolName, ann.risk(),
                        ann.requiredPerms(), ann.capabilities(), ann.requiredArgs(),
                        ann.sideEffect(), ann.sandbox(), ann.commandTemplate(),
                        ann.requiresPlanApproval());
                registry.register(d);
            }
            // 自动授权：让开箱即用的 R1 类工具能过 RBAC（可由配置关闭，改 YAML 即可）。
            // V1.9 起只能走授权端口；端口未注入时**不授权**并告警——宁可工具被 RBAC 拒绝，
            // 也不让"以为授上了、其实没有"或"绕过端口直接写引擎"两种隐患存在。
            if (props.isAutoGrantDeclaredPerms() && ann.requiredPerms().length > 0) {
                PermissionProvisioner port = permissionProvisioner;
                if (port != null) {
                    port.grant(props.getAgentId(), ann.requiredPerms());
                } else {
                    LOG.warning("[towndemontower] 已开启 auto-grant-declared-perms，但未注入"
                            + " PermissionProvisioner：工具 " + toolName + " 的声明权限不会被自动授予，"
                            + "请在自动配置装配处注入授权端口");
                }
            }
            registered.add(toolName);
        }
    }

    /** 把方法实参按形参名映射为意图参数表。 */
    private Map<String, Object> buildArgs(MethodSignature ms, Object[] argv) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (argv == null) return args;
        String[] names = ms.getParameterNames();
        for (int i = 0; i < argv.length; i++) {
            String key = (names != null && i < names.length) ? names[i] : ("arg" + i);
            args.put(key, argv[i]);
        }
        return args;
    }
}
