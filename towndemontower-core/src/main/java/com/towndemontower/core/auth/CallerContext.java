package com.towndemontower.core.auth;

/**
 * [core] 当前请求的已认证调用者，以及它代表的 Agent。
 *
 * <p>由 REST 过滤器在请求进入时写入、离开时清除，供控制器把"谁在调用、代表哪个 Agent"
 * 带进 {@link com.towndemontower.core.contract.ExecutionContext}。未认证
 * （{@link AuthMode#LOOPBACK} 只读、或 {@link AuthMode#OFF}）时两者均为 {@code null}。</p>
 *
 * <p>V1.9 / I3-b 起多带一个 {@code agentId}：它来自 {@link CallerAgentBindings}，
 * 是"这个令牌代表哪个 Agent"的答案。{@code caller} 是认证身份（服务账号），
 * {@code agentId} 是治理主体（RBAC 与审计认它）——两者故意分开：
 * 同一个 Agent 可以由多个调用者代表，而一个调用者不能同时代表多个 Agent。</p>
 *
 * <p>用 {@link ThreadLocal} 而非方法参数，是为了不改动控制器的现有签名——
 * 这是本层唯一的侵入点。异步/线程池派发前必须显式传递，否则子线程看不到调用者。</p>
 */
public final class CallerContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> AGENT = new ThreadLocal<>();

    private CallerContext() {
    }

    /** 写入调用者身份（无绑定时使用；等价于 {@code set(caller, null)}）。 */
    public static void set(String caller) {
        set(caller, null);
    }

    /**
     * 写入调用者身份与它绑定的 Agent。
     *
     * @param caller  认证出的调用者名称，{@code null} 表示未认证
     * @param agentId 绑定的 Agent ID（可空）；{@code null} 表示"没有绑定"，
     *                由使用方决定是回落默认身份还是拒绝
     */
    public static void set(String caller, String agentId) {
        if (caller == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(caller);
        }
        if (agentId == null) {
            AGENT.remove();
        } else {
            AGENT.set(agentId);
        }
    }

    /** 当前调用者；未认证返回 {@code null}。 */
    public static String current() {
        return CURRENT.get();
    }

    /** 当前调用者，未认证时用 {@code fallback}。 */
    public static String currentOr(String fallback) {
        String c = CURRENT.get();
        return c != null ? c : fallback;
    }

    /** 当前调用者绑定的 Agent ID；未认证或未绑定返回 {@code null}。 */
    public static String agentId() {
        return AGENT.get();
    }

    /** 当前调用者绑定的 Agent ID，未绑定时用 {@code fallback}。 */
    public static String agentIdOr(String fallback) {
        String a = AGENT.get();
        return a != null ? a : fallback;
    }

    /** 是否处于"已认证且已绑定 Agent"状态。 */
    public static boolean bound() {
        return CURRENT.get() != null && AGENT.get() != null;
    }

    /** 清理，<b>必须</b>在 finally 中调用，否则线程复用会串身份。 */
    public static void clear() {
        CURRENT.remove();
        AGENT.remove();
    }
}
