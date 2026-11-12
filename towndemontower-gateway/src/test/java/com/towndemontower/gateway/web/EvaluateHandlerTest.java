package com.towndemontower.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.towndemontower.core.audit.AuditRecord;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.gateway.config.GatewayConfig;
import com.towndemontower.gateway.core.GatewayAudit;
import com.towndemontower.gateway.core.GatewayVerdictEngine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [gateway] 处理逻辑测试（无 Spring、无 HTTP）：GW-02 鉴权、GW-05 ExecutionContext 来源、
 * GW-01 网关侧缺字段。直接调用 {@link EvaluateHandler#evaluate}，断言状态码与信封。
 */
public class EvaluateHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 记录最近一次裁决所用的 ExecutionContext，用于断言 GW-05。 */
    static final class RecordingEngine extends GatewayVerdictEngine {
        volatile ExecutionContext lastCtx;

        RecordingEngine() {
            super("cfg-agent",
                    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                            "gw-ctrl-" + System.nanoTime() + ".log").toString());
        }

        @Override
        public SkillResult evaluate(IntentObject intent, ExecutionContext ctx) {
            this.lastCtx = ctx;
            return SkillResult.success(null);
        }
    }

    private GatewayConfig config;
    private RecordingEngine engine;
    private EvaluateHandler handler;

    @BeforeEach
    void setUp() {
        config = new GatewayConfig();
        config.setAgentId("cfg-agent");
        config.setMode(AgentMode.DEV_EXECUTION);
        config.setTainted(false);
        config.setApiToken("secret");

        engine = new RecordingEngine();
        handler = new EvaluateHandler(engine, config);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(String json) throws Exception {
        return MAPPER.readValue(json, Map.class);
    }

    private String intentJson(String tool) {
        return "{\"tool\":\"" + tool + "\",\"args\":{\"module\":\"x\"},\"confidence\":0.9,"
                + "\"reason\":\"r\",\"expectedEffect\":\"e\",\"rollbackHint\":\"无\"}";
    }

    /** GW-02：未配置令牌 → 403（fail-closed）。 */
    @Test
    void gw02_noTokenConfiguredForbidden() throws Exception {
        config.setApiToken("");
        EvaluateHandler.EvalOutcome out = handler.evaluate(body(intentJson("runCompile")), null);
        assertEquals(403, out.status);
        assertEquals("GATEWAY_API_DISABLED", out.response.error);
    }

    /** GW-02：缺失令牌 → 401。 */
    @Test
    void gw02_missingTokenUnauthorized() throws Exception {
        EvaluateHandler.EvalOutcome out = handler.evaluate(body(intentJson("runCompile")), null);
        assertEquals(401, out.status);
        assertEquals("UNAUTHORIZED", out.response.error);
    }

    /** GW-02：错误令牌 → 401。 */
    @Test
    void gw02_wrongTokenUnauthorized() throws Exception {
        EvaluateHandler.EvalOutcome out = handler.evaluate(body(intentJson("runCompile")), "wrong");
        assertEquals(401, out.status);
        assertEquals("UNAUTHORIZED", out.response.error);
    }

    /** GW-02 + 正常通路：正确令牌 → 进入裁决，包回 SkillResult。 */
    @Test
    @SuppressWarnings("unchecked")
    void gw02_validTokenReturnsVerdict() throws Exception {
        EvaluateHandler.EvalOutcome out = handler.evaluate(body(intentJson("runCompile")), "secret");
        assertEquals(200, out.status);
        Map<String, Object> data = (Map<String, Object>) out.response.data;
        assertEquals("OK", data.get("code"));
        assertEquals(Boolean.TRUE, data.get("success"));
    }

    /** GW-05：不传 context → ExecutionContext 取服务端配置。 */
    @Test
    void gw05_contextFromServerConfig() throws Exception {
        handler.evaluate(body(intentJson("runCompile")), "secret");
        ExecutionContext ctx = engine.lastCtx;
        assertEquals("cfg-agent", ctx.agentId);
        assertEquals(AgentMode.DEV_EXECUTION, ctx.mode);
        assertEquals(false, ctx.tainted);
    }

    /** GW-05：传 context 覆盖 → 以请求覆盖字段为准。 */
    @Test
    void gw05_contextOverride() throws Exception {
        String withContext = "{\"tool\":\"runCompile\",\"args\":{\"module\":\"x\"},\"confidence\":0.9,"
                + "\"reason\":\"r\",\"expectedEffect\":\"e\",\"rollbackHint\":\"无\","
                + "\"context\":{\"agentId\":\"req-agent\",\"mode\":\"AUDIT_REVIEWING\",\"tainted\":true}}";
        handler.evaluate(body(withContext), "secret");
        ExecutionContext ctx = engine.lastCtx;
        assertEquals("req-agent", ctx.agentId);
        assertEquals(AgentMode.AUDIT_REVIEWING, ctx.mode);
        assertEquals(true, ctx.tainted);
    }

    /** GW-01（网关侧契约）：缺字段（空 tool）→ 解析层拒绝为 INTENT_FORMAT_INVALID。 */
    @Test
    @SuppressWarnings("unchecked")
    void gw01_missingToolField() throws Exception {
        String json = "{\"tool\":\"\",\"args\":{},\"confidence\":0.9,\"reason\":\"r\","
                + "\"expectedEffect\":\"e\",\"rollbackHint\":\"无\"}";
        EvaluateHandler.EvalOutcome out = handler.evaluate(body(json), "secret");
        assertEquals(200, out.status);
        Map<String, Object> data = (Map<String, Object>) out.response.data;
        assertEquals("INTENT_FORMAT_INVALID", data.get("code"));
    }

    /** 入口层审计：缺令牌 → 401 也要留痕（stage=AUTH + 服务端配置身份）。 */
    @Test
    void entryAudit_missingToken() throws Exception {
        handler.evaluate(body(intentJson("runCompile")), null);
        String payload = lastRejected();
        assertNotNull(payload, "401 必须写审计，否则猜令牌的行为完全不可见");
        assertTrue(payload.startsWith("runCompile|UNAUTHORIZED|stage=AUTH|"), payload);
        assertTrue(payload.contains("mode=DEV_EXECUTION"), payload);
        assertTrue(payload.contains("agent=cfg-agent"), payload);
    }

    /** 入口层审计：服务端未配令牌 → 403 也留痕。 */
    @Test
    void entryAudit_tokenNotConfigured() throws Exception {
        config.setApiToken("");
        handler.evaluate(body(intentJson("runCompile")), "whatever");
        String payload = lastRejected();
        assertNotNull(payload);
        assertTrue(payload.startsWith("runCompile|GATEWAY_API_DISABLED|stage=AUTH|"), payload);
    }

    /** 入口层审计：缺字段被解析层拒绝 → stage=INTENT，工具名落成占位符。 */
    @Test
    void entryAudit_intentParseFailure() throws Exception {
        String json = "{\"tool\":\"\",\"args\":{},\"confidence\":0.9,\"reason\":\"r\"}";
        handler.evaluate(body(json), "secret");
        String payload = lastRejected();
        assertNotNull(payload);
        assertTrue(payload.startsWith("-|INTENT_FORMAT_INVALID|stage=INTENT|"), payload);
    }

    /** 非法 context.mode 属于入参错误：按意图不合法拒绝，而不是变成 500。 */
    @Test
    @SuppressWarnings("unchecked")
    void invalidContextModeIsRejectedNotServerError() throws Exception {
        String json = "{\"tool\":\"runCompile\",\"args\":{\"module\":\"x\"},\"confidence\":0.9,"
                + "\"reason\":\"r\",\"context\":{\"mode\":\"NOT_A_MODE\"}}";
        EvaluateHandler.EvalOutcome out = handler.evaluate(body(json), "secret");
        assertEquals(200, out.status);
        Map<String, Object> data = (Map<String, Object>) out.response.data;
        assertEquals("INTENT_FORMAT_INVALID", data.get("code"));
    }

    /** 取最近一条入口层拒绝记录。 */
    private String lastRejected() {
        List<AuditRecord> all = engine.audit().all();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (GatewayAudit.ACTION_REJECTED.equals(all.get(i).action)) {
                return all.get(i).payload;
            }
        }
        return null;
    }
}
