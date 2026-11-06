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
 * [V1.2] 进程级沙箱执行器（实现 Executor）。用于 shell 类工具：命令取自 intent.args["command"]，
 * 经 SandboxStrategy 在隔离进程中执行。取消令牌在调用前与执行中被检查。
 * 注：现有内存链路（SandboxedToolExecutor + invoker）保持不变，本类作为可选的"硬隔离"注入点。
 */
public class ProcessSandboxExecutor implements Executor {
    private final SkillRegistry registry;
    private final SandboxStrategy strategy;
    private final CancellationToken token;

    public ProcessSandboxExecutor(SkillRegistry registry, SandboxStrategy strategy, CancellationToken token) {
        this.registry = registry;
        this.strategy = strategy;
        this.token = token;
    }

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        if (token != null && token.isCancelled()) {
            return SkillResult.denied(ErrorCodes.CANCELLED, "cancelled before execution: " + intent.tool);
        }
        SkillDescriptor d = registry.get(intent.tool);
        if (d == null) {
            return SkillResult.denied(ErrorCodes.MODE_TOOL_NOT_ALLOWED, "unknown tool " + intent.tool);
        }
        Object cmdObj = intent.args == null ? null : intent.args.get("command");
        if (!(cmdObj instanceof String)) {
            return SkillResult.denied(ErrorCodes.INTENT_FORMAT_INVALID, "process sandbox requires args.command");
        }
        try {
            String out = strategy.execute((String) cmdObj, token);
            return SkillResult.success(out);
        } catch (Exception e) {
            return SkillResult.denied(ErrorCodes.GATE_SCRIPT_FAILED, "sandbox error: " + e.getMessage());
        }
    }
}
