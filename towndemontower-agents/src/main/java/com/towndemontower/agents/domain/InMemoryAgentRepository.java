package com.towndemontower.agents.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [agents] 内存版 Agent 档案仓储（默认实现，零外部依赖，线程安全）。
 *
 * <p>ID 格式 {@code AGT-0001}，由仓储自增生成；从文件载入历史数据后会跳过已占用的序号，
 * 避免重启后新注册的 Agent 与历史 ID 撞号。</p>
 */
public class InMemoryAgentRepository implements AgentRepository {

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    @Override
    public Agent save(Agent agent) {
        if (agent == null) throw new IllegalArgumentException("agent must not be null");
        Agent toSave = (agent.id() == null || agent.id().isBlank())
                ? agent.withId(nextId())
                : agent;
        agents.put(toSave.id(), toSave);
        return toSave;
    }

    @Override
    public Optional<Agent> findById(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(agents.get(id));
    }

    @Override
    public Optional<Agent> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String target = name.trim();
        for (Agent a : agents.values()) {
            if (a.name() != null && a.name().trim().equalsIgnoreCase(target)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<Agent> findAll() {
        List<Agent> all = new ArrayList<>(agents.values());
        all.sort(Comparator.comparing(Agent::id, Comparator.nullsLast(Comparator.naturalOrder())));
        return all;
    }

    @Override
    public boolean deleteById(String id) {
        return id != null && agents.remove(id) != null;
    }

    @Override
    public int count() {
        return agents.size();
    }

    /** 生成形如 AGT-0007 的自增 ID（跳过已被占用的号）。 */
    private String nextId() {
        while (true) {
            String candidate = String.format("AGT-%04d", seq.incrementAndGet());
            if (!agents.containsKey(candidate)) return candidate;
        }
    }
}
