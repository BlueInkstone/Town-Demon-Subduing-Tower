package com.towndemontower.gateway.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.gateway.config.GatewayConfig;
import com.towndemontower.gateway.core.GatewayAudit;
import com.towndemontower.gateway.core.GatewayVerdictEngine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;

/**
 * [gateway] 裁决入口：{@code POST /api/evaluate}（docs/38 改动②）。
 *
 * <p>JDK 内置 {@link HttpHandler}，不依赖 Spring。每次调用都写审计、需独立鉴权、
 * 失败时拒绝：鉴权缺失/错误 → 401/403（fail-closed）；鉴权通过后才进入裁决链。
 * 核心逻辑放在 {@link #evaluate(Map, String)}，与 HTTP 解耦，便于单测。</p>
 *
 * <p><b>入口层拒绝也写审计</b>：缺/错令牌、非 POST、非法 JSON、请求体超限、缺字段
 * 这些"没进裁决链"的请求，由本类补记 {@link GatewayAudit}。此前它们一条记录都没有，
 * 导致"有人在猜令牌"在审计里完全不可见。两条写入路径共用同一个
 * {@link AuditStore} 实例（从 {@link GatewayVerdictEngine#audit()} 取），否则会各写各的段头把链弄坏。</p>
 *
 * <p><b>请求体上限</b>：超过 {@code INKARMOR_GATEWAY_MAX_BODY_BYTES}（默认 4 MiB）直接 413，
 * 不把整段超大 body 读进内存——先前提的是"单参数 ≤ 1MiB"，但那要等 body 全读进来、JSON 解析完
 * 才轮得到检查。</p>
 */
