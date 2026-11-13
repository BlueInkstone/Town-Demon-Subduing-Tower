package com.towndemontower.gateway.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * [gateway/web] 裁决控制台的静态页服务（V2.1）。
 *
 * <p>网关此前只有两个机器接口（{@code POST /api/evaluate}、{@code GET /api/health}），
 * 想验证"这条意图会被判成几级"只能自己拼 curl。本类只做一件事：把打包进 jar 的
 * {@code /console/index.html} 原样吐给浏览器，让调用方在页面上填意图、看裁决。</p>
 *
 * <p><b>不引入任何依赖</b>：JDK 7 起就有 {@link StandardCharsets}，页面在构造时一次性读进内存
 * （单文件几十 KB，不值得每次请求再读盘）；此处仍保持零第三方依赖、Java 8 可编译。</p>
 *
 * <p><b>安全边界</b>：控制台本身不含任何秘密，也不做认证——它只是一张表单，
 * 真正提交时走的是 {@code /api/evaluate}，令牌由使用者在页面上填、随请求头带上。
 * 换句话说：能打开这个页面 ≠ 能裁决；没有 {@code INKARMOR_API_TOKEN} 一样被 401/403 挡回。</p>
 *
 * <p><b>不算能力</b>：这不是管理后台。没有历史查询、没有审计检索、没有调用统计——
 * 审计是落盘证据（{@code ./data/audit/gateway.log}），要查就查文件，网关不提供读接口。</p>
 */
public final class ConsoleHandler implements HttpHandler {

    /** 控制台页面在 classpath 中的位置（打包自 src/main/resources/console/index.html）。 */
    private static final String PAGE_RESOURCE = "/console/index.html";

    private static final String PAGE_PATH = "/console";

    private final byte[] page;

    public ConsoleHandler() {
        this.page = loadPage();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if ("/".equals(path) || PAGE_PATH.equals(path) || (PAGE_PATH + "/").equals(path)) {
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                sendText(exchange, 405, "text/plain; charset=utf-8", "控制台只支持 GET".getBytes(StandardCharsets.UTF_8));
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, page.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(page);
            }
            return;
        }

        // 除了上面三个路径和 /api/*，其它一律 404：不要把未知路径静默当成首页
        sendText(exchange, 404, "text/plain; charset=utf-8",
                ("没有这个路径：" + path + "。可用：GET /  、POST /api/evaluate  、GET /api/health")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static void sendText(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] loadPage() {
        try (InputStream in = ConsoleHandler.class.getResourceAsStream(PAGE_RESOURCE)) {
            if (in == null) {
                // 打包漏了资源时，宁可启动就报错，也不要上线后才发现首页 500
                throw new IllegalStateException("控制台页面缺失：" + PAGE_RESOURCE
                        + "（检查 src/main/resources/console/index.html 是否被打进 jar）");
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("读取控制台页面失败：" + PAGE_RESOURCE, e);
        }
    }
}
