package com.towndemontower.core.contract;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * [core] 时间来源契约。
 *
 * <p><b>为什么有它</b>：v1.15 的 CP10-R4 收口。{@code agents} 与 {@code delivery} 的
 * Controller 原本各自注入业务 Service，只为调 {@code now()} 给 View 推导"到期即延期"，
 * 结果被静态扫描判为"Controller 直调 Service"。把"当前时间"下沉成契约后，
 * Controller 不再攥着业务 Service，R4 从语法上消失，而不是靠豁免注释遮住。</p>
 *
 * <p><b>默认实现是无状态的</b>：{@link #system()} 返回同一个实例，不引入 Spring Bean，
 * 因此不影响 {@code AgentsAutoConfiguration} / {@code DeliveryAutoConfiguration} 的装配顺序。
 * 需要冻结时间的测试用 {@link #fixed(Instant)}。</p>
 */
public interface TimeProvider {

    /** 当前时刻。 */
    Instant now();

    /** 系统时钟（默认），无状态、可自由共享。 */
    TimeProvider SYSTEM = Instant::now;

    /** 默认时间源。 */
    static TimeProvider system() {
        return SYSTEM;
    }

    /**
     * 固定时钟，仅测试用。
     *
     * @param at 要冻结的时刻，不能为 null
     * @throws IllegalArgumentException {@code at} 为 null
     */
    static TimeProvider fixed(Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("固定时钟的时刻不能为空");
        }
        Instant frozen = at;
        return () -> frozen;
    }

    /**
     * 包装既有时钟（例如 Service 里可注入的 {@code Supplier<Instant>}），避免迁移时丢失可测性。
     *
     * @param clock 既有时间源，不能为 null
     * @throws IllegalArgumentException {@code clock} 为 null
     */
    static TimeProvider of(Supplier<Instant> clock) {
        if (clock == null) {
            throw new IllegalArgumentException("时间源不能为空");
        }
        return clock::get;
    }
}
