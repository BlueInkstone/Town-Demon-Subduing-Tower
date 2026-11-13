package com.towndemontower.gateway.web;

import com.sun.net.httpserver.HttpServer;

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
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [gateway] 裁决控制台的 HTTP 层测试（真起 JDK HttpServer，端口 0 由系统分配）。
 *
 * <p>覆盖四件容易在重构中悄悄坏掉的事：</p>
 * <ul>
 *   <li>页面资源确实被打进了 jar（{@code /console/index.html} 缺失时构造就抛异常）；</li>
 *   <li>三个入口路径都能出页面，未知路径是 404 而不是被静默当成首页；</li>
 *   <li>只接受 GET/HEAD，POST 返回 405——控制台不该成为一个能提交表单的写入口；</li>
 *   <li>页面里<b>不含任何令牌</b>：这是静态资源，就算服务端配了 token 也不会漏到浏览器里。</li>
 * </ul>
 */
public class ConsoleHandlerTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new ConsoleHandler());
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
        final String contentType;
        final String body;

        Resp(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }

    private Resp call(String method, String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            in.close();
        }
        String contentType = conn.getContentType();
        conn.disconnect();
        return new Resp(status, contentType, sb.toString());
    }

    @Test
    void rootServesConsolePage() throws IOException {
        Resp r = call("GET", "/", null);
        assertEquals(200, r.status);
        assertTrue(r.contentType != null && r.contentType.contains("text/html"), r.contentType);
        assertTrue(r.contentType.contains("charset=utf-8"), "页面含中文，必须声明 charset：" + r.contentType);
        // 用 ASCII 标记断言，避免把编码问题混进内容断言
        assertTrue(r.body.contains("/api/evaluate"), "页面应当提示接口地址");
        assertTrue(r.body.contains("X-Inkarmor-Token"), "页面应当提示令牌头名称");
    }

    @Test
    void consolePathsServePage() throws IOException {
        assertTrue(call("GET", "/console", null).body.contains("/api/evaluate"));
        assertTrue(call("GET", "/console/", null).body.contains("/api/evaluate"));
    }

    @Test
    void unknownPathIs404NotHome() throws IOException {
        Resp r = call("GET", "/admin", null);
        assertEquals(404, r.status);
        assertTrue(r.body.contains("/admin"), "404 应当说明是哪个路径不存在");
        assertFalse(r.body.contains("<html"), "404 不应把首页当兜底");
    }

    @Test
    void postToConsoleIs405() throws IOException {
        Resp r = call("POST", "/console", "{}");
        assertEquals(405, r.status);
    }

    @Test
    void pageNeverPrefillsToken() throws IOException {
        // 控制台是静态页：令牌只能由使用者在输入框里填，页面不预填、不明文展示
        String html = call("GET", "/console", null).body;
        assertTrue(html.contains("type=\"password\""), "令牌输入框应当是 password 类型");
        assertFalse(html.toLowerCase().contains("value=\"secret\""), "页面不应预置任何令牌值");
    }

    @Test
    void headRequestHasNoBody() throws IOException {
        Resp r = call("HEAD", "/console", null);
        assertEquals(200, r.status);
        assertEquals("", r.body, "HEAD 不应返回正文");
    }
}
