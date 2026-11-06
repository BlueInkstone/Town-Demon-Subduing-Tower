package com.towndemontower.core.decorator;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.metrics.GovernanceMetrics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Logger;

/**
 * [core] 一次性通行令牌（V1.11 / D2 / CP-1）。
 *
 * <p><b>解决的问题</b>：治理点是注解，而注解只在"从代理对象进来"时生效。同一个类里的
 * {@code this.other()} 自调用、以及别的类直接调下层 Service，都会绕过整个装饰器链——
 * 以前这件事<b>不留任何痕迹</b>。本类把"经过治理链"从<b>约定</b>变成<b>前置条件</b>：
 * 方法的实现首行必须能取到链尾发出的令牌，取不到就当场失败。</p>
 *
 * <p><b>为什么令牌放在 {@code decorator} 包</b>：{@link #issue} 与 {@link #clear} 是包内可见的，
 * 只有同包的链尾 {@link SandboxedToolExecutor} 能发牌。若把 {@code issue()} 做成 public，
 * 任何代码都能"先自己发一张再直调方法"，令牌就退化成注释。这是<b>编译期</b>约束，
 * 配合 CP-10 静态扫描（规则 R3 校验首行）形成"发牌—验牌"闭环。</p>
 *
 * <p><b>不变量</b>：
 * <ol>
 *   <li><b>只在实际执行前发放</b>：R2 待审批、R3 拒绝、取消、任一环拒绝都不经过链尾，天然不发牌；</li>
 *   <li><b>一次性</b>：{@link #require} 必须命中<b>栈顶</b>且工具名一致，命中即弹出；</li>
 *   <li><b>必须兜底清理</b>：链尾在 {@code finally} 里 {@link #clear}。delivery / agents 的
 *       facade 复用同一条链但目标方法没有 {@code require}，不清理的话残留令牌会让同线程
 *       后续的 {@code require} 误通过——这是本类最危险的失效模式；</li>
 *   <li><b>线程隔离</b>：令牌装在 ThreadLocal 栈里，异步执行换线程即失效（这是有意的：
 *       令牌代表"此刻这条调用栈正在被治理"，不是"某个身份可以随便调"）。</li>
 * </ol>
 *
 * <p>计数上报：违规时 {@link GovernanceMetrics#UNGOVERNED_INVOCATION} +1，正常情况下它必须
 * <b>恒为 0</b>，非零即代表有代码在绕过治理链。</p>
 */
public final class GovernancePass {

    private static final Logger LOG = Logger.getLogger(GovernancePass.class.getName());

    /** 单线程令牌栈深度上限：超过即有递归失控的嫌疑，按违规处理并清空。 */
    public static final int MAX_DEPTH = 32;

    /** 显式弃权作用域的审计 action（每开一次一条，不是每次调用一条）。 */
    public static final String BYPASS_AUDIT_CODE = "GOVERNANCE_BYPASS_ISSUED";

    /** 一次调用的令牌帧：工具名 + 本次链装配的上报端（避免全局单例串味）。 */
    private record Frame(String tool, AuditStore audit, GovernanceMetrics metrics) {
    }

    private static final ThreadLocal<Deque<Frame>> ACTIVE = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * 本线程最近一次发牌的帧（只用于违规归属，不参与鉴权）。
     *
     * <p>为什么需要它：最常见的两种绕过恰恰发生在<b>栈已空</b>的时候——
     * 直接调用下层 Service（本线程从未发过牌则确实无从归属，只能落到全局默认）、
     * 以及对同一令牌重复 {@code require}（第一次消费后栈就空了）。若只按栈顶归属，
     * 这类违规会"抛了异常但没计数"，而 {@code ungoverned_invocations_total} 恒为 0
     * 恰恰是"有没有人绕过"的唯一凭据。记住上一次的帧，让同一线程的后续违规仍有账可记。</p>
     */
    private static final ThreadLocal<Frame> LAST = new ThreadLocal<>();

    /**
     * 显式弃权作用域的嵌套计数（V1.11）。
     *
     * <p>唯一合法使用者是 starter 切面在"治理被配置成关闭且选择放行"时开的临时作用域：
     * 那种情况下根本没有链，也就没有链尾可以发牌，而方法体首行的 {@code require} 不该
     * 因此失败——否则 {@code disabled-behavior=allow} 这个显式弃权档位就不可用。</p>
     *
     * <p>它不是后门：开一次作用域就写一条 {@code GOVERNANCE_BYPASS_ISSUED} 审计并计一次
     * {@link GovernanceMetrics#EXPLICIT_BYPASS}，且不会压低 {@code UNGOVERNED_INVOCATION}。</p>
     */
    private static final ThreadLocal<Integer> BYPASS = new ThreadLocal<>();

    /** 全局默认上报端：仅用于手工装配（未经由 ExecutorFactory）的链路。 */
    private static volatile AuditStore defaultAudit;
    private static volatile GovernanceMetrics defaultMetrics;

