package com.towndemontower.examples.apiclient;

/**
 * 调用失败时的<b>唯一</b>异常类型：把"网络错误 / HTTP 错误 / 业务码错误"收敛成一个可判定的对象。
 *
 * <p>为什么不直接抛 {@code IOException} 或 {@code RuntimeException}：
 * 上层（中间件、调用方）需要根据 {@link #retryable()} 决定能否重试，
 * 根据 {@link #code()} 决定给用户看哪句提示。散装异常逼着每个调用点都写
 * {@code catch (Exception e) { if (...) } }，最终一定会漏。</p>
 *
 * <p>字段含义：
 * <ul>
 *   <li>{@code status}：HTTP 状态码；{@code 0} 表示请求没到达服务端（连接失败 / 超时）；</li>
 *   <li>{@code code}：业务码（信封里的 {@code code}），没有信封时等于 {@code status}；</li>
 *   <li>{@code error}：机器可读的错误标识，如 {@code PLAN_REJECTED}；</li>
 *   <li>{@code message}：给人看的说明。</li>
 * </ul>
 */
public class ApiException extends RuntimeException {

    /** 未收到任何 HTTP 响应（连接失败 / 读取超时 / 线程被中断）。 */
    public static final int NO_RESPONSE = 0;

    private final int status;
    private final int code;
    private final String error;

    public ApiException(int status, int code, String error, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.error = error;
    }

    public static ApiException network(String error, String message, Throwable cause) {
        return new ApiException(NO_RESPONSE, NO_RESPONSE, error, message, cause);
    }

    public int status() { return status; }

    public int code() { return code; }

    public String error() { return error; }

    public boolean isNetworkError() { return status == NO_RESPONSE; }

    /**
     * 能否重试。
     *
     * <p>规则刻意保守：<b>只重试"服务端暂时性故障"</b>——
     * 无响应（超时/连接失败）、429（限流）、5xx。
     * 4xx 一律不重试：401/403 重试一万次也不会变对，400/409 重试还可能把错误放大。
     * 另外 {@link Middlewares#retry} 还会再加一道"请求是否幂等"的判断。</p>
     */
    public boolean retryable() {
        return isNetworkError() || status == 429 || status >= 500;
    }

    public String describe() {
        return "status=" + status + " code=" + code + " error=" + error + " message=" + getMessage();
    }
}
