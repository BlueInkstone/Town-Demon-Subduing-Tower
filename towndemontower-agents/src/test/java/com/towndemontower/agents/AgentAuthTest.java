package com.towndemontower.agents;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.auth.ApiCallerRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.net.HttpRetryException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [agents/test] V1.8 —— REST 调用者认证端到端验证（真实 Tomcat + 真实 HTTP）。
 *
 * <p>验证三件事：</p>
 * <ol>
 *   <li>{@code token} 模式下没令牌 → 401，且拒绝事件进审计链；</li>
 *   <li>带正确令牌 → 放行并可正常写；令牌只认 {@code Authorization} 头，查询参数无效；</li>
 *   <li>{@code auth-mode} 写错 → <b>启动即失败</b>，不做静默回落。</li>
 * </ol>
 *
 * <p>令牌表通过 {@code registerSingleton} 在 refresh 之前注入，因此仍然走
 * {@code @ConditionalOnMissingBean} 的那条正常装配路径——生产环境对应的来源是
 * 环境变量 {@code TOWNDEMONTOWER_API_TOKENS}（进程内无法改环境变量，故此处显式注入）。</p>
 */
public class AgentAuthTest {

    static int pass = 0;
    static int fail = 0;

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

    static TestRestTemplate rest = new TestRestTemplate();

    public static void main(String[] args) {
        tokenWithoutRegistry();
        tokenWithRegistry();
        invalidModeFailsFast();
        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ------------------------------------------------------------------ */

    static ConfigurableApplicationContext boot(String mode, ApiCallerRegistry registry) {
        SpringApplication app = new SpringApplication(AgentWebTest.TestApp.class);
        if (registry != null) {
            app.addInitializers(ctx -> ctx.getBeanFactory()
                    .registerSingleton("testApiCallerRegistry", registry));
        }
        return app.run("--server.port=0",
                "--towndemontower.agents.demo-data=false",
                "--towndemontower.agents.auth-mode=" + mode);
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

    static Map<String, Object> agentBody() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", "auth-probe");
        b.put("role", "DEVELOPER");
        return b;
    }

    /** 取响应的状态码。 */
    interface Call {
        ResponseEntity<?> run();
    }

    /**
     * 取状态码；把 JDK 的 {@link HttpRetryException} 归一成 401。
     *
     * <p>JDK 的 {@code HttpURLConnection} 在「带 body 的请求 + 401」时会抛
     * {@code HttpRetryException}，与响应头无关（任何 401 都这样）。这是客户端限制，
     * 不是服务端行为问题；真实客户端（Apache HttpClient / OkHttp / 浏览器）能正常拿到 401。</p>
     */
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

    /* ---------- ① token 模式但没有任何令牌：一律拒绝 ---------- */

    @SuppressWarnings("unchecked")
    static void tokenWithoutRegistry() {
        try (ConfigurableApplicationContext ctx = boot("token", null)) {
            String base = base(ctx);

            ResponseEntity<Map> read = rest.getForEntity(base + "/api/agents", Map.class);
            eq("无令牌表-读401", read.getStatusCode(), HttpStatus.UNAUTHORIZED);
            check("无令牌表-拒绝码",
                    String.valueOf(read.getBody().get("code")).contains("AUTH_TOKEN_UNAVAILABLE"));

            eq("无令牌表-写401", status(() -> rest.exchange(base + "/api/agents", HttpMethod.POST,
                    withToken(agentBody(), null), Map.class)), HttpStatus.UNAUTHORIZED.value());

            // 连本机也不放行：token 模式没有"本机例外"
            ResponseEntity<Map> local = rest.getForEntity(base + "/api/agents", Map.class);
            eq("无令牌表-本机同样拒绝", local.getStatusCode(), HttpStatus.UNAUTHORIZED);
        }
    }

    /* ---------- ② token 模式且有令牌：头有效才放行 ---------- */

    @SuppressWarnings("unchecked")
    static void tokenWithRegistry() {
        ApiCallerRegistry registry = ApiCallerRegistry.of("ops", TOKEN);
        try (ConfigurableApplicationContext ctx = boot("token", registry)) {
            String base = base(ctx);

            ResponseEntity<Map> noHeader = rest.getForEntity(base + "/api/agents", Map.class);
            eq("认证-缺头401", noHeader.getStatusCode(), HttpStatus.UNAUTHORIZED);
            check("认证-缺头码",
                    String.valueOf(noHeader.getBody().get("code")).contains("CALLER_UNAUTHENTICATED"));

            ResponseEntity<Map> wrong = rest.exchange(base + "/api/agents", HttpMethod.GET,
                    withToken(null, "deadbeef"), Map.class);
            eq("认证-错令牌401", wrong.getStatusCode(), HttpStatus.UNAUTHORIZED);

            ResponseEntity<List> ok = rest.exchange(base + "/api/agents", HttpMethod.GET,
                    withToken(null, TOKEN), List.class);
            eq("认证-正确令牌200", ok.getStatusCode(), HttpStatus.OK);

            ResponseEntity<Map> created = rest.exchange(base + "/api/agents", HttpMethod.POST,
                    withToken(agentBody(), TOKEN), Map.class);
            eq("认证-带令牌可写201", created.getStatusCode(), HttpStatus.CREATED);
            String id = created.getBody() != null ? String.valueOf(created.getBody().get("id")) : "?";
            check("认证-写成功返回AGT", id.startsWith("AGT-"));

            eq("认证-删除无令牌401", status(() -> rest.exchange(base + "/api/agents/" + id,
                    HttpMethod.DELETE, withToken(null, null), Map.class)),
                    HttpStatus.UNAUTHORIZED.value());

            // 令牌只认头：查询参数不构成认证（防"令牌进 URL 落日志"）
            ResponseEntity<Map> viaQuery = rest.getForEntity(
                    base + "/api/agents?token=" + TOKEN, Map.class);
            eq("认证-查询参数令牌无效", viaQuery.getStatusCode(), HttpStatus.UNAUTHORIZED);

            // 拒绝事件必须留痕
            AuditStore audit = ctx.getBean(AuditStore.class);
            check("认证-拒绝事件进审计", audit.all().stream()
                    .anyMatch(a -> "API_CALLER_DENIED".equals(a.action)));
            check("认证-拒绝留痕含模块", audit.all().stream()
                    .filter(a -> "API_CALLER_DENIED".equals(a.action))
                    .anyMatch(a -> a.payload != null && a.payload.contains("agents")));
            check("认证-审计不含令牌明文", audit.all().stream()
                    .noneMatch(a -> a.payload != null && a.payload.contains(TOKEN)));
        }
    }

    /* ---------- ③ 模式写错必须启动失败 ---------- */

    static void invalidModeFailsFast() {
        boolean failed = false;
        StringBuilder msg = new StringBuilder();
        try (ConfigurableApplicationContext ignored = boot("token-ish", null)) {
            // 不应走到这里
        } catch (RuntimeException e) {
            failed = true;
            for (Throwable t = e; t != null; t = t.getCause()) {
                msg.append(t.getMessage()).append('|');
            }
        }
        check("配置-auth-mode写错启动失败", failed);
        check("配置-失败信息指出合法值",
                msg.toString().contains("auth-mode") || msg.toString().contains("loopback"));
    }
}
