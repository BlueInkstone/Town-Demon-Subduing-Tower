package com.towndemontower.core.auth;
import java.util.Arrays;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [core] 调用者 → Agent 绑定表（V1.9 / I3-b）。纯 Java，无框架依赖。
 *
 * <p>解决的问题：V1.8 的令牌只回答"你是哪个调用者"，不回答"你代表哪个 Agent"。
 * 于是 {@code agents} 的写操作<b>永远</b>落在配置里的同一个身份
 * （{@code towndemontower.agents.agent-id}）上：审计里看不出是哪个 Agent 在改注册表，
 * RBAC 也只认那一个主体。任何持令牌的调用者，实际拥有的都是同一个 Agent 的全部权限。</p>
 *
 * <p>绑定表把这两件事接上：令牌认证出的调用者名字，映射到一个具体 Agent ID，
 * 该 ID 成为 {@code ExecutionContext.agentId}——RBAC 按它裁决、审计按它归属。
 * 调用者拿到的权限因此是<b>它绑定的那个 Agent</b> 的权限，而不是注册表身份的权限。</p>
 *
 * <h2>来源与格式</h2>
 * <p>与令牌同一条规矩：<b>只</b>读环境变量 {@value #ENV}，格式
 * {@code 调用者:AGT-0001,调用者2:AGT-0003}。不落配置文件、无系统属性回退、无默认值。</p>
 *
 * <h2>空表语义（关键取舍）</h2>
 * <p>表为空 = <b>不启用绑定校验</b>，行为与 V1.8 完全一致（所有调用者共用注册表身份）。
 * 这不是"忘了配就放行"的漏洞，而是升级路径的兼容位：V1.8 的部署没有这张表，
 * 若空表即全拒，升级会一次性打死所有接入方。代价由启动自检承担——空表时
 * 报 {@code CALLER_BINDING_MISSING}，让人看见"身份还没落到 Agent 上"。</p>
 *
 * <p>表<b>非空</b>即进入严格模式：表里没有的调用者一律 {@code CALLER_NOT_PROVISIONED}，
 * 不存在"表里没写就沿用默认身份"的回退。要么全绑，要么不启用——中间态才是真正的坑。</p>
 */
public final class CallerAgentBindings {

    /** 绑定表所在的环境变量名。 */
    public static final String ENV = "TOWNDEMONTOWER_API_BINDINGS";

    /** 调用者名称与 Agent ID 的合法形态：字母数字与 {@code _ . -}，长度 1~64。 */
    private static final String TOKEN_PATTERN = "[A-Za-z0-9_.-]{1,64}";

    /** 绑定表整体状态。 */
    public enum Status {
        /** 环境变量未设置（不启用绑定校验）。 */
        MISSING,
        /** 全部条目合格。 */
        OK,
        /** 部分条目被拒（仍可用，但要告警）。 */
        PARTIAL,
        /** 设置过但没有任何一条合格——等同未设置，但有配置错误的嫌疑。 */
        WEAK
    }

    private final Map<String, String> bindings;
    private final List<String> rejected;
    private final boolean envPresent;

    private CallerAgentBindings(Map<String, String> bindings, List<String> rejected, boolean envPresent) {
        // 用 LinkedHashMap 保持配置书写顺序：Map.copyOf 不保证迭代顺序（每个 JVM 的 SALT 不同），
        // 会让启动日志里列出的绑定顺序随机跳动，排查时误以为配置被人改过。
        this.bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        this.rejected = new ArrayList<>(rejected);
        this.envPresent = envPresent;
    }

    /** 空表：不启用绑定校验。 */
    public static CallerAgentBindings none() {
        return new CallerAgentBindings(Collections.emptyMap(), Arrays.asList(), false);
    }

    /** 从环境变量装配。未设置或全不合格 → 空表，不做任何默认值兜底。 */
    public static CallerAgentBindings fromEnv() {
        String raw = System.getenv(ENV);
        if (raw == null || raw.trim().isEmpty()) return none();
        return parse(raw);
    }

    /**
     * 解析 {@code caller:AGT-0001,caller2:AGT-0003}。
     *
     * <p>被拒条目只记录"条目摘要 + 原因"，<b>不含</b>任何令牌内容——这里本来也没有令牌，
     * 但同样不把整条原文打出去，避免误把"用户名+密码"式的写法带进日志。</p>
     */
    public static CallerAgentBindings parse(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return new CallerAgentBindings(map, rejected, false);
        }
        Set<String> seen = new LinkedHashSet<>();
        // 先吃掉冒号两侧的空白：{@code ops : AGT-0001} 与 {@code ops:AGT-0001} 等价。
        // 空白本身既是分隔符又可能只是排版空格，不归一化会把一条合法绑定拆成两段被拒条目，
        // 而"绑定没生效"在严格模式下表现为 403——排查成本远高于这点宽容的代价。
        // 宽容仅限空白位置：两侧取值仍必须匹配名称规则，不放宽任何安全判定。
        String normalized = raw.replaceAll("\\s*:\\s*", ":");
        for (String entry : normalized.split("[,;\\s]+")) {
            if (entry.trim().isEmpty()) continue;
            int idx = entry.indexOf(':');
            if (idx <= 0 || idx == entry.length() - 1) {
                rejected.add((idx < 0 ? entry : entry.substring(0, idx))
                        + "(缺少 '调用者:AgentID' 分隔或后半为空)");
                continue;
            }
            String caller = entry.substring(0, idx).trim();
            String agentId = entry.substring(idx + 1).trim();
            if (!caller.matches(TOKEN_PATTERN)) {
                rejected.add(caller + "(调用者名称非法，只允许字母数字与 _ . -，长度 1~64)");
                continue;
            }
            if (!agentId.matches(TOKEN_PATTERN)) {
                rejected.add(caller + "(AgentID 非法，只允许字母数字与 _ . -，长度 1~64)");
                continue;
            }
            if (!seen.add(caller)) {
                rejected.add(caller + "(调用者重复绑定，仅保留首条)");
                continue;
            }
            map.put(caller, agentId);
        }
        return new CallerAgentBindings(map, rejected, true);
    }

    /** 单条便捷装配（显式注入或单测用，不读环境变量）。 */
    public static CallerAgentBindings of(String caller, String agentId) {
        return parse(caller + ":" + agentId);
    }

    /** 是否一条绑定都没有。 */
    public boolean isEmpty() {
        return bindings.isEmpty();
    }

    /**
     * 是否处于严格模式——即"表非空就必须全绑"。
     *
     * <p>空表返回 {@code false}：不校验，行为回落 V1.8（由启动自检负责告警）。</p>
     */
    public boolean strict() {
        return !bindings.isEmpty();
    }

    public int size() {
        return bindings.size();
    }

    /** 已绑定的调用者名称（保持配置书写顺序）。 */
    public List<String> callers() {
        return new ArrayList<>(bindings.keySet());
    }

    /** 已绑定调用者 → Agent ID（不可变副本，保持书写顺序）。 */
    public Map<String, String> asMap() {
        return bindings;
    }

    /** 被拒条目摘要（{@code 条目(原因)}），可直接进日志与自检。 */
    public List<String> rejected() {
        return rejected;
    }

    /** 整体状态。 */
    public Status status() {
        if (!envPresent) return Status.MISSING;
        if (bindings.isEmpty()) return Status.WEAK;
        return rejected.isEmpty() ? Status.OK : Status.PARTIAL;
    }

    /**
     * 查询调用者绑定的 Agent ID。
     *
     * @return 绑定的 Agent ID；未绑定返回 {@code null}（由调用方决定是拒绝还是回落）
     */
    public String agentFor(String caller) {
        if (caller == null) return null;
        return bindings.get(caller.trim());
    }

    /** 是否已绑定。 */
    public boolean isBound(String caller) {
        return agentFor(caller) != null;
    }

    /**
     * 找出"绑定表里有、令牌表里没有"的调用者。
     *
     * <p>这类条目永远不会生效（没有令牌就认证不出这个名字），是纯粹的配置噪声，
     * 通常是名字拼错。启动期告警比运行期"怎么绑了却没生效"更好查。</p>
     */
    public List<String> callersWithoutToken(ApiCallerRegistry registry) {
        List<String> out = new ArrayList<>();
        if (registry == null) return out;
        List<String> known = registry.callerNames();
        for (String c : bindings.keySet()) {
            if (!known.contains(c)) out.add(c);
        }
        return out;
    }

    /**
     * 找出"令牌表里有、绑定表里没有"的调用者。
     *
     * <p>严格模式下这些调用者会被 403 拒绝——这是设计意图（fail-closed），
     * 但必须在启动时点名，否则运维只会看到"某个令牌突然不能用了"。</p>
     */
    public List<String> callersWithoutBinding(ApiCallerRegistry registry) {
        List<String> out = new ArrayList<>();
        if (registry == null) return out;
        for (String c : registry.callerNames()) {
            if (!bindings.containsKey(c)) out.add(c);
        }
        return out;
    }

    @Override
    public String toString() {
        return "CallerAgentBindings[" + status() + ",size=" + bindings.size()
                + ",rejected=" + rejected.size() + "]";
    }
}
