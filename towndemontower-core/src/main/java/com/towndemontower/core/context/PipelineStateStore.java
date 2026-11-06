package com.towndemontower.core.context;

/**
 * [core] PipelineState 存储接口（内存 / YAML / DB 实现可插拔）。
 */
public interface PipelineStateStore {
    PipelineState load(String taskId);

    void save(String taskId, PipelineState state);

    boolean exists(String taskId);
}
