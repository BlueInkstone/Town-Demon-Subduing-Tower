package com.towndemontower.spring;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.auth.ApiCallerRegistry;
import com.towndemontower.core.auth.AuthMode;
import com.towndemontower.core.auth.CallerAgentBindings;
import com.towndemontower.core.auth.CallerContext;
import com.towndemontower.spring.web.ApiAuthFilter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * [starter/test] V1.9 / I3-b —— 过滤器层的凭据 → Agent 绑定。
 *
 * <p>不起容器，直接驱动 {@link ApiAuthFilter}，验证四件事：</p>
 * <ol>
 *   <li>严格模式（表非空）下，未绑定的调用者即便令牌正确也 <b>403 CALLER_NOT_PROVISIONED</b>；</li>
 *   <li>绑定命中时 {@link CallerContext} 同时拿到 caller 与 agentId，请求结束一并清理；</li>
 *   <li>空表 / 未传绑定表时行为与 V1.8 完全一致（不校验、agentId 为 null）；</li>
 *   <li>拒绝事件写审计，且审计里不出现令牌明文。</li>
 * </ol>
 */
public class V19CallerBindingWiringTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }

    static void eq(String name, Object actual, Object expected) {
        boolean ok = (expected == null) ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name + " = " + actual);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + " expected=" + expected + " actual=" + actual);
        }
    }

    static final String TOKEN = "8f3c9e1b5d2048a6c3a9e7d1b5f0c8a2";
    static final String OTHER = "1a9d7b5f3c1e04862b8d6a4f2e0c9b7d";

    /** 过滤器一次调用的结果。 */
    static final class Out {
        int status;
        String body;
        String callerDuring;
        String agentDuring;
        boolean boundDuring;
    }

    static Out drive(ApiAuthFilter filter, String method, String uri, String remote, String auth)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRequestURI(uri);
        req.setRemoteAddr(remote);
        if (auth != null) req.addHeader("Authorization", "Bearer " + auth);
        MockHttpServletResponse res = new MockHttpServletResponse();
        final String[] caller = new String[1];
        final String[] agent = new String[1];
        final boolean[] bound = new boolean[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request,
                                 jakarta.servlet.ServletResponse response)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                caller[0] = CallerContext.current();
                agent[0] = CallerContext.agentId();
                bound[0] = CallerContext.bound();
                super.doFilter(request, response);
            }
        };
        filter.doFilter(req, res, chain);
        Out o = new Out();
        o.status = res.getStatus();
        o.body = res.getContentAsString();
        o.callerDuring = caller[0];
        o.agentDuring = agent[0];
        o.boundDuring = bound[0];
        return o;
    }

    public static void main(String[] args) throws Exception {
        strictBinding();
        unboundCallerDenied();
        emptyBindingsFallBack();
        noBindingsAtAll();
        loopbackIgnoresBindings();
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- ① 严格模式：绑定命中 ---------- */

    static void strictBinding() throws Exception {
        AuditStore audit = new HashChainAuditStore();
        ApiAuthFilter filter = new ApiAuthFilter(
                ApiCallerRegistry.of("ops", TOKEN), AuthMode.TOKEN, "agents", audit,
                CallerAgentBindings.of("ops", "AGT-0001"));

        Out read = drive(filter, "GET", "/api/agents", "10.0.0.9", TOKEN);
        eq("绑定-读200", read.status, 200);
        eq("绑定-读到caller", read.callerDuring, "ops");
        eq("绑定-读到agentId", read.agentDuring, "AGT-0001");
        check("绑定-链内为已绑定态", read.boundDuring);

        Out write = drive(filter, "POST", "/api/agents", "10.0.0.9", TOKEN);
        eq("绑定-写200（跨机也放行，因为是token模式）", write.status, 200);
        eq("绑定-写agentId", write.agentDuring, "AGT-0001");

        // 请求结束后必须清干净，否则线程复用会串身份
        eq("绑定-出栈后caller清空", CallerContext.current(), null);
        eq("绑定-出栈后agent清空", CallerContext.agentId(), null);
        check("绑定-出栈后未绑定", !CallerContext.bound());
        check("绑定-无拒绝审计", audit.all().stream()
                .noneMatch(r -> "API_CALLER_DENIED".equals(r.action)));
    }

    /* ---------- ② 严格模式：未绑定 ---------- */

    static void unboundCallerDenied() throws Exception {
        AuditStore audit = new HashChainAuditStore();
        ApiCallerRegistry registry = ApiCallerRegistry.parse("ops:" + TOKEN + ",ci:" + OTHER);
        ApiAuthFilter filter = new ApiAuthFilter(
                registry, AuthMode.TOKEN, "agents", audit,
                CallerAgentBindings.of("ops", "AGT-0001"));

        // ci 的令牌有效，但不在绑定表里 —— 令牌对 ≠ 允许代表 Agent
        Out ci = drive(filter, "GET", "/api/agents", "10.0.0.9", OTHER);
        eq("未绑定-403", ci.status, 403);
        check("未绑定-码为CALLER_NOT_PROVISIONED", ci.body.contains("CALLER_NOT_PROVISIONED"));
        eq("未绑定-不写caller", ci.callerDuring, null);
        eq("未绑定-不写agent", ci.agentDuring, null);
        check("未绑定-拒绝事件进审计", audit.all().stream()
                .anyMatch(r -> "API_CALLER_DENIED".equals(r.action)
                        && r.payload.contains("CALLER_NOT_PROVISIONED")));
        check("未绑定-审计含模块与来源", audit.all().stream()
                .anyMatch(r -> "API_CALLER_DENIED".equals(r.action)
                        && r.payload.contains("agents") && r.payload.contains("10.0.0.9")));
        check("未绑定-审计不含令牌", audit.all().stream()
                .noneMatch(r -> r.payload != null && r.payload.contains(OTHER)));
        check("未绑定-审计不含AgentID细节以外的敏感串", audit.all().stream()
                .filter(r -> "API_CALLER_DENIED".equals(r.action))
                .allMatch(r -> r.payload.startsWith("CALLER_NOT_PROVISIONED|agents|")));

        // 令牌无效仍然先撞 401，不会被绑定判定掩盖
        Out badToken = drive(filter, "GET", "/api/agents", "10.0.0.9", "deadbeef");
        eq("未绑定-错令牌仍401", badToken.status, 401);
        check("未绑定-错令牌码", badToken.body.contains("CALLER_UNAUTHENTICATED"));

        // 绑定命中者不受影响，说明拒绝是逐调用者的
        eq("未绑定-已绑定者仍200",
                drive(filter, "GET", "/api/agents", "10.0.0.9", TOKEN).status, 200);
    }

    /* ---------- ③ 空表：回落 V1.8 ---------- */

    static void emptyBindingsFallBack() throws Exception {
        ApiAuthFilter filter = new ApiAuthFilter(
                ApiCallerRegistry.of("ops", TOKEN), AuthMode.TOKEN, "agents",
                new HashChainAuditStore(), CallerAgentBindings.none());

        Out o = drive(filter, "GET", "/api/agents", "10.0.0.9", TOKEN);
        eq("空表-放行", o.status, 200);
        eq("空表-仍记caller", o.callerDuring, "ops");
        eq("空表-agent为空", o.agentDuring, null);
        check("空表-非绑定态", !o.boundDuring);
    }

    /* ---------- ④ 没传绑定表：同样回落 ---------- */

    static void noBindingsAtAll() throws Exception {
        ApiAuthFilter filter = new ApiAuthFilter(
                ApiCallerRegistry.of("ops", TOKEN), AuthMode.TOKEN, "delivery",
                new HashChainAuditStore());

        eq("无绑定表-构造可读回null", filter.bindings(), null);
        Out o = drive(filter, "POST", "/api/deliveries", "10.0.0.9", TOKEN);
        eq("无绑定表-放行", o.status, 200);
        eq("无绑定表-agent为空", o.agentDuring, null);
    }

    /* ---------- ⑤ loopback 模式不做绑定 ---------- */

    static void loopbackIgnoresBindings() throws Exception {
        ApiAuthFilter filter = new ApiAuthFilter(
                ApiCallerRegistry.of("ops", TOKEN), AuthMode.LOOPBACK, "agents",
                new HashChainAuditStore(), CallerAgentBindings.of("someone", "AGT-0001"));

        // loopback 下没有"调用者"这一说：本机写放行，agentId 为 null
        Out local = drive(filter, "POST", "/api/agents", "127.0.0.1", null);
        eq("loopback-本机写放行", local.status, 200);
        eq("loopback-agent为空", local.agentDuring, null);

        Out remote = drive(filter, "POST", "/api/agents", "10.0.0.9", null);
        eq("loopback-跨机写403", remote.status, 403);
        check("loopback-码为CALLER_NOT_LOCAL", remote.body.contains("CALLER_NOT_LOCAL"));
    }
}
