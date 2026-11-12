package com.towndemontower.gateway.config;

import com.towndemontower.core.mode.AgentMode;

/**
 * [gateway] 服务端配置（docs/38 决策②）。
 *
 * <p>ExecutionContext 由服务端提供：{@code agentId} / {@code mode} / {@code tainted}
 * 都来自这里，请求只带 IntentObject 6 字段 + 可选覆盖字段。
 * 这样"10 分钟接入"才成立——调用方不必自己维护上下文。</p>
 *
 * <p>Java 8、不依赖 Spring：配置从环境变量读取，缺省时用内置默认值。</p>
 */
public class GatewayConfig {

    private String agentId = env("INKARMOR_GATEWAY_AGENT_ID", "gateway-default-agent");
    private String mode = env("INKARMOR_GATEWAY_MODE", "DEV_EXECUTION");
    private boolean tainted = "true".equalsIgnoreCase(env("INKARMOR_GATEWAY_TAINTED", "false"));
    private String auditPath = env("INKARMOR_GATEWAY_AUDIT_PATH", "./data/audit/gateway.log");
    /** 客户端必须携带的令牌；优先取环境变量 INKARMOR_API_TOKEN，未配则禁用并 403。 */
    private String apiToken = env("INKARMOR_API_TOKEN", "");

    /** 请求体硬上限：超过直接 413，避免把整段超大 body 读进内存。 */
    private int maxBodyBytes = intEnv("INKARMOR_GATEWAY_MAX_BODY_BYTES", 4 * 1024 * 1024, 1024);
    /** 工作线程数（固定池，无排队上限——见 docs/39 局限说明）。 */
    private int threads = intEnv("INKARMOR_GATEWAY_THREADS", 8, 1);
    /** 请求/响应超时秒数（交给 JDK HttpServer 的内部开关，0 表示不限）。 */
    private int timeoutSeconds = intEnv("INKARMOR_GATEWAY_TIMEOUT_SECONDS", 30, 0);

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v == null ? fallback : v;
    }

    /** 整数型环境变量：解析失败就退回默认值，宁可保守也不因为写错一个环境变量起不来。 */
    private static int intEnv(String key, int fallback, int min) {
        String raw = env(key, null);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v < min ? fallback : v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String agentId() {
        return agentId;
    }

    public AgentMode mode() {
        return AgentMode.valueOf(mode);
    }

    /** 原始模式名；审计里用字符串，避免模式名写错时连审计都写不出来。 */
    public String modeName() {
        return mode;
    }

    public boolean tainted() {
        return tainted;
    }

    public String auditPath() {
        return auditPath;
    }

    public String apiToken() {
        return apiToken;
    }

    public int maxBodyBytes() {
        return maxBodyBytes;
    }

    public int threads() {
        return threads;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    // 以下 setter 供测试构造与显式装配用。
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public void setMode(AgentMode mode) {
        this.mode = mode.name();
    }

    public void setTainted(boolean tainted) {
        this.tainted = tainted;
    }

    public void setAuditPath(String auditPath) {
        this.auditPath = auditPath;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
