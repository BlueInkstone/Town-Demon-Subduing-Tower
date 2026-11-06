package com.towndemontower.examples.apiclient;

import com.towndemontower.examples.apiclient.ApiRequest.Method;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 内置中间件集合。每个方法返回一个 {@link Middleware}，用 {@link ApiClient#add(int, Middleware)}
 * 挂到调用链上即可，不需要继承、不需要容器。
 *
 * <p>推荐顺序（order 由小到大 = 由外到内）：</p>
 * <pre>
 *   0 onError             —— 最外层兜底
 *   1 retry               —— 只包住"会失败的那一层"
 *   2 logging             —— 放在 retry 内层，才能看到每一次尝试
 *   3 token / headers     —— 每次尝试都补头
 *   4 unifiedEnvelope     —— 最内层，把响应转成统一语义
 * </pre>
 */
public final class Middlewares {

    private Middlewares() {}

    // ------------------------------------------------------------------
    // 1. 请求头自动添加
    // ------------------------------------------------------------------

    /** 批量补请求头；已存在的不覆盖（尊重调用方显式设置）。 */
    public static Middleware headers(Map<String, String> extra) {
        Map<String, String> copy = Map.copyOf(extra);
        return new Middleware() {
            @Override public String name() { return "headers"; }
            @Override public ApiResponse apply(ApiRequest request, Next next) {
                ApiRequest req = request;
                for (Map.Entry<String, String> e : copy.entrySet()) {
                    req = req.withHeader(e.getKey(), e.getValue());
                }
                return next.proceed(req);
            }
        };
    }

    /**
     * 注入治理令牌 {@code X-Governance-Token}。
     *
     * <p>为什么放在中间件里而不是调用点：令牌会轮换、可能来自环境变量/密钥管理，
     * 让每个调用点都记着加头，迟早会漏一处。集中在这里，改来源只改一行。</p>
     */
    public static Middleware token(String token) {
        String value = token == null ? "" : token;
        return new Middleware() {
            @Override public String name() { return "token"; }
            @Override public ApiResponse apply(ApiRequest request, Next next) {
                return next.proceed(request.withHeader("X-Governance-Token", value));
            }
        };
    }

    // ------------------------------------------------------------------
    // 2. 响应统一格式化 + 统一异常
    // ------------------------------------------------------------------

    /**
     * 把"HTTP 错误"和"业务码错误"统一成 {@link ApiException}，成功则原样返回。
     *
     * <p>判定顺序很重要：
     * <ol>
     *   <li>先看 HTTP 状态：非 2xx 一律失败（哪怕 body 里有 code=0，也不能当成功）；</li>
     *   <li>再看业务码：信封里 {@code code != 0} 视为失败——HTTP 200 + 业务失败是常见的坑；</li>
     *   <li>其余返回，调用方用 {@link ApiResponse#data()} 统一取数据，不必判断有没有信封。</li>
     * </ol>
     */
    public static Middleware unifiedEnvelope() {
        return new Middleware() {
            @Override public String name() { return "unifiedEnvelope"; }
            @Override public ApiResponse apply(ApiRequest request, Next next) {
                ApiResponse resp = next.proceed(request);
                if (!resp.httpSuccess()) {
                    String error = resp.error() != null ? resp.error() : "HTTP_" + resp.status();
                    String message = resp.message() != null ? resp.message() : resp.body();
                    throw new ApiException(resp.status(), resp.code(), error, message, null);
                }
                if (resp.isEnvelope() && resp.code() != 0) {
                    String error = resp.error() != null ? resp.error() : "BIZ_" + resp.code();
                    String message = resp.message() != null ? resp.message() : "业务处理失败";
                    throw new ApiException(resp.status(), resp.code(), error, message, null);
                }
                return resp;
            }
        };
    }

    // ------------------------------------------------------------------
    // 3. 自动重试
    // ------------------------------------------------------------------

    /**
     * 指数退避 + 抖动的自动重试。
     *
     * <p><b>两道保险</b>：{@link ApiException#retryable()} 判断"错误类型能不能重试"，
     * {@link #idempotent} 判断"这个请求重复执行会不会产生副作用"。两者都满足才重试——
     * 一个没有幂等保证的 POST 重试，可能把一次审批做成两次。</p>
     *
     * <p>抖动（jitter）是必须的：固定 200ms 重试会让一批客户端在同一时刻再次打到服务端，
     * 把一次小抖动放大成雪崩。</p>
     */
    public static Middleware retry(int maxAttempts, Duration baseBackoff) {
        int attempts = Math.max(1, maxAttempts);
        return new Middleware() {
            @Override public String name() { return "retry(" + attempts + ")"; }

            @Override public ApiResponse apply(ApiRequest request, Next next) {
                ApiException last = null;
                for (int attempt = 1; attempt <= attempts; attempt++) {
                    try {
                        return next.proceed(request);
                    } catch (ApiException e) {
                        last = e;
                        boolean canRetry = e.retryable() && idempotent(request);
                        if (!canRetry || attempt == attempts) throw e;
                        sleep(backoff(baseBackoff, attempt));
                    }
                }
                throw last; // 理论到不了这里，防止编译器认为可能返回 null
            }
        };
    }

    /** 只有"重复执行无副作用"的请求才允许重试。 */
    private static boolean idempotent(ApiRequest request) {
        Method m = request.method();
        if (m == Method.GET || m == Method.PUT || m == Method.DELETE) return true;
        return "true".equalsIgnoreCase(request.headers().getOrDefault("X-Idempotent", "false"));
    }

    /** base * 2^(attempt-1)，上限 2s，再加 0~50% 抖动。 */
    private static Duration backoff(Duration base, int attempt) {
        long millis = base.toMillis() * (1L << Math.min(attempt - 1, 5));
        millis = Math.min(millis, 2000L);
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, millis / 2));
        return Duration.ofMillis(millis + jitter);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ApiException.network("INTERRUPTED", "重试等待被中断", ie);
        }
    }

    // ------------------------------------------------------------------
    // 4. 可观测：日志
    // ------------------------------------------------------------------

    /**
     * 每次真实尝试打一行日志（放在 retry 内层，重试次数才看得出来）。
     *
     * <p>只打方法、路径、状态、耗时、业务码；<b>不打请求体和 Authorization 之类的头</b>——
     * 日志是最容易泄密的地方。</p>
     */
    public static Middleware logging(PrintStream out) {
        return new Middleware() {
            @Override public String name() { return "logging"; }
            @Override public ApiResponse apply(ApiRequest request, Next next) {
                long start = System.nanoTime();
                try {
                    ApiResponse resp = next.proceed(request);
                    out.printf("    [http] %-28s -> %s (%dms)%n",
                            request.describe(), resp.describe(), elapsedMs(start));
                    return resp;
                } catch (ApiException e) {
                    out.printf("    [http] %-28s -> 失败 %s (%dms)%n",
                            request.describe(), e.describe(), elapsedMs(start));
                    throw e;
                }
            }
        };
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    // ------------------------------------------------------------------
    // 5. 异常统一处理（最外层兜底）
    // ------------------------------------------------------------------

    /**
     * 集中处理异常：上报监控、写告警、映射成用户提示……处理完<b>必须再抛出</b>。
     *
     * <p>为什么必须抛：中间件的职责是"加能力"，不是"改结果"。如果这里 return 一个默认值，
     * 调用方会以为调用成功了，这是最危险的中间件反模式（静默降级）。</p>
     */
    public static Middleware onError(Consumer<ApiException> sink) {
        return new Middleware() {
            @Override public String name() { return "onError"; }
            @Override public ApiResponse apply(ApiRequest request, Next next) {
                try {
                    return next.proceed(request);
                } catch (ApiException e) {
                    sink.accept(e);
                    throw e;
                }
            }
        };
    }
}
