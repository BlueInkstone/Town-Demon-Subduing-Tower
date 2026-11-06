package com.towndemontower.spring;

import com.towndemontower.core.auth.ApiCallerRegistry;
import com.towndemontower.core.auth.AuthMode;
import com.towndemontower.core.auth.CallerContext;
import com.towndemontower.spring.web.ApiAuthFilter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * [starter/test] V1.8 —— 认证过滤器行为（不起容器，直接驱动 Filter）。
 *
 * <p>验证三档模式的边界：loopback 只挡跨机写、token 缺令牌 fail-closed、
 * off 放行；以及环回地址判定覆盖 IPv4 / IPv6 / IPv4-mapped IPv6。</p>
 */
public class V18AuthWiringTest {

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

    static final String TOKEN = "8f3c9e1b5d2048a6c3a9e7d1b5f0c8a2";

    /** 过滤器一次调用的结果。 */
    static final class Out {
        int status;
        boolean reached;
        String body;
        String callerDuring;
        String challenge;
    }

    static Out drive(ApiAuthFilter filter, String method, String uri, String remote, String auth)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRequestURI(uri);
        req.setRemoteAddr(remote);
        if (auth != null) req.addHeader("Authorization", auth);
        MockHttpServletResponse res = new MockHttpServletResponse();
        final String[] seen = new String[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request,
                                 jakarta.servlet.ServletResponse response)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                seen[0] = CallerContext.current();
                super.doFilter(request, response);
            }
        };
        filter.doFilter(req, res, chain);
        Out o = new Out();
        o.status = res.getStatus();
        // 链被调用 ⇔ 过滤器没有直接拒；被拒时 contentType 是过滤器写的 JSON
        o.reached = seen[0] != null || chain.getRequest() != null;
        o.body = res.getContentAsString();
        o.callerDuring = seen[0];
        o.challenge = res.getHeader("WWW-Authenticate");
        return o;
    }

    public static void main(String[] args) throws Exception {
        loopbackMode();
        tokenMode();
        offMode();
        loopbackAddress();
        crossThreadIsolation();
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void loopbackMode() throws Exception {
        ApiAuthFilter f = new ApiAuthFilter(ApiCallerRegistry.empty(), AuthMode.LOOPBACK, "agents");

        Out read = drive(f, "GET", "/api/agents", "10.1.2.3", null);
        check("loopback-跨机读放行", read.status == 200);

        Out writeLocal = drive(f, "POST", "/api/agents", "127.0.0.1", null);
        check("loopback-本机写放行", writeLocal.status == 200);

        Out writeRemote = drive(f, "POST", "/api/agents", "10.1.2.3", null);
        check("loopback-跨机写拒绝", writeRemote.status == 403);
        check("loopback-跨机写拒绝码", writeRemote.body.contains("CALLER_NOT_LOCAL"));

        Out notApi = drive(f, "POST", "/board", "10.1.2.3", null);
        check("loopback-非/api路径不拦", notApi.status == 200);

        Out del = drive(f, "DELETE", "/api/agents/AGT-0001", "10.1.2.3", null);
        check("loopback-跨机删除拒绝", del.status == 403);
    }

    static void tokenMode() throws Exception {
        ApiCallerRegistry registry = ApiCallerRegistry.of("ops", TOKEN);
        ApiAuthFilter f = new ApiAuthFilter(registry, AuthMode.TOKEN, "agents");

        Out noHeader = drive(f, "GET", "/api/agents", "127.0.0.1", null);
        check("token-缺头401", noHeader.status == 401);
        check("token-缺头码", noHeader.body.contains("CALLER_UNAUTHENTICATED"));
        check("token-401携带Bearer挑战（RFC6750）", "Bearer".equals(noHeader.challenge));
        check("token-401不声明已认证调用者", noHeader.callerDuring == null);

        Out wrong = drive(f, "GET", "/api/agents", "127.0.0.1", "Bearer nope");
        check("token-错令牌401", wrong.status == 401);

        Out ok = drive(f, "GET", "/api/agents", "10.1.2.3", "Bearer " + TOKEN);
        check("token-正确令牌放行", ok.status == 200);
        check("token-调用者进入上下文", "ops".equals(ok.callerDuring));

        Out clearedAfter = drive(f, "GET", "/api/agents", "10.1.2.3", "Bearer " + TOKEN);
        check("token-请求后上下文已清理", CallerContext.current() == null);

        ApiAuthFilter noRegistry = new ApiAuthFilter(ApiCallerRegistry.empty(), AuthMode.TOKEN, "agents");
        Out empty = drive(noRegistry, "GET", "/api/agents", "127.0.0.1", "Bearer " + TOKEN);
        check("token-空令牌表全拒", empty.status == 401);
        check("token-空表码为AUTH_TOKEN_UNAVAILABLE",
                empty.body.contains("AUTH_TOKEN_UNAVAILABLE"));

        // 写操作即使来自本机，在 token 模式下也必须带令牌
        Out localWriteNoToken = drive(f, "POST", "/api/agents", "127.0.0.1", null);
        check("token-本机写同样要令牌", localWriteNoToken.status == 401);
    }

    static void offMode() throws Exception {
        ApiAuthFilter f = new ApiAuthFilter(ApiCallerRegistry.empty(), AuthMode.OFF, "agents");
        Out write = drive(f, "POST", "/api/agents", "10.1.2.3", null);
        check("off-跨机写放行", write.status == 200);
        check("off-不写调用者", write.callerDuring == null);
    }

    static void loopbackAddress() {
        check("地址-127.0.0.1", ApiAuthFilter.isLoopback("127.0.0.1"));
        check("地址-127.网段", ApiAuthFilter.isLoopback("127.9.9.9"));
        check("地址-IPv6环回", ApiAuthFilter.isLoopback("::1"));
        check("地址-IPv6展开环回", ApiAuthFilter.isLoopback("0:0:0:0:0:0:0:1"));
        check("地址-IPv4映射环回", ApiAuthFilter.isLoopback("::ffff:127.0.0.1"));
        check("地址-局域网", !ApiAuthFilter.isLoopback("10.1.2.3"));
        check("地址-公网", !ApiAuthFilter.isLoopback("8.8.8.8"));
        check("地址-空", !ApiAuthFilter.isLoopback(null));
        check("地址-伪造前缀不可绕", !ApiAuthFilter.isLoopback("123.0.0.1"));
    }

    static void crossThreadIsolation() {
        CallerContext.set("ops");
        check("上下文-同线程可见", "ops".equals(CallerContext.current()));
        CallerContext.clear();
        check("上下文-清理后不可见", CallerContext.current() == null);
    }
}
