package com.towndemontower.core.executor;

import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;

/**
 * [core] 网关"只裁决不执行"链的终点执行器（docs/38 决策①）。
 *
 * <p>网关形态要求复用前几层裁决，但<b>不真正执行业务方法</b>、也不经过
 * {@code SandboxedToolExecutor}（它是唯一发牌点，跳过它就不会误发 GovernancePass 令牌）。
 * 当所有裁决层都放行时，本执行器返回 {@code SkillResult.success(null)}，语义是"允许执行"——
 * 真正执行由已接入网关的客户端负责，网关只给裁决，不替调用方动手。</p>
 */
public class VerdictTerminalExecutor implements Executor {

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        return SkillResult.success(null);
    }
}
