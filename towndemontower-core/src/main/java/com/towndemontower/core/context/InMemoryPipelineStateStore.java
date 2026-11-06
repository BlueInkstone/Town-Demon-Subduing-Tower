package com.towndemontower.core.context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [core] 内存版 PipelineStateStore。put 时存副本，get 时返回副本，保证落盘与运行态隔离。
 * 后续可换成 YAML/DB 实现，接口不变（starter 负责装配）。
 */
public class InMemoryPipelineStateStore implements PipelineStateStore {
    private final Map<String, PipelineState> store = new ConcurrentHashMap<>();

    @Override
    public PipelineState load(String taskId) {
        PipelineState s = store.get(taskId);
        return s == null ? null : s.copy();
    }

    @Override
    public void save(String taskId, PipelineState state) {
        store.put(taskId, state.copy());
    }

    @Override
    public boolean exists(String taskId) {
        return store.containsKey(taskId);
    }
}
