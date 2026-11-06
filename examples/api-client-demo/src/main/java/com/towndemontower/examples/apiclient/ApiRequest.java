package com.towndemontower.examples.apiclient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 API 调用的<b>不可变</b>描述：方法 + 路径 + 查询参数 + 请求头 + 请求体 + 超时。
 *
 * <p>为什么不可变：中间件（见 {@link Middleware}）会"补一个头"或"加一个参数"，
 * 如果原地修改，同一份请求被重试时就会叠加副作用（例如重试 3 次却在头里塞了 3 个追踪 id）。
 * 所以所有改写方法都返回<b>新对象</b>，{@link #withHeader(String, String)} 之类不修改 this。</p>
 *
 * <p>只描述"调用意图"，不持有 baseUrl 与 HttpClient —— 那两样属于 {@link ApiClient}，
 * 这样同一份请求可以被不同域名的客户端复用（例如灰度环境与生产环境）。</p>
 */
public final class ApiRequest {

    public enum Method { GET, POST, PUT, DELETE }

    private final Method method;
    private final String path;
    private final Map<String, String> query;
    private final Map<String, String> headers;
    private final String jsonBody;
    private final Duration timeout;

    private ApiRequest(Builder b) {
        this.method = b.method;
        this.path = b.path;
        // 用 LinkedHashMap 保序：日志与 curl 复现时可读
        this.query = Collections.unmodifiableMap(new LinkedHashMap<>(b.query));
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.jsonBody = b.jsonBody;
        this.timeout = b.timeout;
    }

    public Method method() { return method; }

    public String path() { return path; }

    public Map<String, String> query() { return query; }

    public Map<String, String> headers() { return headers; }

    /** 原始 JSON 文本；{@code null} 表示无请求体（GET 一般为 null）。 */
    public String jsonBody() { return jsonBody; }

    public boolean hasBody() { return jsonBody != null && !jsonBody.isBlank(); }

    public Duration timeout() { return timeout; }

    public boolean hasHeader(String name) {
        return headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(name));
    }

    // ------------------------------------------------------------------
    // 改写：返回新对象，绝不原地修改
    // ------------------------------------------------------------------

    /** 补一个请求头；同名已存在时<b>不覆盖</b>（中间件不应悄悄改掉调用方显式设置的值）。 */
    public ApiRequest withHeader(String name, String value) {
        if (hasHeader(name)) return this;
        Builder b = copyBuilder();
        b.headers.put(name, value);
        return new ApiRequest(b);
    }

    /** 强制覆盖请求头（用于"必须以我方为准"的场景，例如网关注入的追踪 id）。 */
    public ApiRequest withHeaderOverride(String name, String value) {
        Builder b = copyBuilder();
        b.headers.put(name, value);
        return new ApiRequest(b);
    }

    public ApiRequest withQuery(String name, Object value) {
        if (value == null) return this;
        Builder b = copyBuilder();
        b.query.put(name, String.valueOf(value));
        return new ApiRequest(b);
    }

    public ApiRequest withTimeout(Duration newTimeout) {
        Builder b = copyBuilder();
        b.timeout = newTimeout;
        return new ApiRequest(b);
    }

    private Builder copyBuilder() {
        Builder b = new Builder(method, path);
        b.query.putAll(query);
        b.headers.putAll(headers);
        b.jsonBody = jsonBody;
        b.timeout = timeout;
        return b;
    }

    // ------------------------------------------------------------------
    // URL 组装
    // ------------------------------------------------------------------

    /** 拼成最终 URL；查询参数做 UTF-8 编码，避免中文/空格把 URL 弄坏。 */
    public String urlAgainst(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p = path.startsWith("/") ? path : "/" + path;
        StringBuilder sb = new StringBuilder(base).append(p);
        if (!query.isEmpty()) {
            sb.append(p.contains("?") ? '&' : '?');
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (!first) sb.append('&');
                sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
        }
        return sb.toString();
    }

    /** 日志用：不含请求体（避免把敏感字段打出来）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(method.name()).append(' ').append(path);
        if (!query.isEmpty()) sb.append(' ').append(query);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 构造入口
    // ------------------------------------------------------------------

    public static ApiRequest get(String path) {
        return new Builder(Method.GET, path).build();
    }

    public static ApiRequest post(String path, String jsonBody) {
        return new Builder(Method.POST, path).json(jsonBody).build();
    }

    public static Builder builder(Method method, String path) {
        return new Builder(method, path);
    }

    /** 可变构造器；仅在 {@code build()} 之前存活。 */
    public static final class Builder {
        final Method method;
        final String path;
        final Map<String, String> query = new LinkedHashMap<>();
        final Map<String, String> headers = new LinkedHashMap<>();
        String jsonBody;
        Duration timeout = Duration.ofSeconds(10);

        private Builder(Method method, String path) {
            this.method = method;
            this.path = path;
        }

        public Builder query(String name, Object value) {
            if (value != null) query.put(name, String.valueOf(value));
            return this;
        }

        public Builder header(String name, String value) {
            if (name != null && value != null) headers.put(name, value);
            return this;
        }

        public Builder json(String rawJson) {
            this.jsonBody = rawJson;
            return this;
        }

        public Builder timeout(Duration d) {
            if (d != null) this.timeout = d;
            return this;
        }

        public ApiRequest build() {
            return new ApiRequest(this);
        }
    }
}
