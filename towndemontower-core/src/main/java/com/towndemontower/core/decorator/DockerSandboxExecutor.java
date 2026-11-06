package com.towndemontower.core.decorator;

import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.executor.CancellationToken;
import com.towndemontower.core.executor.SkillRegistry;

/**
 * [V1.2.1] 容器级沙箱执行器（实现 Executor）。用于 shell 类工具：命令取自 intent.args["command"]，
 * 经 DockerSandboxStrategy 在隔离容器中执行。取消令牌在调用前与执行中被检查。
 *
 * 此实现替换 ProcessSandboxExecutor 即可获得容器隔离；装饰器链（Audit→...→Sandbox）无需改动，
 * 仅需在装配阶段把最内层 Executor 换成本类。
 */
public class DockerSandboxExecutor implements Executor {
    private final SkillRegistry registry;
    private final DockerSandboxStrategy strategy;
    private final CancellationToken token;

    public DockerSandboxExecutor(SkillRegistry registry, DockerSandboxStrategy strategy, CancellationToken token) {
        this.registry = registry;
        this.strategy = strategy;
        this.token = token;
    }

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        if (token != null && token.isCancelled()) {
            return SkillResult.denied(ErrorCodes.CANCELLED, "cancelled before docker execution: " + intent.tool);
        }
        SkillDescriptor d = registry.get(intent.tool);
        if (d == null) {
            return SkillResult.denied(ErrorCodes.MODE_TOOL_NOT_ALLOWED, "unknown tool " + intent.tool);
        }
        Object cmdObj = intent.args == null ? null : intent.args.get("command");
        if (!(cmdObj instanceof String)) {
            return SkillResult.denied(ErrorCodes.INTENT_FORMAT_INVALID, "docker sandbox requires args.command");
        }
        try {
            String out = strategy.execute((String) cmdObj, token);
            return SkillResult.success(out);
        } catch (DockerSandboxStrategy.DockerUnavailableException e) {
            // 沙箱基础设施不可用：降级为明确的拒绝码，不向上抛未捕获异常
            return SkillResult.denied(ErrorCodes.SANDBOX_UNAVAILABLE, "docker unavailable: " + e.getMessage());
        } catch (Exception e) {
            return SkillResult.denied(ErrorCodes.GATE_SCRIPT_FAILED, "docker sandbox error: " + e.getMessage());
        }
    }
}
