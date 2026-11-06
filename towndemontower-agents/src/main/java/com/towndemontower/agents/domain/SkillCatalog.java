package com.towndemontower.agents.domain;

import java.util.List;
import java.util.Set;

/**
 * [agents] 技能目录：判断"某个工具名是否真实存在"。
 *
 * <p>领域层不直接依赖 core 的 {@code SkillRegistry}——注册表要能脱离治理内核单独测试。
 * Spring 装配时由 {@code RegistrySkillCatalog} 适配真实的工具注册表。</p>
 *
 * <p><b>为什么必须校验</b>：档案里的技能名若拼错（{@code deployService} 写成
 * {@code deployServices}），治理链在 Schema/Capability 环节才报错，且报的是
 * "未知工具"这种底层错误，排查成本极高。在注册时校验，错误信息可以直接指向字段。</p>
 */
public interface SkillCatalog {

    /** 工具名是否存在于系统工具注册表。 */
    boolean exists(String skill);

    /** 系统当前全部工具名（供前端渲染技能多选）。 */
    Set<String> all();

    /** 空目录：不做校验的宽松模式（轻量嵌入场景）。 */
    static SkillCatalog unlimited() {
        return new SkillCatalog() {
            @Override
            public boolean exists(String skill) {
                return skill != null && !skill.isBlank();
            }

            @Override
            public Set<String> all() {
                return Set.of();
            }
        };
    }

    /** 便捷方法：把一批技能名中不存在的挑出来。 */
    default List<String> unknown(List<String> skills) {
        if (skills == null) return List.of();
        return skills.stream().filter(s -> !exists(s)).toList();
    }
}
