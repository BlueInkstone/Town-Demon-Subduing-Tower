package com.towndemontower.examples.apiclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 示例用的<b>假治理服务</b>：用 JDK 自带的 {@link HttpServer} 实现 v1.17 管理接口的
 * 一小部分契约，让示例可以"单机直接跑"，不必先启动 Spring 应用。
 *
 * <p>刻意复刻了几个真实行为，方便验证中间件：</p>
 * <ul>
 *   <li>鉴权：缺/错 {@code X-Governance-Token} → 401 + {@code UNAUTHORIZED} 信封；</li>
 *   <li>业务失败：已否决的计划单再批准 → 409 + {@code PLAN_REJECTED} 信封；</li>
 *   <li>暂时性故障：{@code /flaky} 前两次 503，第三次 200（演示重试与退避）；</li>
 *   <li>慢响应：{@code /slow} 睡 700ms（演示读超时）。</li>
 * </ul>
 *
 * <p>接真实服务时把这个类删掉，把 baseUrl 换成 {@code http://host:port} 即可。</p>
 */
public final class DemoServer implements AutoCloseable {

    public static final String TOKEN = "demo-token";
    private static final String PREFIX = "/towndemontower/admin";

    private final HttpServer server;
    private final AtomicInteger flakyCalls = new AtomicInteger();

    private DemoServer(HttpServer server) {
        this.server = server;
    }

    public static DemoServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        DemoServer demo = new DemoServer(server);
        server.createContext("/", demo::handle);
        server.setExecutor(null);
        server.start();
        return demo;
    }

    public int port() { return server.getAddress().getPort(); }

    public String baseUrl() { return "http://127.0.0.1:" + port(); }

    @Override public void close() { server.stop(0); }

    // ------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String query = ex.getRequestURI().getRawQuery();

        // 1) 鉴权：真实服务里这一步由 AdminGuard 做，未配令牌 403、令牌错 401
        if (!TOKEN.equals(ex.getRequestHeaders().getFirst("X-Governance-Token"))) {
            json(ex, 401, envelope(401, "unauthorized", null, "UNAUTHORIZED"));
            return;
        }

        // 2) 路由
        if ("GET".equals(method) && path.equals(PREFIX + "/plans")) {
            json(ex, 200, envelope(0, "ok", PLANS, null));
            return;
        }
        if ("GET".equals(method) && path.startsWith(PREFIX + "/plans/")) {
            String id = path.substring((PREFIX + "/plans/").length());
            json(ex, 200, envelope(0, "ok", planDetail(id), null));
            return;
        }
        if ("POST".equals(method) && path.equals(PREFIX + "/plans/plan-2/approve")) {
            // 已否决的单子再批准：真实服务返回 409 PLAN_REJECTED
            json(ex, 409, envelope(409, "计划已被否决，不能补批", null, "PLAN_REJECTED"));
            return;
        }
        if ("POST".equals(method) && path.endsWith("/approve")) {
            json(ex, 200, envelope(0, "ok", "{\"id\":\"plan-1\",\"status\":\"APPROVED\"}", null));
            return;
        }
        if ("GET".equals(method) && path.equals(PREFIX + "/approvals")) {
            String status = param(query, "status");
            String filtered = status == null ? APPROVALS_ALL : APPROVALS_PENDING;
            json(ex, 200, envelope(0, "ok", filtered, null));
            return;
        }
        if ("GET".equals(method) && path.equals(PREFIX + "/audit")) {
            String q = param(query, "q");
            String data = q == null ? AUDIT_ALL : AUDIT_FILTERED;
            json(ex, 200, envelope(0, "ok", data, null));
            return;
        }
        if ("GET".equals(method) && path.equals(PREFIX + "/flaky")) {
            int n = flakyCalls.incrementAndGet();
            if (n <= 2) {
                json(ex, 503, envelope(503, "服务暂时不可用，请重试", null, "SERVICE_UNAVAILABLE"));
            } else {
                json(ex, 200, envelope(0, "ok", "{\"attempt\":" + n + "}", null));
            }
            return;
        }
        if ("GET".equals(method) && path.equals(PREFIX + "/slow")) {
            sleep(700);
            json(ex, 200, envelope(0, "ok", "{\"sleptMs\":700}", null));
            return;
        }
        json(ex, 404, envelope(404, "路径不存在", null, "NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // 假数据
    // ------------------------------------------------------------------

    private static final String PLANS = """
            {"total":2,"items":[
              {"id":"plan-1","agentId":"agent-a","taskId":"task-42","status":"PENDING",
               "plan":{"summary":"修复登录超时","steps":[{"tool":"readFile","intent":"定位超时点"},
                                                      {"tool":"applyPatch","intent":"加 3s 超时"}]}},
              {"id":"plan-2","agentId":"agent-b","taskId":"task-43","status":"REJECTED",
               "plan":{"summary":"重构审计存储","steps":[{"tool":"applyPatch","intent":"改 FileAuditStore"}]}}
            ]}""";

    private static final String APPROVALS_ALL = """
            {"total":2,"items":[
              {"id":"ap-1","taskId":"task-42","tool":"applyPatch","riskLevel":"R3",
               "impactNote":"修改生产登录模块","requestedBy":"agent-a","status":"PENDING"},
              {"id":"ap-2","taskId":"task-41","tool":"runCommand","riskLevel":"R3",
               "impactNote":"重启网关进程","requestedBy":"agent-b","status":"APPROVED"}
            ]}""";

    private static final String APPROVALS_PENDING = """
            {"total":1,"items":[
              {"id":"ap-1","taskId":"task-42","tool":"applyPatch","riskLevel":"R3",
               "impactNote":"修改生产登录模块","requestedBy":"agent-a","status":"PENDING"}
            ]}""";

    private static final String AUDIT_ALL = """
            {"total":3,"valid":true,"records":3,"items":[
              {"seq":3,"action":"TOOL_INVOKED","payload":"tool=applyPatch task=task-42"},
              {"seq":2,"action":"PLAN_APPROVED","payload":"plan=plan-1"},
              {"seq":1,"action":"PLAN_REQUESTED","payload":"plan=plan-1"}
            ]}""";

    private static final String AUDIT_FILTERED = """
            {"total":1,"valid":true,"records":3,"items":[
              {"seq":2,"action":"PLAN_APPROVED","payload":"plan=plan-1"}
            ]}""";

    private static String planDetail(String id) {
        if ("plan-1".equals(id)) {
            return """
                    {"id":"plan-1","agentId":"agent-a","taskId":"task-42","status":"PENDING",
                     "plan":{"summary":"修复登录超时","steps":[{"tool":"readFile","intent":"定位超时点"},
                                                            {"tool":"applyPatch","intent":"加 3s 超时"}]}}""";
        }
        return "{\"id\":\"" + id + "\",\"status\":\"NOT_FOUND\"}";
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 统一信封：{@code {code,message,data,error}}，与后端 ApiResponse 完全一致。 */
    static String envelope(int code, String message, String dataJson, String error) {
        return "{\"code\":" + code
                + ",\"message\":\"" + message + "\""
                + ",\"data\":" + (dataJson == null ? "null" : dataJson)
                + ",\"error\":" + (error == null ? "null" : "\"" + error + "\"")
                + "}";
    }

    private static String param(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(name)) {
                return pair.substring(i + 1);
            }
        }
        return null;
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
