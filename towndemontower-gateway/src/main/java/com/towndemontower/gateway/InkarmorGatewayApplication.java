package com.towndemontower.gateway;

import com.sun.net.httpserver.HttpServer;
import com.towndemontower.gateway.config.GatewayConfig;
import com.towndemontower.gateway.core.GatewayVerdictEngine;
import com.towndemontower.gateway.web.ConsoleHandler;
import com.towndemontower.gateway.web.EvaluateHandler;
import com.towndemontower.gateway.web.HealthHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * [gateway] 墨铠网关入口。独立可运行的裁决服务，暴露：
 * <ul>
 *   <li>{@code POST /api/evaluate}：裁决入口，需令牌，写审计；</li>
 *   <li>{@code GET /api/health}：探活，不需要令牌，<b>不写审计</b>；</li>
 *   <li>{@code GET /console}：裁决控制台页面（{@link ConsoleHandler}），只读静态页，无秘密。</li>
 * </ul>
 *
 * <p>Java 8、不依赖 Spring：用 JDK 内置 {@link HttpServer} 提供 HTTP，
 * 用 jackson 处理 JSON。与 delivery / agents 演示模块互不干扰：网关是"只裁决不执行"
 * 的新形态，不引入执行层、不替调用方动手。</p>
 *
 * <p><b>可运维性开关</b>（都是环境变量，见 {@link GatewayConfig}）：工作线程数
 * {@code INKARMOR_GATEWAY_THREADS}（默认 8）、请求体上限 {@code INKARMOR_GATEWAY_MAX_BODY_BYTES}
 * （默认 4 MiB）、请求/响应超时 {@code INKARMOR_GATEWAY_TIMEOUT_SECONDS}（默认 30 秒）。</p>
 *
 * <p><b>两个已知缺口</b>：线程池用的是无界队列，排队数量不受控；超时依赖 JDK HttpServer 的内部开关
 * （{@code sun.net.httpserver.maxReqTime/maxRspTime}），是"尽力而为"而不是硬保证。这两点在
 * docs/39 §8 里如实记着，不要当成已具备的能力。</p>
 */
public final class InkarmorGatewayApplication {

    private InkarmorGatewayApplication() {
    }

    public static void main(String[] args) throws IOException {
        GatewayConfig config = new GatewayConfig();
        int port = port();

        // 必须在 HttpServer 创建之前设置：超时是 ServerImpl 构造时读的
        applyRequestTimeouts(config.timeoutSeconds());

        GatewayVerdictEngine engine = new GatewayVerdictEngine(config.agentId(), config.auditPath());

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/evaluate", new EvaluateHandler(engine, config));
        server.createContext("/api/health", new HealthHandler());
        // "/" 只服务裁决控制台；前缀匹配下 /api/* 仍然优先命中上面两个更具体的 context
        server.createContext("/", new ConsoleHandler());
        server.setExecutor(Executors.newFixedThreadPool(config.threads()));
        server.start();

        System.out.println("Inkarmor Gateway (Java 8, no Spring) listening on :" + port);
        System.out.println("  POST /api/evaluate  (X-Inkarmor-Token required, audited)");
        System.out.println("  GET  /api/health    (no token, not audited)");
        System.out.println("  GET  /console       (裁决控制台，浏览器直接打开；令牌在页面上填)");
        System.out.println("  audit -> " + config.auditPath()
                + " | mode=" + config.modeName() + " | agent=" + config.agentId()
                + " | threads=" + config.threads()
                + " | maxBody=" + config.maxBodyBytes() + "B"
                + " | timeout=" + config.timeoutSeconds() + "s");
    }

    /**
     * 请求/响应超时交给 JDK HttpServer 的内部开关。显式传入的 {@code -D} 优先，不覆盖使用方设置。
     *
     * <p>这不是硬保证：JDK 的实现是定时检查，超时后直接关连接，不给响应。因此它挡的是
     * "慢速灌包占住线程"，调用方拿到的表现是连接被断开（客户端侧按 fail-closed 处理）。</p>
     */
    private static void applyRequestTimeouts(int seconds) {
        if (seconds <= 0) {
            return;
        }
        String value = String.valueOf(seconds);
        setIfAbsent("sun.net.httpserver.maxReqTime", value);
        setIfAbsent("sun.net.httpserver.maxRspTime", value);
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static int port() {
        String p = System.getenv("PORT");
        if (p == null || p.trim().isEmpty()) {
            p = System.getProperty("PORT");
        }
        if (p == null || p.trim().isEmpty()) {
            return 8080;
        }
        return Integer.parseInt(p.trim());
    }
}
