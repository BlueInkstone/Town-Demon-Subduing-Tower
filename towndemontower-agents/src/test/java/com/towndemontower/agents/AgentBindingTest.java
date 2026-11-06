package com.towndemontower.agents;

import com.towndemontower.agents.domain.Agent;
import com.towndemontower.agents.domain.AgentRole;
import com.towndemontower.agents.governance.AgentGovernanceFacade;
import com.towndemontower.agents.governance.BindingReadinessCheck;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.auth.ApiCallerRegistry;
import com.towndemontower.core.auth.CallerAgentBindings;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.mode.AgentMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.net.HttpRetryException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [agents/test] V1.9 / I3-b —— 凭据 → Agent 绑定端到端验证（真实 Tomcat + 真实 HTTP）。
 *
 * <p>这是 I3 真正想关掉的那个口子：<b>V1.8 只证明"你有令牌"，不证明"你代表谁"</b>。
 * 于是任何持令牌的调用者实际都拥有注册表配置身份的全部权限。绑定后：</p>
 * <ol>
 *   <li>绑定到没有 {@code agents:write} 的 Agent → 写操作 <b>403</b>（此前会 201）；</li>
 *   <li>绑定到 {@code agents:write} 的 Agent → 写操作 201，且审计归属到该 Agent；</li>
 *   <li>未绑定 → <b>403 CALLER_NOT_PROVISIONED</b>；</li>
 *   <li>绑定指向不存在 / 非 ACTIVE 的 Agent → <b>403 CALLER_AGENT_UNAVAILABLE</b>。</li>
 * </ol>
 *
 * <p>被绑定的 Agent 通过门面在进程内预先注册（真实的"运维先建号、再发凭据"流程），
 * 因此不需要绕开治理链，也不会把测试专用后门带进产品代码。</p>
 */
public class AgentBindingTest {

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

    /** 三个调用者：ops（有写权）、ci（只读）、ghost（未绑定）。 */
    static final String T_OPS = "8f3c9e1b5d2048a6c3a9e7d1b5f0c8a2";
    static final String T_CI = "1a9d7b5f3c1e04862b8d6a4f2e0c9b7d";
    static final String T_GHOST = "5c2e8a1f7b3d09462e6a8c0f4b2d1e97";

    static final String BOUND_WRITER = "AGT-0001";
    static final String BOUND_READER = "AGT-0002";

    static TestRestTemplate rest = new TestRestTemplate();

    public static void main(String[] args) {
        endToEnd();
        missingAgent();
        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- 装配 ---------- */

    static ConfigurableApplicationContext boot(ApiCallerRegistry registry,
                                               CallerAgentBindings bindings) {
        SpringApplication app = new SpringApplication(AgentWebTest.TestApp.class);
        app.addInitializers(ctx -> {
            ctx.getBeanFactory().registerSingleton("testApiCallerRegistry", registry);
            ctx.getBeanFactory().registerSingleton("testCallerBindings", bindings);
        });
        return app.run("--server.port=0",
                "--towndemontower.agents.demo-data=false",
                "--towndemontower.agents.auth-mode=token");
    }

    static String base(ConfigurableApplicationContext ctx) {
        return "http://127.0.0.1:" + ctx.getEnvironment().getProperty("local.server.port");
    }

    static HttpEntity<Map<String, Object>> withToken(Map<String, Object> body, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.set("Authorization", "Bearer " + token);
        return new HttpEntity<>(body, h);
    }

    static Map<String, Object> agentBody(String name) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("role", "DEVELOPER");
        return b;
    }

    interface Call {
        ResponseEntity<?> run();
    }

