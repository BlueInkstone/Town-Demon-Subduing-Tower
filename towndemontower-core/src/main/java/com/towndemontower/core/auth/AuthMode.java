package com.towndemontower.core.auth;

import java.util.Locale;

/**
 * [core] REST 接口的调用者认证模式。
 *
 * <p>默认 {@link #LOOPBACK}：既不打破本地演示与既有测试（本机调用照旧），
 * 又让"局域网内任何人都能 POST /api/agents"这件事不再成立。</p>
 */
public enum AuthMode {

    /**
     * 只读放行、写操作限本机。
     *
     * <p>GET/HEAD/OPTIONS 不校验；其余方法要求 {@code remoteAddr} 属于环回地址。
     * 适用于"只在本机演示或已由外层网关鉴权"的场景。</p>
     */
    LOOPBACK,

    /**
     * 令牌认证。所有受保护路径都必须携带 {@code Authorization: Bearer <token>}。
     *
     * <p>令牌只从环境变量 {@code TOWNDEMONTOWER_API_TOKENS} 读取；没配到合格令牌时
     * <b>一律拒绝</b>（不是"没配就放行"）。</p>
     */
    TOKEN,

    /**
     * 不校验。仅用于被外层强制鉴权包住的场景，启动时记 WARN，自检报 finding。
     */
    OFF;

    /** 宽松解析：未知值返回 {@code null}，由调用方决定是报错还是回落默认。 */
    public static AuthMode parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("NONE".equals(v) || "DISABLED".equals(v)) return OFF;
        if ("LOCAL".equals(v) || "LOCALHOST".equals(v)) return LOOPBACK;
        if ("BEARER".equals(v) || "API_TOKEN".equals(v)) return TOKEN;
        for (AuthMode m : values()) {
            if (m.name().equals(v)) return m;
        }
        return null;
    }
}