    private GovernancePass() {
    }

    /**
     * 注册全局默认上报端（通常由 {@link com.towndemontower.core.executor.ExecutorFactory} 在装配时调用）。
     *
     * <p><b>首个非空注册生效</b>，重复注册只记 WARN：同进程内多个模块各自建链时，
     * 同一个违规被多份计数器重复计一次会污染 {@code ungoverned_invocations_total}
     * （它要求恒为 0，多计会让"到底有没有绕过"变得不可判读）。链路自己带上报端时
     * 优先用链路的，不依赖这里。</p>
     */
    public static void configure(AuditStore audit, GovernanceMetrics metrics) {
        if (audit == null && metrics == null) return;
        synchronized (GovernancePass.class) {
            if (defaultAudit == null && defaultMetrics == null) {
                defaultAudit = audit;
                defaultMetrics = metrics;
                return;
            }
            LOG.warning("[towndemontower] GovernancePass 已配置过默认上报端，本次注册被忽略"
                    + "（同进程内只允许一处默认上报，避免违规计数被重复累加）");
        }
    }

    /**
     * 业务方法体首条语句：申请通行令牌。
     *
     * <p>命中栈顶同名令牌即弹出并返回；否则按违规处理——计数 + 审计 + 抛
     * {@link UngovernedInvocationException}，并清空本线程令牌栈（违规之后不再信任残留状态）。</p>
     *
     * @param tool 工具名，必须与 {@code @AgentSkill(name=...)} 逐字一致；CP-10 规则 R3 会校验这一点
     */
    public static void require(String tool) {
        if (tool == null || tool.isEmpty()) {
            throw new IllegalArgumentException("GovernancePass.require(tool) 的 tool 不能为空");
        }
        if (isBypassActive()) {
            // 显式弃权作用域内不校验令牌：进入作用域时已写审计、已计数，不是"悄悄放行"。
            return;
        }
        Deque<Frame> stack = ACTIVE.get();
        Frame top = stack.peek();
        if (top != null && tool.equals(top.tool())) {
            stack.pop();
            if (stack.isEmpty()) ACTIVE.remove();
            return;
        }

        String reason = stack.isEmpty()
                ? "invoked without an active governance pass on thread '" + Thread.currentThread().getName() + "'"
                : "invoked while the active pass is for tool '" + top.tool() + "'";
        // 违规：先上报（栈顶帧 → 本线程最近一次发牌帧 → 全局默认，逐级回落），再清栈，最后抛。
        reportViolation(tool, reason, top != null ? top : LAST.get());
        ACTIVE.remove();
        throw new UngovernedInvocationException(tool, reason);
    }

    /** 当前线程是否存在令牌（诊断用；不消费）。 */
    public static boolean hasActivePass() {
        return !ACTIVE.get().isEmpty();
    }

    /** 当前线程令牌栈深度（诊断用）。 */
    public static int depth() {
        return ACTIVE.get().size();
    }

    /**
     * 清空当前线程令牌栈（测试/诊断与违规兜底用）。
     *
     * <p>不影响已注册的默认上报端——那是装配级状态，由 {@link #configure} 管。</p>
     */
    public static void resetThread() {
        ACTIVE.remove();
        LAST.remove();
        BYPASS.remove();
    }

    /**
     * 打开一个显式弃权作用域（V1.11）：作用域内 {@link #require} 不校验令牌。
     *
     * <p><b>只应被 starter 切面在"治理被配置成关闭且选择放行"时使用</b>——那种情况下没有链，
     * 也就没有链尾可以发牌。其它任何场景调用它，都等于给自己开一张通行证：每开一次会写一条
     * {@code GOVERNANCE_BYPASS_ISSUED} 审计并计一次 {@link GovernanceMetrics#EXPLICIT_BYPASS}，
     * 让"谁在放行"这件事至少是可查的，而不是无痕的。</p>
     *
     * <p>嵌套安全：内层作用域关闭不会撤销外层的弃权。上报端缺省时逐项回落到全局默认。</p>
     */
    public static AutoCloseable bypassScope(String reason) {
        return bypassScope(reason, null, null);
    }

