package com.towndemontower.core.audit;

import com.towndemontower.core.util.IO;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * [core] HTTP 型锚点副本：把每一行锚点 POST 给一个收集端（V1.10 / I4-c）。
 *
 * <p>这是本版唯一真正做到"跨主机"的副本——文件副本再换路径也还在同一台机器上，
 * 只有把锚点交出本机，才谈得上"能改日志的人删不掉证据"。</p>
 *
 * <p>协议刻意做得极简，收集端只需要两个动作：</p>
 * <ul>
 *   <li>{@code POST <url>}，body 是一行 {@code records=..\thash=..\tmac=..\tat=..}，
 *       返回 2xx 表示已持久化（<b>2xx 必须是"已落盘"</b>，收集端不能先回 200 再异步写）；</li>
 *   <li>{@code GET <url>}，返回最新一行的原文，{@code 204}/{@code 404} 表示"还没有"。</li>
 * </ul>
 *
 * <p>实现用的是 JDK 自带的 {@code java.net.HttpURLConnection}（Java 8 兼容），不引入任何
 * 第三方依赖——这条约束在这个项目里是硬的（core 至今零外部依赖）。</p>
 *
 * <p>认证：可选共享令牌，从环境变量 {@value #TOKEN_ENV} 读（与门禁密钥、调用令牌、
 * 审计密钥同一套规矩：只走环境变量，不落配置文件）。令牌以 {@code X-Anchor-Token}
 * 头发送；<b>没有令牌也照发</b>，是否拒绝由收集端决定——本类不替它做安全判断。</p>
 *
 * <p><b>代价</b>：push 是同步的，并且发生在锚点写入路径上。默认 {@code anchorBatch=1}
 * 时等于每条审计一次 HTTP，延迟直接叠加到治理主链。远端副本请配
 * {@code audit-anchor-batch}（例如 16）把这次开销均摊掉。</p>
 */
public final class HttpAnchorReplica implements AnchorReplica {

    /** 收集端共享令牌的环境变量名。 */
    public static final String TOKEN_ENV = "TOWNDEMONTOWER_ANCHOR_TOKEN";

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final URI uri;
    private final String token;
    private final Duration requestTimeout;
    private final boolean offHost;
    private volatile String lastError;

    public HttpAnchorReplica(String url) {
        this(url, envToken(), DEFAULT_REQUEST_TIMEOUT);
    }

    public HttpAnchorReplica(String url, String token) {
        this(url, token, DEFAULT_REQUEST_TIMEOUT);
    }

    public HttpAnchorReplica(String url, String token, Duration requestTimeout) {
        this.uri = URI.create(url);
        if (this.uri.getHost() == null) {
            throw new IllegalArgumentException("锚点副本 URL 缺少主机名：[" + url + "]");
        }
        this.token = (token == null || token.trim().isEmpty()) ? null : token.trim();
        this.requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        this.offHost = !isLoopback(this.uri.getHost());
    }

    /** 环境变量里的令牌；未配置返回 {@code null}。 */
    public static String envToken() {
        String raw = System.getenv(TOKEN_ENV);
        return (raw == null || raw.trim().isEmpty()) ? null : raw.trim();
    }

    public URI uri() {
        return uri;
    }

    @Override
    public String name() {
        return "http:" + uri;
    }

    @Override
    public boolean offHost() {
        return offHost;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public void push(AnchorSnapshot snapshot) {
        HttpURLConnection conn = null;
        try {
            conn = open("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            byte[] body = snapshot.line().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int status = conn.getResponseCode();
            if (status / 100 != 2) {
                lastError = "HTTP " + status;
                throw new IllegalStateException("锚点副本拒绝写入：" + uri + " 返回 HTTP " + status);
            }
            lastError = null;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw new IllegalStateException("锚点副本不可达：" + uri + "（" + lastError + "）", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public AnchorSnapshot pull() {
        HttpURLConnection conn = null;
        try {
            conn = open("GET");
            int status = conn.getResponseCode();
            if (status == 204 || status == 404) {
                lastError = null;
                return null;
            }
            if (status / 100 != 2) {
                lastError = "HTTP " + status;
                throw new IllegalStateException("锚点副本读取失败：" + uri + " 返回 HTTP " + status);
            }
            String body = new String(IO.readAllBytes(conn.getInputStream()), StandardCharsets.UTF_8);
            AnchorSnapshot s = AnchorSnapshot.parse(body, "副本 " + uri);
            lastError = null;
            return s;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw new IllegalStateException("锚点副本不可读：" + uri + "（" + lastError + "）", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection open(String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout((int) DEFAULT_CONNECT_TIMEOUT.toMillis());
        conn.setReadTimeout((int) requestTimeout.toMillis());
        if (token != null) conn.setRequestProperty("X-Anchor-Token", token);
        return conn;
    }

    private static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1")
                || h.startsWith("127.");
    }
}