public class EvaluateHandler implements HttpHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN_HEADER = "X-Inkarmor-Token";

    private final GatewayVerdictEngine engine;
    private final GatewayConfig config;
    private final AuditStore audit;

    public EvaluateHandler(GatewayVerdictEngine engine, GatewayConfig config) {
        this(engine, config, engine == null ? null : engine.audit());
    }

    /**
     * @param audit 与裁决链共用的审计存储，可为 null（单测里如果不关心入口审计）
     */
    public EvaluateHandler(GatewayVerdictEngine engine, GatewayConfig config, AuditStore audit) {
        this.engine = engine;
        this.config = config;
        this.audit = audit;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                auditReject(GatewayAudit.UNKNOWN_TOOL, "METHOD_NOT_ALLOWED", GatewayAudit.STAGE_METHOD, null);
                send(exchange, 405, ApiResponse.fail(405, "METHOD_NOT_ALLOWED", "仅支持 POST"));
                return;
            }
            String token = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
            Map<String, Object> body;
            try {
                byte[] raw = readAll(exchange.getRequestBody(), config.maxBodyBytes());
                body = MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {
                });
            } catch (PayloadTooLargeException e) {
                auditReject(GatewayAudit.UNKNOWN_TOOL, "PAYLOAD_TOO_LARGE", GatewayAudit.STAGE_BODY, null);
                send(exchange, 413, ApiResponse.fail(413, "PAYLOAD_TOO_LARGE",
                        "请求体超过 " + config.maxBodyBytes() + " 字节上限"));
                return;
            } catch (Exception e) {
                auditReject(GatewayAudit.UNKNOWN_TOOL, "BAD_REQUEST", GatewayAudit.STAGE_BODY, null);
                send(exchange, 400, ApiResponse.fail(400, "BAD_REQUEST", "请求体不是合法 JSON"));
                return;
            }
            EvalOutcome out = evaluate(body, token);
            send(exchange, out.status, out.response);
        } finally {
            exchange.close();
        }
    }

    /**
     * 纯逻辑（无 HTTP）：鉴权 → 解析 IntentObject → 构建 ExecutionContext → 裁决。
     * 供单元测试直接调用。
     */
    EvalOutcome evaluate(Map<String, Object> body, String token) {
        // 1) 鉴权：缺失/错误令牌 → 401/403（fail-closed）
        String expected = config.apiToken();
        if (expected == null || expected.trim().isEmpty()) {
            auditReject(toolOf(body), "GATEWAY_API_DISABLED", GatewayAudit.STAGE_AUTH, null);
            return new EvalOutcome(403, ApiResponse.fail(403, "GATEWAY_API_DISABLED",
                    "网关未配置访问令牌：请设置 INKARMOR_API_TOKEN"));
        }
        if (token == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            auditReject(toolOf(body), "UNAUTHORIZED", GatewayAudit.STAGE_AUTH, null);
            return new EvalOutcome(401, ApiResponse.fail(401, "UNAUTHORIZED", "无效的网关令牌"));
        }

        // 2) 解析 IntentObject（6 字段）+ 构造上下文；两者任一非法都按"意图不合法"处理
        IntentObject intent;
        ExecutionContext ctx;
        try {
            intent = parseIntent(body);
            ctx = buildContext(body);
        } catch (IllegalArgumentException ex) {
            auditReject(toolOf(body), ErrorCodes.INTENT_FORMAT_INVALID, GatewayAudit.STAGE_INTENT, null);
            return new EvalOutcome(200, ApiResponse.ok(
                    SkillResult.denied(ErrorCodes.INTENT_FORMAT_INVALID, ex.getMessage()).toWire()));
        }

        // 3) 裁决
        SkillResult result = engine.evaluate(intent, ctx);
        return new EvalOutcome(200, ApiResponse.ok(result.toWire()));
    }

    /**
     * 写一条入口层拒绝审计。
     *
     * <p>此时通常还没有 {@link ExecutionContext}（鉴权阶段连 body 都没解析），
     * 归属退回到服务端配置身份——这是事实：请求确实打到了这个配置身份上。</p>
     */
    private void auditReject(String tool, String code, String stage, ExecutionContext ctx) {
        if (ctx != null) {
            GatewayAudit.record(audit, tool, code, stage, ctx);
        } else {
            GatewayAudit.record(audit, tool, code, stage, null,
                    config.modeName(), config.agentId(), null);
        }
    }

    @SuppressWarnings("unchecked")
    private IntentObject parseIntent(Map<String, Object> body) {
        Object tool = body.get("tool");
        if (!(tool instanceof String) || ((String) tool).isEmpty()) {
            throw new IllegalArgumentException("intent.tool 缺失或为空");
        }
        Object argsObj = body.get("args");
        Map<String, Object> args = (argsObj instanceof Map)
                ? (Map<String, Object>) argsObj
                : Collections.<String, Object>emptyMap();

        Object confidenceObj = body.get("confidence");
        if (!(confidenceObj instanceof Number)) {
            throw new IllegalArgumentException("intent.confidence 缺失或非法");
        }
        double confidence = ((Number) confidenceObj).doubleValue();

        return new IntentObject(
                (String) tool,
                args,
                confidence,
                asString(body.get("reason")),
                asString(body.get("expectedEffect")),
                asString(body.get("rollbackHint")));
    }

    @SuppressWarnings("unchecked")
    private ExecutionContext buildContext(Map<String, Object> body) {
        Map<String, Object> override = (body.get("context") instanceof Map)
                ? (Map<String, Object>) body.get("context")
                : Collections.<String, Object>emptyMap();

        String agentId = pick(override, "agentId", config.agentId());
        AgentMode mode;
        try {
            mode = AgentMode.valueOf(pick(override, "mode", config.mode().name()));
        } catch (IllegalArgumentException ex) {
            // 非法模式名不该变成 500（HttpServer 会把未捕获异常翻成 5xx）：
            // 它是调用方的入参错误，按意图不合法拒绝。
            throw new IllegalArgumentException("context.mode 非法：" + override.get("mode"));
        }
        Object taintedObj = override.get("tainted");
        boolean tainted = (taintedObj instanceof Boolean) ? ((Boolean) taintedObj) : config.tainted();
        String sessionId = pick(override, "sessionId", null);
        String taskId = pick(override, "taskId", null);
        String tenantId = pick(override, "tenantId", null);
        String ownerUserId = pick(override, "ownerUserId", null);

        return new ExecutionContext(sessionId, taskId, tenantId, agentId, ownerUserId, tainted, mode);
    }

    private static String toolOf(Map<String, Object> body) {
        Object tool = body == null ? null : body.get("tool");
        return (tool instanceof String && !((String) tool).isEmpty())
                ? (String) tool : GatewayAudit.UNKNOWN_TOOL;
    }

    private static String pick(Map<String, Object> override, String key, String fallback) {
        Object v = override.get(key);
        if (v instanceof String && !((String) v).isEmpty()) {
            return (String) v;
        }
        return fallback;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static void send(HttpExchange exchange, int status, ApiResponse response) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /** 边读边计数，超过上限立刻抛——不把超大 body 全读进内存。 */
    private static byte[] readAll(InputStream in, int limit) throws IOException, PayloadTooLargeException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (bos.size() + n > limit) {
                throw new PayloadTooLargeException(limit);
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** 请求体超过上限。 */
    static final class PayloadTooLargeException extends Exception {
        PayloadTooLargeException(int limit) {
            super("body exceeds " + limit + " bytes");
        }
    }

    /** HTTP 状态 + 响应信封（纯数据，便于断言）。 */
    static final class EvalOutcome {
        final int status;
        final ApiResponse response;

        EvalOutcome(int status, ApiResponse response) {
            this.status = status;
            this.response = response;
        }
    }
}
