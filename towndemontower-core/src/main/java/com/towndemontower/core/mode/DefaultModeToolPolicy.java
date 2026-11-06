package com.towndemontower.core.mode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * [core] 默认模式白名单（对齐 README 8.1）。
 * PLANNING 仅 splitTask/writeDesign；DEV 仅 readFile/writeFile/runCompile；
 * AUDIT 仅只读扫描工具。如需自定义，改 YAML 后换实现（机制写死必经校验）。
 */
public class DefaultModeToolPolicy implements ModeToolPolicy {
    private final Map<AgentMode, Set<String>> table = new EnumMap<>(AgentMode.class);

    public DefaultModeToolPolicy() {
        table.put(AgentMode.ARCHITECT_PLANNING, set("splitTask", "writeDesign"));
        // 执行态：开发+运维执行类工具（含 R1/R2/R3）。R3 虽可见，但风险闸门仍默认拒绝（AND 关系）。
        // 依据 README 2.4：Ops 使用 DEV_EXECUTION（受限），允许部署/重启类 R1/R2 工具。
        table.put(AgentMode.DEV_EXECUTION,
                set("readFile", "writeFile", "runCompile", "restartService", "deployService", "deleteData"));
        table.put(AgentMode.AUDIT_REVIEWING, set("readDiff", "runRuleScan", "readTestReport"));
    }

    private static Set<String> set(String... tools) {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, tools);
        return s;
    }

    @Override
    public Set<String> allowedTools(AgentMode mode) {
        Set<String> allowed = table.get(mode);
        return allowed == null ? Collections.emptySet() : Collections.unmodifiableSet(allowed);
    }
}
