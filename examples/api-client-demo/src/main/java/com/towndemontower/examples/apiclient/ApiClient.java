package com.towndemontower.examples.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标准 HTTP 调用器：拼 URL → 组请求 → 过中间件链 → 收响应 → 解析 → 异常收敛。
 *
 * <p><b>职责边界</b>：只做"传输 + 解析"，不做业务判断、不做重试、不加鉴权头——
 * 那三件事都在中间件里（{@link Middlewares}）。这样换一套鉴权或重试策略时，
 * 业务调用代码一行都不用改。</p>
 *
 * <p><b>线程安全</b>：{@link HttpClient} 与 {@link ObjectMapper} 都是线程安全的，
 * 应作为单例复用；中间件链在构造期注册完、运行期只读，所以整个实例可以共享。
 * 唯一要小心的是 {@link #add} 之后再并发调用——链条会重排，注册请在启动阶段做完。</p>
 */
public final class ApiClient {

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final MiddlewareChain chain = new MiddlewareChain();
    private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
    private final Duration defaultTimeout;

    public ApiClient(String baseUrl) {
        this(baseUrl, new ObjectMapper(), Duration.ofSeconds(10));
    }

    public ApiClient(String baseUrl, ObjectMapper mapper, Duration defaultTimeout) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl 不能为空");
        this.baseUrl = baseUrl;
        this.mapper = mapper;
        this.defaultTimeout = defaultTimeout;
        // 连接池复用是性能关键：不要每请求 new 一个 HttpClient
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 注册中间件；order 越小越外层。 */
    public ApiClient add(int order, Middleware middleware) {
        chain.add(order, middleware);
        return this;
    }

    public ApiClient add(Middleware middleware) {
        chain.add(middleware);
        return this;
    }

    /** 客户端级默认头，优先级低于请求级（不覆盖调用方显式设置）。 */
    public ApiClient defaultHeader(String name, String value) {
        defaultHeaders.put(name, value);
        return this;
    }

    public List<String> chainNames() {
        return chain.names();
    }

    public String baseUrl() { return baseUrl; }

    /** 入口：所有调用都从这里走，保证中间件一定生效（不要绕过它直接调 send）。 */
    public ApiResponse execute(ApiRequest request) throws ApiException {
        return chain.execute(request, this::send);
    }

    // ------------------------------------------------------------------
    // 终结点：真正发 HTTP
    // ------------------------------------------------------------------

    ApiResponse send(ApiRequest request) {
        Duration timeout = request.timeout() == null ? defaultTimeout : request.timeout();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.urlAgainst(baseUrl)))
                .timeout(timeout);
        // Java 的 HttpRequest 会拒绝设置受管头（Host / Content-Length 等），这里静默跳过
        defaultHeaders.forEach((k, v) -> setHeaderSafely(builder, k, v));
        request.headers().forEach((k, v) -> setHeaderSafely(builder, k, v));
        setHeaderSafely(builder, "Accept", "application/json");

        if (request.hasBody()) {
            builder.header("Content-Type", "application/json; charset=utf-8");
            builder.method(request.method().name(),
                    HttpRequest.BodyPublishers.ofString(request.jsonBody(), StandardCharsets.UTF_8));
        } else {
            builder.method(request.method().name(), HttpRequest.BodyPublishers.noBody());
        }

        try {
            HttpResponse<String> resp = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode json = parse(resp.body(), resp.headers().firstValue("Content-Type").orElse(""));
            return new ApiResponse(resp.statusCode(), resp.headers().map(), resp.body(), json);
        } catch (HttpTimeoutException e) {
            // 读超时：请求可能已经到达服务端并被执行，所以严格说不一定"可以安全重试"
            throw ApiException.network("TIMEOUT", "请求超时（" + timeout.toMillis() + "ms）", e);
        } catch (IOException e) {
            throw ApiException.network("NETWORK_ERROR", "连接失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断标记，别吞
            throw ApiException.network("INTERRUPTED", "调用被中断", e);
        }
    }

    private void setHeaderSafely(HttpRequest.Builder builder, String name, String value) {
        if (name == null || value == null) return;
        try {
            builder.header(name, value);
        } catch (IllegalArgumentException ignored) {
            // 受管头（如 Host）不允许手动设置，忽略即可
        }
    }

    /**
     * 只在"看起来是 JSON"时解析：
     * 网关 502 常常返回 HTML，硬解析会把真正的原因（HTML 里的 nginx 字样）盖成 "JSON 解析失败"。
     */
    private JsonNode parse(String body, String contentType) {
        if (body == null || body.isBlank()) return null;
        String ct = contentType == null ? "" : contentType.toLowerCase();
        boolean looksJson = ct.contains("json") || body.trim().startsWith("{") || body.trim().startsWith("[");
        if (!looksJson) return null;
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            return null; // 保留原始 body，让调用方自己决定怎么处理
        }
    }
}
