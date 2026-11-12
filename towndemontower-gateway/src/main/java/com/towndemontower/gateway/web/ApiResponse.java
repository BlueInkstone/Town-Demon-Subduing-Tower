package com.towndemontower.gateway.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * [gateway] 统一响应信封（docs/38 改动⑧：复用 code/message/data/error 形态）。
 *
 * <p>{@code data} 内放 {@code SkillResult.toWire()} 的 5 个字段（success/code/message/
 * output/approvalTicketId）。外层 {@code code} 仅表示"网关是否处理了这次请求"：
 * 0 = 已处理（裁决在 data.code 中）；非 0 = 鉴权等前置失败（与 HTTP 状态码一致）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse {

    public final int code;
    public final String message;
    public final Object data;
    public final String error;

    public ApiResponse(int code, String message, Object data, String error) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.error = error;
    }

    public static ApiResponse ok(Object data) {
        return new ApiResponse(0, "ok", data, null);
    }

    public static ApiResponse fail(int code, String error, String message) {
        return new ApiResponse(code, message, null, error);
    }
}
