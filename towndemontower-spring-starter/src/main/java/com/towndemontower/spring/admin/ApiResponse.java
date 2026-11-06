package com.towndemontower.spring.admin;

/**
 * [starter/admin] 管理接口统一响应体。
 *
 * <p>成功时 {@code code=0}、{@code error=null}；失败时 {@code code} 取 HTTP 状态码、
 * {@code error} 是稳定的机器可读错误码、{@code message} 给人看。失败时 {@code data}
 * 恒为 {@code null}——不返回半截数据，避免调用方误以为"有一部分能用"。</p>
 *
 * <p>错误码清单见 {@code docs/33-治理管理接口.md}，前端按 {@code error} 分支，
 * 不要按 {@code message} 文本匹配。</p>
 */
public record ApiResponse(int code, String message, Object data, String error) {

    public static ApiResponse ok(Object data) {
        return new ApiResponse(0, "ok", data, null);
    }

    public static ApiResponse fail(int code, String error, String message) {
        return new ApiResponse(code, message, null, error);
    }
}
