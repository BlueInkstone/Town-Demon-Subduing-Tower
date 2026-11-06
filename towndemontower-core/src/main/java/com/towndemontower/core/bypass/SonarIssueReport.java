package com.towndemontower.core.bypass;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [core] 把扫描结果转成 SonarQube <b>通用问题导入</b> 格式（零依赖，手写 JSON）。
 *
 * <p>为什么是这个形态而不是"写个 SonarQube 插件"：插件要 {@code sonar-plugin-api}、
 * 要联网、要一个跑着的服务端，等于把"离线可用"这条基线换掉。通用问题报告是 SonarQube 的
 * 输入格式，由接入方在 CI 里挂到 {@code sonar.externalIssuesReportPaths} 即可——
 * 规则在本地就能跑，SonarQube 只负责汇总展示。</p>
 */
public final class SonarIssueReport {

    private static final String ENGINE_ID = "towndemontower";

    private static final Map<String, String> RULE_NAMES = new LinkedHashMap<>();
    private static final Map<String, String> RULE_DESCRIPTIONS = new LinkedHashMap<>();

    static {
        RULE_NAMES.put(BypassScanner.R1, "受治理注解必须可被代理拦截");
        RULE_DESCRIPTIONS.put(BypassScanner.R1,
                "@AgentSkill 只能标在 public、非 static/final/private/abstract 的方法上，否则 AOP 织不进去。");
        RULE_NAMES.put(BypassScanner.R2, "受治理方法体内禁止直接副作用");
        RULE_DESCRIPTIONS.put(BypassScanner.R2,
                "方法体内出现 ProcessBuilder / Runtime.getRuntime / Files.write / new FileOutputStream"
                        + " / HttpClient / DriverManager 时，副作用绕过了治理链。");
        RULE_NAMES.put(BypassScanner.R3, "受治理方法必须申请通行令牌");
        RULE_DESCRIPTIONS.put(BypassScanner.R3,
                "方法体首条语句必须是 GovernancePass.require(\"工具名\")，且与 @AgentSkill(name=...) 一致。");
        RULE_NAMES.put(BypassScanner.R4, "Controller 不得直接注入 Service");
        RULE_DESCRIPTIONS.put(BypassScanner.R4,
                "Controller 直接调用 Service 会跳过治理链；应改为调用受治理的工具入口。");
        RULE_NAMES.put(BypassScanner.R5, "命令模板不得过宽");
        RULE_DESCRIPTIONS.put(BypassScanner.R5,
                "commandTemplate 里若没有任何固定词（如 {cmd*}），等价于没有声明模板，I5 失效。");
        RULE_NAMES.put(BypassScanner.META, "豁免声明必须合法");
        RULE_DESCRIPTIONS.put(BypassScanner.META,
                "豁免注释必须写明规则号与非空理由，否则视为违规。");
    }

    private SonarIssueReport() {
    }

    /**
     * @param issues 需要上报的问题（已排除被豁免的条目）
     */
    public static String toJson(List<BypassFinding> issues) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n  \"rules\": [\n");
        boolean firstRule = true;
        for (Map.Entry<String, String> e : RULE_NAMES.entrySet()) {
            if (!firstRule) sb.append(",\n");
            firstRule = false;
            // R4/R5 是"提示级"：R4 的写法被认可、R5 有时是刻意透传，都不该阻断合并
            boolean blocker = !BypassScanner.R4.equals(e.getKey())
                    && !BypassScanner.R5.equals(e.getKey());
            sb.append("    {\"id\": \"").append(esc(e.getKey()))
                    .append("\", \"name\": \"").append(esc(e.getValue()))
                    .append("\", \"description\": \"").append(esc(RULE_DESCRIPTIONS.get(e.getKey())))
                    .append("\", \"engineId\": \"").append(ENGINE_ID)
                    .append("\", \"cleanCodeAttribute\": \"CONVENTIONAL\"")
                    .append(", \"type\": \"CODE_SMELL\"")
                    .append(", \"severity\": \"").append(blocker ? "BLOCKER" : "MAJOR")
                    .append("\", \"impacts\": [{\"softwareQuality\": \"SECURITY\", \"severity\": \"")
                    .append(blocker ? "HIGH" : "MEDIUM").append("\"}]}");
        }
        sb.append("\n  ],\n  \"issues\": [\n");

        boolean firstIssue = true;
        for (BypassFinding f : issues) {
            if (!firstIssue) sb.append(",\n");
            firstIssue = false;
            sb.append("    {\"ruleId\": \"").append(esc(f.ruleId))
                    .append("\", \"engineId\": \"").append(ENGINE_ID)
                    .append("\", \"primaryLocation\": {\"message\": \"").append(esc(f.message))
                    .append("\", \"filePath\": \"").append(esc(f.file))
                    .append("\", \"textRange\": {\"startLine\": ").append(f.line)
                    .append(", \"endLine\": ").append(f.line).append("}}}");
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    /** JSON 字符串转义（只处理规范要求的最小集，够用且不引依赖）。 */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
