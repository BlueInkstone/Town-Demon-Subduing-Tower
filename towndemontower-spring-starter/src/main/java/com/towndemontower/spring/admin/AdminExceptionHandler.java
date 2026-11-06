package com.towndemontower.spring.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.System.Logger;

/**
 * [starter/admin] 管理接口异常映射。
 *
 * <p>只作用于 {@code com.towndemontower.spring.admin} 包内的控制器：
 * 若做成全局 advice，接入方自己的异常也会被这里的格式改写，那等于 starter
 * 偷偷改了别人的 API 契约。</p>
 *
 * <p>映射规则：参数类 → 400；状态冲突 → 409；找不到 → 404；其余 → 500。
 * 500 只回 {@code INTERNAL_ERROR}，原始异常写日志，不把堆栈或内部路径带回响应。</p>
 */
@RestControllerAdvice(basePackages = "com.towndemontower.spring.admin")
public class AdminExceptionHandler {

    private static final Logger LOG = System.getLogger(AdminExceptionHandler.class.getName());

    @ExceptionHandler(AdminApiException.class)
    public ResponseEntity<ApiResponse> onAdminApi(AdminApiException e) {
        return ResponseEntity.status(e.status())
                .body(ApiResponse.fail(e.status().value(), e.code(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse> onBadRequest(Exception e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, "BAD_REQUEST", "请求参数或请求体格式不正确"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, "VALIDATION_FAILED", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse> onIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(409, "CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse> onNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(404, "NOT_FOUND", "接口不存在：" + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> onUnexpected(Exception e) {
        LOG.log(Logger.Level.ERROR, "治理管理接口未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "INTERNAL_ERROR", "服务内部错误，请查看服务端日志"));
    }
}
