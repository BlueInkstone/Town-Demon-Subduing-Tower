package com.towndemontower.agents.governance;

import com.towndemontower.agents.domain.Agent;
import com.towndemontower.agents.service.AgentService;
import com.towndemontower.core.auth.CallerContext;
import com.towndemontower.core.contract.ErrorCodes;

/**
 * [agents] 把"认证出的调用者"落成"治理链里的 Agent 身份"（V1.9 / I3-b）。
 *
 * <p>职责只有一个：回答本次请求该用哪个 {@code agentId}。三种情况：</p>
 * <ol>
 *   <li><b>未绑定</b>（{@link CallerContext#agentId()} 为 null，含 loopback / auth-mode=off）——
 *       回落 {@code towndemontower.agents.agent-id}，行为与 V1.8 完全一致；</li>
 *   <li><b>绑定命中且 Agent 可用</b>——用绑定的 ID，RBAC 按<b>那个 Agent</b> 的权限裁决，
 *       审计按<b>那个 Agent</b> 归属；</li>
 *   <li><b>绑定命中但 Agent 不存在 / 非 ACTIVE</b>——直接拒绝（{@code CALLER_AGENT_UNAVAILABLE}）。</li>
 * </ol>
 *
 * <p><b>为什么不靠 RBAC 自然拒绝</b>：停用（SUSPENDED / RETIRED）的 Agent 会被回收权限，
 * 不检查也会在 Permission 环节被拒——但那样返回的是 {@code PERMISSION_DENIED}，
 * 运维看到的是"权限不足"，真实原因却是"绑定指向了一个已停用的 Agent"。
 * 这两个修复动作完全不同（补权限 vs 改绑定/恢复状态），所以在此提前分辨。</p>
 *
 * <p><b>为什么要求 ACTIVE 而不是"保留权限"</b>：PAUSED 仍保留权限（便于恢复），
 * 但它的语义就是"暂停执行"。让一个暂停的 Agent 代表调用者去<b>改注册表</b>，
 * 等于绕过暂停这个动作本身。这里从紧：只要不能执行，就不能代表调用者。</p>
 *
 * <p>只读仓储、不进治理链：这是一次内存/文件读取，若走链会给每个请求平白加一条审计。</p>
 */
public class CallerBindingGuard {

    private final AgentService service;
    private final String fallbackAgentId;

    public CallerBindingGuard(AgentService service, String fallbackAgentId) {
        this.service = service;
        this.fallbackAgentId = fallbackAgentId;
    }

    /** 回落身份（未绑定时的 agentId）。 */
    public String fallbackAgentId() {
        return fallbackAgentId;
    }

    /**
     * 解析本次请求应使用的 Agent 身份。
     *
     * @throws AgentGovernanceException 绑定的 Agent 不存在或非 ACTIVE
     *                                  （HTTP 403，码 {@code CALLER_AGENT_UNAVAILABLE}）
     */
    public String resolveAgentId() {
        String bound = CallerContext.agentId();
        if (bound == null) return fallbackAgentId;

        Agent agent = service.findById(bound).orElse(null);
        if (agent == null) {
            throw new AgentGovernanceException(ErrorCodes.CALLER_AGENT_UNAVAILABLE,
                    "调用者 " + CallerContext.current() + " 绑定的 Agent " + bound
                            + " 不存在（可能已注销并删除档案），请更新绑定表 " + bindHint());
        }
        if (!agent.status().canAct()) {
            throw new AgentGovernanceException(ErrorCodes.CALLER_AGENT_UNAVAILABLE,
                    "调用者 " + CallerContext.current() + " 绑定的 Agent " + bound
                            + " 当前状态为 " + agent.status().name()
                            + "，只有 ACTIVE 的 Agent 可以代表调用者操作注册表");
        }
        return bound;
    }

    private static String bindHint() {
        return "TOWNDEMONTOWER_API_BINDINGS";
    }
}
