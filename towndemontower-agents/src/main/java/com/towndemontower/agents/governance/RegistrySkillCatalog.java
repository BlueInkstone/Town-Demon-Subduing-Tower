package com.towndemontower.agents.governance;

import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.agents.domain.SkillCatalog;

import java.util.Set;
import java.util.TreeSet;

/**
 * [agents/governance] 技能目录的 core 适配器：把 core {@link SkillRegistry} 暴露成
 * 领域层可用的 {@link SkillCatalog}。
 *
 * <p>于是"档案里的技能"与"治理链实际能调用的工具"来自同一份注册表，
 * 不存在两套清单各自漂移的问题。</p>
 */
public class RegistrySkillCatalog implements SkillCatalog {

    private final SkillRegistry registry;

    public RegistrySkillCatalog(SkillRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        this.registry = registry;
    }

    @Override
    public boolean exists(String skill) {
        return skill != null && registry.contains(skill.trim());
    }

    @Override
    public Set<String> all() {
        return new TreeSet<>(registry.names());
    }
}
