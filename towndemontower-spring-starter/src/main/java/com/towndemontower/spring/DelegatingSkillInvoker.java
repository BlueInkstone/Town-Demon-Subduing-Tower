package com.towndemontower.spring;

import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.executor.SkillInvoker;

/**
 * [starter] 桥接 invoker：core 的 {@code Executor} 在装配时把 SkillInvoker 写死进链尾的
 * {@code SandboxedToolExecutor}，无法在每次调用时注入"当前被拦截方法的执行"。本类用 ThreadLocal
 * 把"本次调用的真实执行"（即 {@code pjp.proceed()}）临时绑定，链尾执行时再取出调用。
 *
 * <p>这是让 core 装饰器链保持零 Spring 依赖、而适配层又薄的关键一环：core 一行不改，
 * 适配层只提供这个透传通道。</p>
 */
public class DelegatingSkillInvoker implements SkillInvoker {

    /** 每次 @AgentSkill 调用前由切面绑定，调用后清除。 */
    public static final ThreadLocal<SkillInvoker> CURRENT = new ThreadLocal<>();

    @Override
    public Object invoke(SkillDescriptor d, IntentObject intent) {
        SkillInvoker inv = CURRENT.get();
        if (inv == null) {
            throw new IllegalStateException(
                    "DelegatingSkillInvoker: 未绑定 per-call SkillInvoker。请通过 AgentSkillAspect 调用，"
                            + "或在使用 GovernanceTemplate 时显式传入 invoker。");
        }
        return inv.invoke(d, intent);
    }
}
