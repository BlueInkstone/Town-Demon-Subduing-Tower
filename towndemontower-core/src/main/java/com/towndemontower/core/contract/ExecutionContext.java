package com.towndemontower.core.contract;

import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.executor.CancellationToken;

/**
 * [core] 执行上下文。mode 是第三层工具白名单的来源。
 * tainted 表示该会话读取过不可信来源，后续写/外发需额外校验。
 * cancellationToken（V1.2/NFR-5）用于急停在途 Tool 调用；可为 null（旧调用方兼容）。
 */
public class ExecutionContext {
    public final String sessionId;
    public final String taskId;
    public final String tenantId;
    public final String agentId;
    public final String ownerUserId;
    public final boolean tainted;
    public final AgentMode mode;
    public final CancellationToken cancellationToken;

    public ExecutionContext(String sessionId, String taskId, String tenantId,
                            String agentId, String ownerUserId, boolean tainted, AgentMode mode) {
        this(sessionId, taskId, tenantId, agentId, ownerUserId, tainted, mode, null);
    }

    public ExecutionContext(String sessionId, String taskId, String tenantId,
                            String agentId, String ownerUserId, boolean tainted,
                            AgentMode mode, CancellationToken cancellationToken) {
        this.sessionId = sessionId;
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.ownerUserId = ownerUserId;
        this.tainted = tainted;
        this.mode = mode;
        this.cancellationToken = cancellationToken;
    }
}
