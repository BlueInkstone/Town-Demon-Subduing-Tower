package com.towndemontower.gateway.web;

import com.sun.net.httpserver.HttpServer;
import com.towndemontower.core.audit.AuditRecord;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.gateway.config.GatewayConfig;
import com.towndemontower.gateway.core.GatewayAudit;
import com.towndemontower.gateway.core.GatewayVerdictEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [gateway] HTTP 层测试（真起一个 JDK HttpServer，端口取 0 由系统分配）。
 *
 * <p>覆盖 {@link EvaluateHandler#evaluate} 测不到的三类：方法不允许、请求体不是 JSON、
 * 请求体超限——它们的判定发生在解析之前，只有走真 HTTP 才碰得到。
 * 同时验证这些入口层拒绝<b>确实写了审计</b>（此前一条都没有）。</p>
 */
public class GatewayHttpTest {

    private HttpServer server;
    private GatewayConfig config;
    private GatewayVerdictEngine engine;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        config = new GatewayConfig();
        config.setAgentId("cfg-agent");
        config.setMode(AgentMode.DEV_EXECUTION);
        config.setApiToken("secret");
        config.setMaxBodyBytes(1024);

        String path = Paths.get(System.getProperty("java.io.tmpdir"),
                "gw-http-" + System.nanoTime() + ".log").toString();
        engine = new GatewayVerdictEngine("cfg-agent", path);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/evaluate", new EvaluateHandler(engine, config));
        server.createContext("/api/health", new HealthHandler());
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static final class Resp {
        final int status;
        final String body;

        Resp(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private Resp call(String method, String path, String token, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (token != null) {
            conn.setRequestProperty("X-Inkarmor-Token", token);
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            in.close();
        }
        conn.disconnect();
        return new Resp(status, sb.toString());
    }

    /** 最近一条入口层拒绝审计的 payload。 */
    private String lastRejected() {
        List<AuditRecord> all = engine.audit().all();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (GatewayAudit.ACTION_REJECTED.equals(all.get(i).action)) {
                return all.get(i).payload;
            }
        }
        return null;
    }

    private static String intentJson(String tool) {
        return "{\"tool\":\"" + tool + "\",\"args\":{\"module\":\"x\"},\"confidence\":0.9,"
                + "\"reason\":\"r\",\"expectedEffect\":\"e\",\"rollbackHint\":\"none\"}";
    }

    /** 非 POST → 405，且写入口审计（stage=METHOD）。 */
    @Test
    void methodNotAllowedIsAudited() throws IOException {
        Resp r = call("GET", "/api/evaluate", "secret", null);
        assertEquals(405, r.status);
        assertTrue(r.body.contains("METHOD_NOT_ALLOWED"), r.body);

        String payload = lastRejected();
        assertNotNull(payload, "非 POST 应当留下审计");
        assertTrue(payload.startsWith("-|METHOD_NOT_ALLOWED|stage=METHOD|"), payload);
        assertTrue(payload.contains("agent=cfg-agent"), payload);
    }

    /** 非法 JSON → 400，且写入口审计（stage=BODY）。 */
    @Test
    void badJsonIsAudited() throws IOException {
        Resp r = call("POST", "/api/evaluate", "secret", "{not json");
        assertEquals(400, r.status);
        assertTrue(r.body.contains("BAD_REQUEST"), r.body);

        String payload = lastRejected();
        assertNotNull(payload);
        assertTrue(payload.startsWith("-|BAD_REQUEST|stage=BODY|"), payload);
    }

    /** 请求体超过上限 → 413，且不把整段 body 读进内存。 */
    @Test
    void oversizedBodyRejected() throws IOException {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\"tool\":\"runCompile\",\"args\":{\"module\":\"");
        while (sb.length() < 2048) {
            sb.append('x');
        }
        sb.append("\"},\"confidence\":0.9}");

        Resp r = call("POST", "/api/evaluate", "secret", sb.toString());
        assertEquals(413, r.status);
        assertTrue(r.body.contains("PAYLOAD_TOO_LARGE"), r.body);

        String payload = lastRejected();
        assertNotNull(payload);
        assertTrue(payload.startsWith("-|PAYLOAD_TOO_LARGE|stage=BODY|"), payload);
    }

    /** 探活：不带令牌也 200，且不写审计（否则探针会把审计刷满）。 */
    @Test
    void healthIsUpAndNotAudited() throws IOException {
        int before = engine.audit().all().size();
        Resp r = call("GET", "/api/health", null, null);
        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"status\":\"up\""), r.body);
        assertEquals(before, engine.audit().all().size(), "探活不应写审计");
    }

    /** 探活只接受 GET。 */
    @Test
    void healthRejectsPost() throws IOException {
        Resp r = call("POST", "/api/health", null, "{}");
        assertEquals(405, r.status);
    }

    /** 正常通路：带令牌的有效请求 → 200 且不产生入口层拒绝记录。 */
    @Test
    void validRequestHasNoEntryRejection() throws IOException {
        Resp r = call("POST", "/api/evaluate", "secret", intentJson("runCompile"));
        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"code\":\"OK\""), r.body);
        assertTrue(lastRejected() == null, "正常请求不应产生 GATEWAY_REJECTED");
    }
}
