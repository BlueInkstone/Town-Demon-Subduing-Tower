package com.towndemontower.core.contract;

/**
 * [core] 错误码常量（跨语言契约，字符串枚举）。
 */
public final class ErrorCodes {
    public static final String INTENT_FORMAT_INVALID = "INTENT_FORMAT_INVALID";
    public static final String NEED_MORE_INFO = "NEED_MORE_INFO";
    public static final String R3_DENIED = "R3_DENIED";
    public static final String TAINTED_WRITE_BLOCKED = "TAINTED_WRITE_BLOCKED";
    public static final String MODE_TOOL_NOT_ALLOWED = "MODE_TOOL_NOT_ALLOWED";
    public static final String MODE_TRANSITION_INVALID = "MODE_TRANSITION_INVALID";
    public static final String PLAN_APPROVAL_REQUIRED = "PLAN_APPROVAL_REQUIRED";
    public static final String GATE_SCRIPT_FAILED = "GATE_SCRIPT_FAILED";
    public static final String AST_ANALYSIS_FALLBACK_R2 = "AST_ANALYSIS_FALLBACK_R2";
    public static final String SNAPSHOT_FAILED = "SNAPSHOT_FAILED";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String CANCELLED = "CANCELLED";
    public static final String SANDBOX_UNAVAILABLE = "SANDBOX_UNAVAILABLE";
    /** 治理被停用时的 fail-closed 拒绝（V1.6）。 */
    public static final String GOVERNANCE_DISABLED = "GOVERNANCE_DISABLED";
    /** 参数未通过输入校验（超长/控制字符/路径穿越）。 */
    public static final String INPUT_REJECTED = "INPUT_REJECTED";
    /** 门禁密钥不可用（未通过环境变量提供或强度不足），门禁脚本一律不执行（V1.7）。 */
    public static final String GATE_KEY_UNAVAILABLE = "GATE_KEY_UNAVAILABLE";
    /** 调用者未通过认证：缺 Authorization 头、方案不是 Bearer、或令牌无效（V1.8）。 */
    public static final String CALLER_UNAUTHENTICATED = "CALLER_UNAUTHENTICATED";
    /** 令牌表为空，认证不可用——此时一律拒绝，不存在"没配就放行"（V1.8）。 */
    public static final String AUTH_TOKEN_UNAVAILABLE = "AUTH_TOKEN_UNAVAILABLE";
    /** 写操作来自非本机地址（loopback 模式）被拒（V1.8）。 */
    public static final String CALLER_NOT_LOCAL = "CALLER_NOT_LOCAL";
    /**
     * 调用者已认证，但没有绑定到任何 Agent（V1.9 / I3-b）。
     *
     * <p>只在绑定表非空（严格模式）时出现：此时"令牌有效"仅代表"服务身份可信"，
     * 不代表"允许以某个 Agent 的身份操作注册表"。</p>
     */
    public static final String CALLER_NOT_PROVISIONED = "CALLER_NOT_PROVISIONED";
    /**
     * 调用者绑定的 Agent 不可用：不存在、已删除，或状态不是 ACTIVE（V1.9 / I3-b）。
     *
     * <p>与 {@link #CALLER_NOT_PROVISIONED} 分开，是因为修复动作不同：前者要改绑定表，
     * 后者要恢复 Agent 状态或修正绑定指向。</p>
     */
    public static final String CALLER_AGENT_UNAVAILABLE = "CALLER_AGENT_UNAVAILABLE";
    /**
     * 非受治理调用（V1.11 / D2 / CP-1）：受治理方法的实现首行要求通行令牌，
     * 但当前线程栈上没有——典型原因是自调用（AOP 不生效）或绕过切面直调下层 Service。
     *
     * <p>这是"代码写错了"而不是"业务被拒了"：正常情况下计数必须恒为 0，
     * 非零即意味着治理链被跳过。</p>
     */
    public static final String UNGOVERNED_INVOCATION = "UNGOVERNED_INVOCATION";

    /**
     * 命令形状与工具声明的模板不符（V1.12 / I5）。
     *
     * <p>含义很明确：这个工具只允许"这一种形状"的命令，实际命令多了/少了/换了词，
     * 或者参数值里带了控制操作符。它不是"命令危险"（那由 R 级别与 AST 判），
     * 而是"命令不可预测"——不可预测就没有可判定的边界。</p>
     */
    public static final String COMMAND_TEMPLATE_MISMATCH = "COMMAND_TEMPLATE_MISMATCH";

    /** 模板声明本身不合法（占位符写错、字面量含控制操作符）。属开发期错误，运行期按拒绝处理。 */
    public static final String COMMAND_TEMPLATE_INVALID = "COMMAND_TEMPLATE_INVALID";

    private ErrorCodes() {
    }
}