    static int status(Call call) {
        try {
            return call.run().getStatusCode().value();
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof HttpRetryException) {
                return ((HttpRetryException) e.getCause()).responseCode();
            }
            throw e;
        }
    }

    /** 运维侧预置：用注册表自身身份建两个 Agent（一个可写、一个只读）。 */
    static Agent[] provision(AgentGovernanceFacade facade) {
        ExecutionContext bootstrap = new ExecutionContext("binding-test", "binding-test",
                "tenant-default", "agents-api-agent", "ops-console", false,
                AgentMode.ARCHITECT_PLANNING);
        Agent writer = facade.register(bootstrap, "ops-writer", AgentRole.OPS,
                List.of(), List.of("agents:read", "agents:write"), null, null, "绑定测试：可写");
        Agent reader = facade.register(bootstrap, "ops-reader", AgentRole.OPS,
                List.of(), List.of("agents:read"), null, null, "绑定测试：只读");
        return new Agent[]{writer, reader};
    }

    /* ---------- ① 端到端 ---------- */

    @SuppressWarnings("unchecked")
    static void endToEnd() {
        ApiCallerRegistry registry = ApiCallerRegistry.parse(
                "ops:" + T_OPS + ",ci:" + T_CI + ",ghost:" + T_GHOST);
        CallerAgentBindings bindings = CallerAgentBindings.parse(
                "ops:" + BOUND_WRITER + ",ci:" + BOUND_READER);

        try (ConfigurableApplicationContext ctx = boot(registry, bindings)) {
            AgentGovernanceFacade facade = ctx.getBean(AgentGovernanceFacade.class);
            Agent[] seeded = provision(facade);
            eq("预置-可写Agent", seeded[0].id(), BOUND_WRITER);
            eq("预置-只读Agent", seeded[1].id(), BOUND_READER);
            String base = base(ctx);

            // 启动体检：预置完成后两条绑定都应判定可用（体检是实时读仓储，不是快照）
            check("体检-预置后无坏绑定", ctx.getBean(BindingReadinessCheck.class)
                    .unusable().isEmpty());

            // ① 绑定到可写 Agent：读与写都放行
            eq("绑定写-读200", rest.exchange(base + "/api/agents", HttpMethod.GET,
                    withToken(null, T_OPS), List.class).getStatusCode().value(), 200);
            ResponseEntity<Map> created = rest.exchange(base + "/api/agents", HttpMethod.POST,
                    withToken(agentBody("by-bound-writer"), T_OPS), Map.class);
            eq("绑定写-注册201", created.getStatusCode().value(), 201);
            String newId = created.getBody() != null
                    ? String.valueOf(created.getBody().get("id")) : "?";

            // ② 绑定到只读 Agent：同一条链、同一个令牌格式，写操作被 RBAC 拒绝
            //    —— 这就是"身份真的换成了绑定的那个 Agent"的证据（此前会 201）
            ResponseEntity<Map> readOnlyWrite = rest.exchange(base + "/api/agents",
                    HttpMethod.POST, withToken(agentBody("by-bound-reader"), T_CI), Map.class);
            eq("绑定只读-写403", readOnlyWrite.getStatusCode().value(), 403);
            check("绑定只读-码为PERMISSION_DENIED", readOnlyWrite.getBody() != null
                    && String.valueOf(readOnlyWrite.getBody().get("error"))
                    .contains("PERMISSION"));
            eq("绑定只读-读200", rest.exchange(base + "/api/agents", HttpMethod.GET,
                    withToken(null, T_CI), List.class).getStatusCode().value(), 200);

            // ③ 令牌有效但未绑定：403 CALLER_NOT_PROVISIONED（不是 401）
            ResponseEntity<Map> ghost = rest.exchange(base + "/api/agents", HttpMethod.GET,
                    withToken(null, T_GHOST), Map.class);
            eq("未绑定-403", ghost.getStatusCode().value(), 403);
            check("未绑定-码", ghost.getBody() != null
                    && String.valueOf(ghost.getBody().get("code")).contains("CALLER_NOT_PROVISIONED"));
            eq("未绑定-写也403", status(() -> rest.exchange(base + "/api/agents", HttpMethod.POST,
                    withToken(agentBody("by-ghost"), T_GHOST), Map.class)), 403);
            // 未绑定的拒绝不能留下"某个 Agent 干过"的假证据
            check("未绑定-不产生SKILL_INVOKED", ctx.getBean(AuditStore.class).all().stream()
                    .noneMatch(r -> "SKILL_INVOKED".equals(r.action)
                            && r.payload.contains("owner=ghost")));

            // ④ 审计归属：写成功那条必须落在绑定的 Agent 上
            //    注意用 endsWith 而不是 contains：预置流程的 owner 是 ops-console，
            //    用 contains("owner=ops") 会把它一起匹配进来（前缀相同），断言就没意义了。
            AuditStore audit = ctx.getBean(AuditStore.class);
            check("审计-归属绑定Agent", audit.all().stream()
                    .anyMatch(r -> "SKILL_INVOKED".equals(r.action)
                            && r.payload.contains("agent=" + BOUND_WRITER)
                            && r.payload.endsWith("owner=ops")));
            check("审计-不含回落身份", audit.all().stream()
                    .filter(r -> "SKILL_INVOKED".equals(r.action))
                    .filter(r -> r.payload.endsWith("owner=ops"))
                    .noneMatch(r -> r.payload.contains("agent=agents-api-agent")));
            check("审计-绑定只读的拒绝也归属到它", audit.all().stream()
                    .anyMatch(r -> "SKILL_INVOKED".equals(r.action)
                            && r.payload.contains("agent=" + BOUND_READER)
                            && r.payload.contains("PERMISSION_DENIED")));

            // ⑤ 绑定的 Agent 被停用：仍在绑定表里，但不能再代表调用者
            ExecutionContext bootstrap = new ExecutionContext("binding-test", "binding-test",
                    "tenant-default", "agents-api-agent", "ops-console", false,
                    AgentMode.ARCHITECT_PLANNING);
            facade.changeStatus(bootstrap, BOUND_WRITER, com.towndemontower.agents.domain.AgentStatus.SUSPENDED);

            ResponseEntity<Map> suspended = rest.exchange(base + "/api/agents", HttpMethod.GET,
                    withToken(null, T_OPS), Map.class);
            eq("停用后-403", suspended.getStatusCode().value(), 403);
            check("停用后-码为CALLER_AGENT_UNAVAILABLE", suspended.getBody() != null
                    && String.valueOf(suspended.getBody().get("error"))
                    .contains("CALLER_AGENT_UNAVAILABLE"));

            // ⑥ 停用不影响另一个仍 ACTIVE 的绑定
            eq("停用后-其他绑定仍200", rest.exchange(base + "/api/agents", HttpMethod.GET,
                    withToken(null, T_CI), List.class).getStatusCode().value(), 200);

            check("收尾-新注册的Agent仍可查", newId.startsWith("AGT-"));
        }
    }

    /* ---------- ② 绑定指向不存在的 Agent ---------- */

    static void missingAgent() {
        ApiCallerRegistry registry = ApiCallerRegistry.parse("solo:" + T_OPS);
        CallerAgentBindings bindings = CallerAgentBindings.of("solo", "AGT-9999");

        try (ConfigurableApplicationContext ctx = boot(registry, bindings)) {
            String base = base(ctx);
            ResponseEntity<Map> r = rest.exchange(base + "/api/agents", HttpMethod.GET,
                    withToken(null, T_OPS), Map.class);
            eq("不存在-403", r.getStatusCode().value(), 403);
            check("不存在-码为CALLER_AGENT_UNAVAILABLE", r.getBody() != null
                    && String.valueOf(r.getBody().get("error")).contains("CALLER_AGENT_UNAVAILABLE"));
            check("不存在-提示含AgentID", r.getBody() != null
                    && String.valueOf(r.getBody().get("message")).contains("AGT-9999"));

            // 启动体检必须点名这条坏绑定，而不是只留一串运行期 403
            var bad = ctx.getBean(BindingReadinessCheck.class).unusable();
            check("不存在-体检点名坏绑定", bad.size() == 1
                    && bad.get(0).contains("solo") && bad.get(0).contains("AGT-9999"));
        }
    }
}
