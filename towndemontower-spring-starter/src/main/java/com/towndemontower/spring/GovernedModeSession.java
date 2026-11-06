package com.towndemontower.spring;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.metrics.GovernanceMetrics;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.mode.ModeEvent;
import com.towndemontower.core.mode.ModeStateMachine;
import com.towndemontower.core.mode.ModeTransitionInvalidException;

import java.util.concurrent.atomic.AtomicReference;

/**
 * [starter] 模式会话：把 {@link AgentMode} 从"调用方随手传的参数"变成"由状态机推进的服务端状态"。
 *
 * <p><b>为什么必须这么做</b>：模式白名单是整个治理链里最容易被整体绕开的一环——
 * 只要 {@code ExecutionContext.mode} 由调用方给，绕过者不需要动任何策略，
 * 把 mode 换成 {@code DEV_EXECUTION} 就能让本该在审计态被挡住的写工具全部可见。
 * 因此 starter 不再接受外部指定模式：切面从本会话取当前模式，切换只能通过
 * {@link #transition(ModeEvent)} 触发，且必须满足 {@link ModeStateMachine} 的转换表。</p>
 *
 * <p>非法转换（例如审计态直接跳回执行态）会抛 {@link ModeTransitionInvalidException}，
 * 同时写审计与指标——被拒绝的切换和成功的切换一样要留痕，否则"谁试图跳状态"就查不到。</p>
 */
public class GovernedModeSession {

    private final ModeStateMachine machine;
    private final AtomicReference<AgentMode> current;
    private final AuditStore audit;
    private final GovernanceMetrics metrics;
    /** 上一次已上报的"配置期望模式"，用于去重。 */
    private volatile AgentMode lastReportedDrift;

    public GovernedModeSession(AgentMode initial) {
        this(new ModeStateMachine(), initial, null, null);
    }

    public GovernedModeSession(ModeStateMachine machine, AgentMode initial,
                               AuditStore audit, GovernanceMetrics metrics) {
        this.machine = machine;
        this.current = new AtomicReference<>(initial == null ? AgentMode.DEV_EXECUTION : initial);
        this.audit = audit;
        this.metrics = metrics;
    }

    /** 当前模式。切面只用它，不接受调用方传入的模式。 */
    public AgentMode current() {
        return current.get();
    }

    /**
     * 记录"有人试图用配置覆盖模式"。
     *
     * <p>配置里的 {@code towndemontower.mode} 只在启动时决定初始模式；运行期再改配置不会生效，
     * 但必须留痕——否则运维会以为切换成功，实际仍跑在旧模式下。同一目标模式只报一次，避免刷屏。</p>
     */
    public void reportOverrideAttempt(AgentMode configured) {
        if (configured == null || configured == current.get()) return;
        if (configured == lastReportedDrift) return;
        lastReportedDrift = configured;
        if (audit != null) {
            audit.appendAuto("MODE_OVERRIDE_IGNORED",
                    "configured=" + configured.name() + "|actual=" + current.get().name());
        }
        if (metrics != null) {
            metrics.inc(GovernanceMetrics.MODE_OVERRIDE_IGNORED);
        }
    }

    /**
     * 按状态机推进模式。
     *
     * @throws ModeTransitionInvalidException 转换表不允许该事件
     */
    public synchronized AgentMode transition(ModeEvent event) {
        AgentMode from = current.get();
        try {
            AgentMode to = machine.transition(from, event);
            current.set(to);
            if (audit != null) {
                audit.appendAuto("MODE_TRANSITION", from.name() + "->" + to.name() + "|event=" + event);
            }
            return to;
        } catch (ModeTransitionInvalidException ex) {
            if (audit != null) {
                audit.appendAuto("MODE_TRANSITION_DENIED", from.name() + "|event=" + event);
            }
            if (metrics != null) {
                metrics.inc(ErrorCodes.MODE_TRANSITION_INVALID);
            }
            throw ex;
        }
    }
}
