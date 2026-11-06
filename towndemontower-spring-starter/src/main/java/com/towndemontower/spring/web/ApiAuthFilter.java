package com.towndemontower.spring.web;

import com.towndemontower.core.auth.ApiCallerRegistry;
import com.towndemontower.core.auth.AuthMode;
import com.towndemontower.core.auth.CallerAgentBindings;
import com.towndemontower.core.auth.CallerContext;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.contract.ErrorCodes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * [starter] REST 调用者认证过滤器（V1.8 / I6）。
 *
 * <p>解决的问题很具体：{@code agents} 与 {@code delivery} 的 HTTP 接口此前<b>零身份校验</b>，
 * 同一网段内任何人都能 {@code POST /api/agents} 注册一个自带权限的 Agent。现在按
 * {@link AuthMode} 分三档：</p>
 *
 * <ul>
 *   <li>{@link AuthMode#LOOPBACK}（默认）：读放行、<b>写只允许本机</b>。不打扰本地演示与既有用例，
 *       但"局域网内任意主机改注册表"这条路直接断掉。</li>
 *   <li>{@link AuthMode#TOKEN}：所有受保护路径必须带 {@code Authorization: Bearer <token>}；
 *       令牌表为空时<b>全部拒绝</b>（fail-closed，不是"没配就放行"）。</li>
 *   <li>{@link AuthMode#OFF}：不校验，仅限已被外层网关鉴权的部署，启动日志记 WARN。</li>
 * </ul>
 *
 * <p>认证通过后把调用者写入 {@link CallerContext}，控制器据此填
 * {@code ExecutionContext.ownerUserId}——"谁在调用"因此进入审计链，而不只是留在访问日志里。
 * 请求结束（含异常）一定清理 ThreadLocal，避免线程复用串身份。</p>
 *
 * <h2>V1.9 / I3-b：凭据 → Agent 绑定</h2>
 * <p>令牌只证明"你是哪个服务账号"，不证明"你代表哪个 Agent"。可选传入
 * {@link CallerAgentBindings} 后，认证通过还会做一次绑定查询：</p>
 * <ul>
 *   <li>绑定表非空（严格模式）且当前调用者不在表里 → <b>403 {@code CALLER_NOT_PROVISIONED}</b>，
 *       不留任何回落到默认身份的口子；</li>
 *   <li>绑定命中 → 把 {@code caller} 与 {@code agentId} 一起写进 {@link CallerContext}，
 *       后续 {@code ExecutionContext.agentId} 用它，RBAC 与审计因此归属到具体 Agent；</li>
 *   <li>绑定表为空 → 不校验，行为与 V1.8 完全一致（升级兼容位，由启动自检告警）。</li>
 * </ul>
 * <p>传 {@code null} 表示本模块不启用绑定（例如没有 Agent 概念的模块）。</p>
 *
 * <p><b>不做的事</b>：不做会话、不做刷新、不做按接口粒度的授权（scope）、不校验绑定的
 * Agent 是否存在或可用——那需要注册表，属于业务模块的职责（见 agents 的
 * {@code CallerBindingGuard}）。令牌是"服务身份"，不是用户登录态。</p>
 */
public class ApiAuthFilter extends OncePerRequestFilter {

    /** 受保护路径前缀。 */
    private static final String PROTECTED_PREFIX = "/api/";

    private static final Logger LOG = Logger.getLogger(ApiAuthFilter.class.getName());

    private final ApiCallerRegistry registry;
    private final AuthMode mode;
    private final String module;
    /** 可空：没有审计通道时只记日志，不影响认证结果。 */
    private final AuditStore audit;
    /** 可空：为 null 表示本模块不做凭据→Agent 绑定（V1.9 / I3-b）。 */
    private final CallerAgentBindings bindings;

    public ApiAuthFilter(ApiCallerRegistry registry, AuthMode mode, String module) {
        this(registry, mode, module, null, null);
    }

    public ApiAuthFilter(ApiCallerRegistry registry, AuthMode mode, String module, AuditStore audit) {
        this(registry, mode, module, audit, null);
    }

    public ApiAuthFilter(ApiCallerRegistry registry, AuthMode mode, String module,
                         AuditStore audit, CallerAgentBindings bindings) {
        this.registry = registry != null ? registry : ApiCallerRegistry.empty();
        this.mode = mode != null ? mode : AuthMode.LOOPBACK;
        this.module = module;
        this.audit = audit;
        this.bindings = bindings;
    }

    /** 当前生效模式，便于启动日志与自检读取。 */
    public AuthMode mode() {
        return mode;
    }

    /** 绑定表；{@code null} 表示本模块不做绑定。 */
    public CallerAgentBindings bindings() {
        return bindings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!isProtected(request.getRequestURI()) || mode == AuthMode.OFF) {
            chain.doFilter(request, response);
            return;
        }

        if (mode == AuthMode.LOOPBACK) {
            if (isReadMethod(request.getMethod()) || isLoopback(request.getRemoteAddr())) {
                chain.doFilter(request, response);
                return;
            }
            deny(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "CALLER_NOT_LOCAL",
                    "写操作只允许来自本机（认证模式 loopback）；跨机调用请改用 token 模式");
            return;
        }

        ApiCallerRegistry.Auth auth = registry.authenticate(request.getHeader("Authorization"));
        if (!auth.allowed()) {
            deny(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    auth.code(), auth.reason());
            return;
        }

        // ① 认证通过 ≠ 允许代表某个 Agent 操作。绑定表非空时，未绑定一律拒绝。
        String boundAgent = null;
        if (bindings != null) {
            boundAgent = bindings.agentFor(auth.caller());
            if (boundAgent == null && bindings.strict()) {
                deny(request, response, HttpServletResponse.SC_FORBIDDEN,
                        ErrorCodes.CALLER_NOT_PROVISIONED,
                        "调用者 " + auth.caller() + " 未绑定任何 Agent（环境变量 "
                                + CallerAgentBindings.ENV + "），不能以 Agent 身份操作");
                return;
            }
        }

        CallerContext.set(auth.caller(), boundAgent);
        try {
            chain.doFilter(request, response);
        } finally {
            CallerContext.clear();
        }
    }

    private static boolean isProtected(String uri) {
        return uri != null && uri.startsWith(PROTECTED_PREFIX);
    }

    /** 只读方法：读放行是 loopback 档的明确定义，不是遗漏。 */
    private static boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    /**
     * 环回判定。
     *
     * <p>同时覆盖 IPv4（{@code 127.0.0.0/8}）、IPv6 环回（{@code ::1}）以及
     * <b>IPv4-mapped IPv6</b>（{@code ::ffff:127.x.x.x}）——后者在双栈环境下是常态，
     * 漏掉它会让"本机"判定在本机也失败，从而把演示与用例一起打死。</p>
     */
    public static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) return false;
        String a = remoteAddr.trim();
        if ("::1".equals(a) || "0:0:0:0:0:0:0:1".equals(a) || "localhost".equalsIgnoreCase(a)) {
            return true;
        }
        if (a.startsWith("127.")) return true;
        return a.startsWith("::ffff:127.") || "::ffff:0:0:0:1".equals(a);
    }

    private void deny(HttpServletRequest request, HttpServletResponse response,
                      int status, String code, String message) throws IOException {
        String detail = module + " " + request.getMethod() + " " + request.getRequestURI()
                + " 被拒：" + code;
        if (audit != null) {
            // 拒绝事件必须留痕：谁、从哪、要做什么。不记录令牌内容。
            audit.appendAuto("API_CALLER_DENIED",
                    code + "|" + module + "|" + request.getMethod() + "|"
                            + request.getRequestURI() + "|" + request.getRemoteAddr());
        }
        LOG.warning(detail + "（来源 " + request.getRemoteAddr() + "）");
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            // RFC 6750 §3.1 要求 401 携带挑战。
            // 注意：JDK 的 HttpURLConnection 在「带 body 的请求 + 401」时会抛
            // HttpRetryException（与是否有本头无关，是 JDK 自身限制）；
            // 这类客户端请改用 Apache HttpClient / OkHttp，或从 errorStream 读状态。
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\""
                + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }
}
