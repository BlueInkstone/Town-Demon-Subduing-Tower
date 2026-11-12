package com.towndemontower.gateway.core;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.contract.ExecutionContext;

/**
 * [gateway] 入口层审计：**没进裁决链的拒绝也要留痕**。
 *
 * <p>此前网关只在 {@code AuditDecorator}（裁决链最外层）写审计，于是"链都没进"的请求一条记录都没有：
 * 缺令牌、错令牌、非 POST、非法 JSON、缺字段、参数超长——恰好是最该查的那几类
 * （有人在猜令牌时，审计里看不到任何痕迹）。这里补上入口层的写入口。</p>
 *
 * <p><b>为什么用独立 action 而不是混进 {@code SKILL_INVOKED}</b>：一次没有工具的请求如果记成
 * "某技能被调用"，行为分析器会把它算进该 Agent 的拒绝率与连续拒绝长度，可能凭空触发降权建议。
 * 入口层拒绝与"技能被拒"是两件事，必须可区分；代价是行为分析器会把它们计入"跳过"，
 * 覆盖率会低于 100%（该口径本来就是这样设计的，见 {@code AuditBehaviorAnalyzer}）。</p>
 *
 * <p><b>payload 形状</b>与链内记录同构，便于同一套解析：
 * <pre>tool|code|stage=..|task=..|mode=..|agent=..|owner=..</pre>
 * 工具名未知时用 {@code -} 占位，保证第一列恒非空。</p>
 *
 * <p><b>写失败不改结论</b>：本类只服务"已经决定拒绝"的路径，落盘失败不会让请求变成放行，
 * 因此只记 stderr，不把异常抛给调用方（抛出去会把 401 变成 500，反而掩盖真实语义）。</p>
 */
public final class GatewayAudit {

    /** 入口层拒绝的动作名（非技能调用）。 */
    public static final String ACTION_REJECTED = "GATEWAY_REJECTED";

    /** 方法不允许。 */
    public static final String STAGE_METHOD = "METHOD";
    /** 请求体非法（非 JSON / 超长）。 */
    public static final String STAGE_BODY = "BODY";
    /** 鉴权失败（缺令牌 / 错令牌 / 服务端未配令牌）。 */
    public static final String STAGE_AUTH = "AUTH";
    /** 意图解析失败（缺字段、context 非法）。 */
    public static final String STAGE_INTENT = "INTENT";
    /** 入参校验失败（超长、控制字符）。 */
    public static final String STAGE_INPUT = "INPUT";

    /** 工具名未知时的占位符。 */
    public static final String UNKNOWN_TOOL = "-";

    private GatewayAudit() {
    }

    /**
     * 组装 payload；字段顺序固定，空值的写法<b>与链内记录逐字节一致</b>：
     * {@code task} 缺失时是字面量 {@code null}（链内是字符串拼接的产物），
     * {@code agent}/{@code owner} 缺失时是空串（同 {@code attribution}）。
     * 这里不做"更漂亮"的统一——两套写法同时存在才是解析脚本的灾难。
     */
    public static String payload(String tool, String code, String stage,
                                 String taskId, String mode, String agentId, String owner) {
        return tool(tool) + "|" + code + "|stage=" + stage
                + "|task=" + String.valueOf(taskId)
                + "|mode=" + nullSafe(mode)
                + "|agent=" + nullSafe(agentId)
                + "|owner=" + nullSafe(owner);
    }

    /** 有上下文时按上下文归属。 */
    public static void record(AuditStore audit, String tool, String code, String stage, ExecutionContext ctx) {
        if (ctx == null) {
            record(audit, tool, code, stage, null, null, null, null);
            return;
        }
        record(audit, tool, code, stage, ctx.taskId, String.valueOf(ctx.mode), ctx.agentId, ctx.ownerUserId);
    }

    /** 没有上下文（例如鉴权阶段还没解析出 context）时按服务端配置归属。 */
    public static void record(AuditStore audit, String tool, String code, String stage,
                              String taskId, String mode, String agentId, String owner) {
        if (audit == null) {
            return;
        }
        try {
            audit.appendAuto(ACTION_REJECTED, payload(tool, code, stage, taskId, mode, agentId, owner));
        } catch (RuntimeException ex) {
            System.err.println("[gateway] entry audit write failed: "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static String tool(String tool) {
        return (tool == null || tool.isEmpty()) ? UNKNOWN_TOOL : tool;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
