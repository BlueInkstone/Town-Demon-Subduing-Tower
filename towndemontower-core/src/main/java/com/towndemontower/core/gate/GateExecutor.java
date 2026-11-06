package com.towndemontower.core.gate;

/**
 * [core] 门禁脚本实际执行器（在沙箱内执行，默认无网，只读项目目录+临时目录）。
 * 实现可插拔；测试时可注入不同行为（始终通过 / 始终失败）以验证重试与 escalate。
 */
@FunctionalInterface
public interface GateExecutor {
    boolean run(GateScript script);
}
