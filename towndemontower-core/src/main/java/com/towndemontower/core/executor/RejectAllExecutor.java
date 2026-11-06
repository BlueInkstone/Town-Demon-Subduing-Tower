package com.towndemontower.core.executor;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;

/**
 * [core] 熔断执行器：治理被关闭时使用，<b>拒绝一切调用</b>而不是放行。
 *
 * <p>这是安全默认值的核心一环。此前 {@code towndemontower.enabled=false} 的语义是
 * "不装配切面"，等于<b>关掉治理 = 全部放行</b>——一个为了本地调试方便的开关，
 * 实际成了最彻底的后门。正确语义应是"关掉治理 = 全部拒绝"：
 * 关掉安检门不会让人随便进，而是干脆不出票。</p>
 *
 * <p>每一次被熔断的调用都会写审计（{@code GOVERNANCE_DISABLED_CALL}），
 * 因此"谁在什么时候关的治理、之后有哪些调用被拦"是可查的。</p>
 */
public class RejectAllExecutor implements Executor {

    private final AuditStore audit;

    public RejectAllExecutor(AuditStore audit) {
        this.audit = audit;
    }

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        String tool = intent == null ? "?" : intent.tool;
        String agent = ctx == null ? "?" : ctx.agentId;
        if (audit != null) {
            audit.appendAuto("GOVERNANCE_DISABLED_CALL", tool + "|agent=" + agent);
        }
        return SkillResult.denied(ErrorCodes.GOVERNANCE_DISABLED,
                "治理已停用，调用被拒绝（fail-closed）：" + tool);
    }
}
