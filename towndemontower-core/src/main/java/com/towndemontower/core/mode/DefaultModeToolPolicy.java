package com.towndemontower.core.mode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * [core] 默认模式白名单（对齐 README 8.1）。
 * PLANNING 仅 splitTask/writeDesign；DEV 为 writeFile/runCompile 与运维执行类；
 * AUDIT 仅只读扫描工具。如需自定义，改 YAML 后换实现（机制写死必经校验）。
 *
 * <p><b>白名单只列已注册工具</b>：以前 DEV 白名单里写着 {@code readFile}，但
 * {@code SkillRegistry} 从未注册过它，于是调用方按白名单发请求会拿到"未知工具"的拒绝——
 * 白名单描述"这个模式允许哪些用途"，不能承诺"这些工具一定可用"。
 * 移除未注册项后，白名单之外与"注册表里没有"由不同错误码区分（前者在这里拦，
 * 后者在风险闸门拦），排查时不会再混淆。要恢复读文件能力，应先在注册表注册该工具再回到这里放行。</p>
 */
public class DefaultModeToolPolicy implements ModeToolPolicy {
    private final Map<AgentMode, Set<String>> table = new EnumMap<>(AgentMode.class);

    public DefaultModeToolPolicy() {
        table.put(AgentMode.ARCHITECT_PLANNING, set("splitTask", "writeDesign"));
        // 执行态：开发+运维执行类工具（含 R1/R2/R3）。R3 虽可见，但风险闸门仍默认拒绝（AND 关系）。
        // 依据 README 2.4：Ops 使用 DEV_EXECUTION（受限），允许部署/重启类 R1/R2 工具。
        table.put(AgentMode.DEV_EXECUTION,
                set("writeFile", "runCompile", "restartService", "deployService", "deleteData"));
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
