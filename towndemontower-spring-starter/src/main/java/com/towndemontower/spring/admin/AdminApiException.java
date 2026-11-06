package com.towndemontower.spring.admin;

import org.springframework.http.HttpStatus;

/**
 * [starter/admin] 管理接口可预期的业务异常：携带 HTTP 状态与稳定错误码。
 *
 * <p>与通用 {@link IllegalArgumentException} 分开，是为了让"参数缺失"和"状态冲突"
 * 在响应里可区分——前者 400，后者 409。异常不吞原始原因，message 直接面向调用方，
 * 因此不要在 message 里拼内部路径或堆栈。</p>
 */
public class AdminApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AdminApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
