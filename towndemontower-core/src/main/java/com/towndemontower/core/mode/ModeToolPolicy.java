package com.towndemontower.core.mode;

import java.util.Set;

/**
 * [core] 模式工具白名单策略（第三层 AND）。输入模式，输出允许工具名集合。
 * 模型看不到白名单外的工具，但 Executor 仍二次校验（防伪造工具名）。
 */
public interface ModeToolPolicy {
    Set<String> allowedTools(AgentMode mode);
}
