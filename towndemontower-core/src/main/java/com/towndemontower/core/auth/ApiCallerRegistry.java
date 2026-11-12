package com.towndemontower.core.auth;
import java.util.Arrays;

import com.towndemontower.core.security.SecretPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * [core] REST 调用者令牌表。纯 Java，无框架依赖。
 *
 * <p>令牌<b>只</b>来自环境变量 {@value #TOKENS_ENV}，格式为
 * {@code 名称:令牌} 逗号分隔，例如
 * {@code ops:8f3c...（32 位以上）,ci:1a9d...}。名称即调用者身份，
 * 会写进审计与执行上下文的 {@code ownerUserId}。</p>
 *
 * <p>三条硬规则：</p>
 * <ol>
 *   <li><b>不回退</b>：没有合格令牌就是"认证不可用"，{@link #authenticate} 一律拒绝，
 *       不存在"没配就放行"。</li>
 *   <li><b>不落配置文件</b>：只读环境变量，避免令牌被提交进仓库。</li>
 *   <li><b>不存明文</b>：内存里只保留令牌的 SHA-256 摘要，比较用
 *       {@link MessageDigest#isEqual} 做等长全量比较，不短路。</li>
 * </ol>
 */
public final class ApiCallerRegistry {

    /** 令牌表所在的环境变量名。 */
    public static final String TOKENS_ENV = "TOWNDEMONTOWER_API_TOKENS";

    /** 支持的认证方案。 */
    public static final String SCHEME = "Bearer";

    /** 单条令牌条目的合法名称：字母数字与 {@code _ . -}，长度 1~64。 */
    private static final String NAME_PATTERN = "[A-Za-z0-9_.-]{1,64}";

    /** 令牌表整体状态。 */
    public enum Status {
        /** 环境变量未设置。 */
        MISSING,
        /** 全部条目合格。 */
        OK,
        /** 部分条目被拒（仍可用，但要告警）。 */
        PARTIAL,
        /** 设置过但没有任何一条合格——等同于不可用。 */
        WEAK
    }

    /** 认证结果。{@code caller} 仅在 {@code allowed=true} 时非空。 */
    public static final class Auth {
        private final boolean allowed;
        private final String caller;
        private final String code;
        private final String reason;

        private Auth(boolean allowed, String caller, String code, String reason) {
            this.allowed = allowed;
            this.caller = caller;
            this.code = code;
            this.reason = reason;
        }

        static Auth ok(String caller) {
            return new Auth(true, caller, null, null);
        }

        static Auth denied(String code, String reason) {
            return new Auth(false, null, code, reason);
        }

        public boolean allowed() {
            return allowed;
        }

        public String caller() {
            return caller;
        }

        /** 拒绝码（{@code CALLER_UNAUTHENTICATED} / {@code AUTH_TOKEN_UNAVAILABLE}）。 */
        public String code() {
            return code;
        }

        /** 拒绝原因，<b>不含</b>任何令牌内容。 */
        public String reason() {
            return reason;
        }

        @Override
        public String toString() {
            return allowed ? "Auth[ok," + caller + "]" : "Auth[denied," + code + "]";
        }
    }

    private final List<String> names;
    private final List<byte[]> digests;
    private final List<String> rejected;
    private final boolean envPresent;

    private ApiCallerRegistry(List<String> names, List<byte[]> digests,
                              List<String> rejected, boolean envPresent) {
        this.names = new ArrayList<>(names);
        this.digests = new ArrayList<>(digests);
        this.rejected = new ArrayList<>(rejected);
        this.envPresent = envPresent;
    }

    /** 空表（没有任何令牌）。 */
    public static ApiCallerRegistry empty() {
        return new ApiCallerRegistry(Arrays.asList(), Arrays.asList(), Arrays.asList(), false);
    }

    /** 从环境变量装配。未设置或不合格 → 空表，不做任何默认值兜底。 */
    public static ApiCallerRegistry fromEnv() {
        String raw = System.getenv(TOKENS_ENV);
        if (raw == null || raw.trim().isEmpty()) return empty();
        return parse(raw);
    }

    /**
     * 解析 {@code name:token,name2:token2}。
     *
     * <p>被拒条目只记录"名称 + 原因"，不记录令牌内容；全部被拒时返回空表。</p>
     */
    public static ApiCallerRegistry parse(String raw) {
        List<String> names = new ArrayList<>();
        List<byte[]> digests = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) {
            return new ApiCallerRegistry(names, digests, rejected, false);
        }
        for (String entry : raw.split("[,;\\s]+")) {
            if (entry.trim().isEmpty()) continue;
            int idx = entry.indexOf(':');
            if (idx <= 0 || idx == entry.length() - 1) {
                rejected.add(entry.contains(":") ? "条目缺少令牌" : entry + "(缺少 '名称:令牌' 分隔)");
                continue;
            }
            String name = entry.substring(0, idx).trim();
            String token = entry.substring(idx + 1).trim();
            if (!name.matches(NAME_PATTERN)) {
                rejected.add(name + "(名称非法，只允许字母数字与 _ . -，长度 1~64)");
                continue;
            }
            SecretPolicy.Problem p = SecretPolicy.check(token);
            if (p != SecretPolicy.Problem.OK) {
                rejected.add(name + "(" + SecretPolicy.describe(p) + ")");
                continue;
            }
            if (!seen.add(name)) {
                rejected.add(name + "(名称重复，仅保留首条)");
                continue;
            }
            names.add(name);
            digests.add(sha256(token));
        }
        return new ApiCallerRegistry(names, digests, rejected, true);
    }

    /** 单条便捷装配（显式配置或单测用，不读环境变量）。 */
    public static ApiCallerRegistry of(String name, String token) {
        return parse(name + ":" + token);
    }

    /** 是否有可用令牌。 */
    public boolean hasCallers() {
        return !names.isEmpty();
    }

    public int size() {
        return names.size();
    }

    /** 已装配的调用者名称（不含令牌）。 */
    public List<String> callerNames() {
        return names;
    }

    /** 被拒条目摘要（{@code 名称(原因)}），可直接进日志与自检。 */
    public List<String> rejected() {
        return rejected;
    }

    /** 整体状态。 */
    public Status status() {
        if (!envPresent) return Status.MISSING;
        if (names.isEmpty()) return Status.WEAK;
        return rejected.isEmpty() ? Status.OK : Status.PARTIAL;
    }

    /**
     * 校验 {@code Authorization} 头。
     *
     * <p>空表直接拒绝（码 {@code AUTH_TOKEN_UNAVAILABLE}）；表非空但头缺失/不匹配
     * 拒绝（码 {@code CALLER_UNAUTHENTICATED}）。两种情况都不抛异常，由过滤器决定响应。</p>
     */
    public Auth authenticate(String authorizationHeader) {
        if (names.isEmpty()) {
            return Auth.denied("AUTH_TOKEN_UNAVAILABLE",
                    "未配置任何可用调用令牌（环境变量 " + TOKENS_ENV + "）");
        }
        if (authorizationHeader == null || authorizationHeader.trim().isEmpty()) {
            return Auth.denied("CALLER_UNAUTHENTICATED", "缺少 Authorization 头");
        }
        String v = authorizationHeader.trim();
        int sp = v.indexOf(' ');
        if (sp <= 0 || !SCHEME.equalsIgnoreCase(v.substring(0, sp))) {
            return Auth.denied("CALLER_UNAUTHENTICATED", "认证方案必须是 " + SCHEME);
        }
        String token = v.substring(sp + 1).trim();
        if (token.isEmpty()) {
            return Auth.denied("CALLER_UNAUTHENTICATED", "令牌为空");
        }
        byte[] digest = sha256(token);
        // 全量比较、不提前返回：命中位置不影响耗时
        int hit = -1;
        for (int i = 0; i < digests.size(); i++) {
            if (MessageDigest.isEqual(digests.get(i), digest) && hit < 0) hit = i;
        }
        if (hit < 0) {
            return Auth.denied("CALLER_UNAUTHENTICATED", "令牌无效");
        }
        return Auth.ok(names.get(hit));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
