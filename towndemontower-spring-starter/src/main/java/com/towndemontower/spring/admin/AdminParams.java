package com.towndemontower.spring.admin;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * [starter/admin] 参数校验的小工具。
 *
 * <p>刻意不引入 Bean Validation：starter 的 web 依赖是 optional，加
 * {@code spring-boot-starter-validation} 会给所有接入方多带一套注解与实现。
 * 这里的规则很少（非空、区间），手写即可，且错误码统一可控。</p>
 */
final class AdminParams {

    private AdminParams() {
    }

    /** 取字符串参数；空白按"未提供"处理，返回 {@code null}。 */
    static String text(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    static int limit(Integer value, int defaultValue, int max) {
        if (value == null) return defaultValue;
        if (value < 1 || value > max) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                    "limit 必须在 1.." + max + " 之间，实际为 " + value);
        }
        return value;
    }

    static int offset(Integer value) {
        if (value == null) return 0;
        if (value < 0) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                    "offset 不能为负数，实际为 " + value);
        }
        return value;
    }
}