    /**
     * 同 {@link #bypassScope(String)}，但显式指定上报端。
     *
     * <p>存在的理由：{@code enabled=false} 时自动配置直接返回 {@code RejectAllExecutor}，
     * 根本不会走 {@code ExecutorFactory}，全局默认上报端也就没被注册过。切面自己持有审计与
     * 指标 Bean，由它带进来才能保证"弃权"这件事落在真正那条链上。</p>
     */
    public static AutoCloseable bypassScope(String reason, AuditStore audit, GovernanceMetrics metrics) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("GovernancePass.bypassScope(reason) 的理由不能为空");
        }
        Integer current = BYPASS.get();
        if (current == null) {
            recordBypass(reason, audit, metrics);
            BYPASS.set(1);
        } else {
            BYPASS.set(current + 1);
        }
        return () -> {
            Integer depth = BYPASS.get();
            if (depth == null || depth <= 1) {
                BYPASS.remove();
            } else {
                BYPASS.set(depth - 1);
            }
        };
    }

    /** 当前线程是否处于显式弃权作用域内（诊断用）。 */
    public static boolean isBypassActive() {
        Integer depth = BYPASS.get();
        return depth != null && depth > 0;
    }

    // ------------------------------------------------------------------
    // 发牌 / 收牌：包内可见，只有链尾（SandboxedToolExecutor）能调用
    // ------------------------------------------------------------------

    /** 链尾发牌（回落到全局默认上报端）。 */
    static void issue(String tool) {
        issue(tool, null, null);
    }

    /**
     * 链尾发牌，绑定本次链装配的审计与指标上报端（缺省时逐项回落到全局默认）。
     *
     * <p>为什么把上报端放进令牌帧而不是只留一份全局：同进程内可能并存多条链
     * （starter 主链、delivery、agents 各一条，测试里更多），全局单例会让"违规记到谁的账上"
     * 取决于装配顺序。令牌帧随链走，账就记在发起这次调用的那条链上。</p>
     *
     * @throws UngovernedInvocationException 令牌栈超深（递归失控），已清栈
     */
    static void issue(String tool, AuditStore audit, GovernanceMetrics metrics) {
        AuditStore boundAudit = audit != null ? audit : defaultAudit;
        GovernanceMetrics boundMetrics = metrics != null ? metrics : defaultMetrics;
        Deque<Frame> stack = ACTIVE.get();
        if (stack.size() >= MAX_DEPTH) {
            String reason = "governance pass stack exceeded depth " + MAX_DEPTH + " (recursion?)";
            reportViolation(tool, reason, new Frame(tool, boundAudit, boundMetrics));
            ACTIVE.remove();
            throw new UngovernedInvocationException(tool, reason);
        }
        Frame frame = new Frame(tool, boundAudit, boundMetrics);
        stack.push(frame);
        LAST.set(frame);
    }

    /**
     * 链尾收牌，<b>必须在 finally 调用</b>。
     *
     * <p>容错设计：栈顶不是自己时按工具名兜底移除（正常路径不会走到），栈空则什么都不做——
     * 收牌不允许抛异常，否则会把真实执行结果盖掉。</p>
     */
    static void clear(String tool) {
        Deque<Frame> stack = ACTIVE.get();
        if (stack.isEmpty()) {
            ACTIVE.remove();
            return;
        }
        Frame top = stack.peek();
        if (top != null && top.tool().equals(tool)) {
            stack.pop();
        } else {
            stack.removeIf(f -> f.tool().equals(tool));
        }
        if (stack.isEmpty()) ACTIVE.remove();
    }

    /** 显式弃权的留痕：一条审计 + 一次计数；上报失败只记日志，不影响调用本身。 */
    private static void recordBypass(String reason, AuditStore audit, GovernanceMetrics metrics) {
        AuditStore target = audit != null ? audit : defaultAudit;
        GovernanceMetrics counter = metrics != null ? metrics : defaultMetrics;
        if (counter != null) {
            try {
                counter.inc(GovernanceMetrics.EXPLICIT_BYPASS);
            } catch (RuntimeException ex) {
                LOG.severe("[towndemontower] 记录显式弃权指标失败：" + ex);
            }
        }
        if (target != null) {
            try {
                target.appendAuto(BYPASS_AUDIT_CODE, reason + "|thread=" + Thread.currentThread().getName());
            } catch (RuntimeException ex) {
                LOG.severe("[towndemontower] 显式弃权未落审计：" + ex);
            }
        }
    }

    /** 计数 + 审计，且保证"上报失败"不会盖掉"违规"这个主事实。 */
    private static void reportViolation(String tool, String reason, Frame reporter) {
        GovernanceMetrics metrics = reporter != null && reporter.metrics() != null
                ? reporter.metrics() : defaultMetrics;
        AuditStore audit = reporter != null && reporter.audit() != null
                ? reporter.audit() : defaultAudit;

        if (metrics != null) {
            try {
                metrics.inc(GovernanceMetrics.UNGOVERNED_INVOCATION);
            } catch (RuntimeException ex) {
                LOG.severe("[towndemontower] 记录非受治理调用指标失败：" + ex);
            }
        }
        if (audit != null) {
            try {
                audit.appendAuto(ErrorCodes.UNGOVERNED_INVOCATION,
                        tool + "|" + reason + "|thread=" + Thread.currentThread().getName());
            } catch (RuntimeException ex) {
                // 审计写失败要被看见，但"这是一次绕过"才是调用方该先知道的事。
                LOG.severe("[towndemontower] 审计写入失败（" + ErrorCodes.UNGOVERNED_INVOCATION
                        + " 未落链）：" + ex);
            }
        }
    }
}
