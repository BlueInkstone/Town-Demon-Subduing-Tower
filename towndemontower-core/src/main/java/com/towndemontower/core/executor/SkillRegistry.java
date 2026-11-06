package com.towndemontower.core.executor;

import com.towndemontower.core.contract.SkillDescriptor;

import java.util.HashMap;
import java.util.Map;

/**
 * [core] 技能注册表。registry.get(tool) 解析工具元数据。
 * 预置最小可用工具集，覆盖 R0-R3 与三模式白名单，供链路演示与测试。
 */
public class SkillRegistry {
    private final Map<String, SkillDescriptor> map = new HashMap<>();

    public SkillRegistry() {
        register(new SkillDescriptor("readLog", "R0",
                new String[]{"log:read"}, new String[]{"read"}, new String[]{"path"},
                false, "read-only"));
        register(new SkillDescriptor("restartService", "R1",
                new String[]{"ops:restart"}, new String[]{"ops"}, new String[]{"service"},
                true, "ephemeral"));
        register(new SkillDescriptor("deployService", "R2",
                new String[]{"ops:deploy"}, new String[]{"ops", "deploy"}, new String[]{"target", "version"},
                true, "ephemeral"));
        register(new SkillDescriptor("deleteData", "R3",
                new String[]{"data:delete"}, new String[]{"data"}, new String[]{"table"},
                true, "ephemeral"));
        register(new SkillDescriptor("writeFile", "R1",
                new String[]{"fs:write"}, new String[]{"fs"}, new String[]{"path", "content"},
                true, "ephemeral"));
        register(new SkillDescriptor("runCompile", "R0",
                new String[]{"build:run"}, new String[]{"build"}, new String[]{"module"},
                false, "ephemeral"));
        register(new SkillDescriptor("splitTask", "R0",
                new String[]{"plan:write"}, new String[]{"plan"}, new String[]{"requirement"},
                false, "none"));
        register(new SkillDescriptor("writeDesign", "R0",
                new String[]{"plan:write"}, new String[]{"plan"}, new String[]{"topic"},
                false, "none"));
        register(new SkillDescriptor("readDiff", "R0",
                new String[]{"review:read"}, new String[]{"review"}, new String[]{"ref"},
                false, "none"));
        register(new SkillDescriptor("runRuleScan", "R0",
                new String[]{"review:read"}, new String[]{"review"}, new String[]{"scope"},
                false, "none"));
        register(new SkillDescriptor("readTestReport", "R0",
                new String[]{"review:read"}, new String[]{"review"}, new String[]{"suite"},
                false, "none"));
    }

    public void register(SkillDescriptor d) {
        map.put(d.name, d);
    }

    public SkillDescriptor get(String tool) {
        return map.get(tool);
    }

    public boolean contains(String tool) {
        return map.containsKey(tool);
    }

    /**
     * 全部已注册工具名（只读、按字典序）。
     *
     * <p>供上层做"可选工具清单"展示与校验（例如员工花名册渲染技能多选、
     * 校验员工是否被授予了系统里不存在的工具）。</p>
     */
    public java.util.Set<String> names() {
        return java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(map.keySet()));
    }
}
