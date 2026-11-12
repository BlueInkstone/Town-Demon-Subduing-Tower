package com.towndemontower.gateway.core;

import com.towndemontower.core.audit.AuditRecord;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.mode.AgentMode;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [gateway] 裁决引擎测试：GW-01 网关侧 golden（超长参数 → INPUT_REJECTED）+ 合法 R0 放行。
 * Java 8 版本（无 var / Map.of / String.repeat）。
 */
public class GatewayVerdictEngineTest {

    private static final String AGENT = "gw-agent";

    private static GatewayVerdictEngine engine() {
        String path = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                "gw-eng-" + System.nanoTime() + ".log").toString();
        return new GatewayVerdictEngine(AGENT, path);
    }

    private static ExecutionContext ctx() {
        return new ExecutionContext("s1", "t1", "tenant", AGENT, "owner", false, AgentMode.DEV_EXECUTION);
    }

    private static Map<String, Object> arg(String key, Object value) {
        return Collections.<String, Object>singletonMap(key, value);
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    /** GW-01：超长参数（>1MiB）在网关入口层被拒（INPUT_REJECTED），不进入裁决链。 */
    @Test
    void gw01_oversizedArgInputRejected() {
        GatewayVerdictEngine e = engine();
        String big = repeat("x", 2_000_000);
        SkillResult r = e.evaluate(
                new IntentObject("runCompile", arg("module", big), 0.9, "r", "e", "无"), ctx());
        assertEquals(ErrorCodes.INPUT_REJECTED, r.code);
        assertNotNull(r.message);
    }

    /** GW-01：合法 R0 工具（已对服务身份授权）→ 允许。 */
    @Test
    void gw01_validR0Allowed() {
        GatewayVerdictEngine e = engine();
        SkillResult r = e.evaluate(
                new IntentObject("runCompile", arg("module", "x"), 0.9, "编译", "产出", "无"), ctx());
        assertTrue(r.success);
    }

    /** 入参拒绝不进裁决链，但必须留痕（stage=INPUT），否则这类拒绝在审计里不存在。 */
    @Test
    void inputRejectionIsAudited() {
        GatewayVerdictEngine e = engine();
        SkillResult r = e.evaluate(
                new IntentObject("runCompile", arg("module", "a\u0001b"), 0.9, "r", "e", "无"), ctx());
        assertEquals(ErrorCodes.INPUT_REJECTED, r.code);

        String payload = lastRejected(e);
        assertNotNull(payload, "入参拒绝必须写审计");
        assertTrue(payload.startsWith("runCompile|INPUT_REJECTED|stage=INPUT|"), payload);
        assertTrue(payload.contains("task=t1"), payload);
        assertTrue(payload.contains("agent=" + AGENT), payload);
    }

    /** 合法请求走裁决链，由链内 AuditDecorator 记录，不产生入口层记录。 */
    @Test
    void validRequestHasNoEntryRejection() {
        GatewayVerdictEngine e = engine();
        e.evaluate(new IntentObject("runCompile", arg("module", "x"), 0.9, "编译", "产出", "无"), ctx());
        assertEquals(null, lastRejected(e));
    }

    private static String lastRejected(GatewayVerdictEngine e) {
        List<AuditRecord> all = e.audit().all();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (GatewayAudit.ACTION_REJECTED.equals(all.get(i).action)) {
                return all.get(i).payload;
            }
        }
        return null;
    }
}
