package com.towndemontower.core.context;

import com.towndemontower.core.contract.ExecutionContext;

/**
 * [core] 上下文选择器接口。按模式决定注入哪些上下文（规划态看需求+目录树；执行态看当前文件+报错）。
 * 固定前缀在前（系统提示/工具定义/角色约束），动态内容在后，缓存友好。
 */
public interface ContextSelector {
    String id();

    String select(ExecutionContext ctx);
}
