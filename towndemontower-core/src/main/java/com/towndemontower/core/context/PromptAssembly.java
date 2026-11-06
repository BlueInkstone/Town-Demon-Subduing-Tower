package com.towndemontower.core.context;

import com.towndemontower.core.routing.ModelProfile;

import java.util.List;

/**
 * [core] 一次模型调用的提示词装配结果。
 *
 * <p>{@link #prefix} 与 {@link #dynamic} 分开保存，是缓存策略的载体：只有前缀进缓存键，
 * 动态段每次不同属正常现象，不应计入未命中。{@link #cacheHit} 表示"本进程此前见过同一前缀"。</p>
 */
public final class PromptAssembly {

    /** 固定前缀（角色 + 输出契约 + 工具清单）。 */
    public final String prefix;
    /** 动态段（任务、子任务、上次门禁结果、上次裁决）。 */
    public final String dynamic;
    /** 前缀哈希，用作缓存键。 */
    public final String cacheKey;
    /** 本次路由到的模型画像，可为 null（未装配路由时）。 */
    public final ModelProfile model;
    /** 提示词中可见的工具（= 模式白名单）。 */
    public final List<String> tools;
    /** 是否命中进程内前缀缓存。 */
    public final boolean cacheHit;

    public PromptAssembly(String prefix, String dynamic, String cacheKey,
                          ModelProfile model, List<String> tools, boolean cacheHit) {
        this.prefix = prefix;
        this.dynamic = dynamic;
        this.cacheKey = cacheKey;
        this.model = model;
        this.tools = tools == null ? List.of() : tools;
        this.cacheHit = cacheHit;
    }

    /** 实际发送给模型的完整文本：固定前缀在前，动态内容在后。 */
    public String full() {
        return prefix + "\n" + dynamic;
    }

    /** 供 PipelineState 落盘的路由记录，如 {@code strong-llm(strong,high)@ARCHITECT_PLANNING}。 */
    public String routeRecord() {
        return model == null ? null : model + "|prefix=" + cacheKey;
    }
}
