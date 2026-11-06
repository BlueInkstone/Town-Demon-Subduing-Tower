package com.towndemontower.spring.admin;

import com.towndemontower.spring.TownDemontowerProperties;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * [starter/admin] 管理接口的访问控制。
 *
 * <p>复用观测端点的 {@code X-Governance-Token} 与 {@code towndemontower.metrics-token}：
 * 两者都是运维内部凭据，拆成两个令牌只会让配置更容易漏配。未配置令牌时一律 403——
 * "<b>打开了管理接口却没配令牌</b>"必须是拒绝，而不是"默认放行"。</p>
 *
 * <p>比较用 {@link MessageDigest#isEqual} 做定长比较，避免按字符提前返回泄露前缀匹配信息。</p>
 */
final class AdminGuard {

    /** 请求头名；与观测端点保持一致。 */
    static final String HEADER = "X-Governance-Token";

    private final TownDemontowerProperties props;

    AdminGuard(TownDemontowerProperties props) {
        this.props = props;
    }

    void check(String token) {
        String expected = props.getMetricsToken();
        if (expected == null || expected.isBlank()) {
            throw new AdminApiException(HttpStatus.FORBIDDEN, "ADMIN_API_DISABLED",
                    "管理接口未配置访问令牌：请设置 towndemontower.metrics-token");
        }
        if (token == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new AdminApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "无效的治理令牌");
        }
    }
}
