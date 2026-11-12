package com.towndemontower.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [gateway] 探活入口：{@code GET /api/health}。
 *
 * <p>为什么要单独一个：此前网关只有 {@code /api/evaluate}，它要求 POST + 令牌，
 * 探针只能靠"端口连得上吗"猜存活，或者拿令牌真跑一次裁决——后者会往审计里刷无意义记录、
 * 也会污染"拒绝率/转人工率"这类统计。本端点：</p>
 * <ul>
 *   <li><b>不写审计</b>：每 5 秒一次的探针会把审计链刷满，把真正的证据淹掉；</li>
 *   <li><b>不要求令牌</b>：K8s / 负载均衡探针通常不带自定义头；</li>
 *   <li><b>不进裁决链</b>：没有任何工具、模式或风险语义；</li>
 *   <li><b>只回最小信息</b>：{@code status} + {@code uptimeSeconds}。
 *       agentId / mode / 审计路径一律不返回——未鉴权端点不泄露内部结构。</li>
 * </ul>
 *
 * <p>注意它的语义边界：返回 200 只代表"进程活着、线程池在收请求"。
 * 审计链在启动时就已校验（{@code FileAuditStore} 构造失败即拒绝启动），
 * 因此 200 也隐含"审计已初始化"，但不代表磁盘还有空间。</p>
 */
public class HealthHandler implements HttpHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final long startedAtMs = System.currentTimeMillis();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, ApiResponse.fail(405, "METHOD_NOT_ALLOWED", "仅支持 GET"));
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "up");
            data.put("uptimeSeconds", (System.currentTimeMillis() - startedAtMs) / 1000L);
            send(exchange, 200, ApiResponse.ok(data));
        } finally {
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, ApiResponse response) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
