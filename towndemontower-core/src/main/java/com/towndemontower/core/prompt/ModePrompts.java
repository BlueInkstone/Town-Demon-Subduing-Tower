package com.towndemontower.core.prompt;

import com.towndemontower.core.mode.AgentMode;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * [core] 按模式生成系统提示词前缀（固定段）。
 *
 * <p><b>只写可执行的行为约束</b>，不写元认知与免责声明：
 * 不出现"请谨慎""不要执行危险操作""要考虑损失厌恶"这类句子——它们对模型没有约束力，
 * 只会稀释真正有效的格式要求，也让"是否遵守"无法判定。约束必须能被机器检查：
 * 输出什么结构、允许调用哪些工具、被拒后怎么改。</p>
 *
 * <p><b>工具清单按模式白名单生成</b>，于是"模型看不到的工具不可调用"从口号变成输入事实：
 * 规划态根本不会看到 {@code writeFile}/{@code deployService}。排序后输出，保证前缀稳定、
 * 可被 prompt 缓存复用（顺序一变缓存全废）。</p>
 */
public final class ModePrompts {

    private ModePrompts() {
    }

    /** 固定前缀：角色 + 输出契约 + 该模式允许的工具（排序、稳定）。 */
    public static String prefix(AgentMode mode, Collection<String> allowedTools) {
        String head;
        switch (mode) {
            case ARCHITECT_PLANNING:
                head = "角色：规划者。本阶段只产出计划制品与拆分后的子任务，不执行任何写操作。\n"
                        + "允许的工具仅限下列清单，清单外的工具调用会被网关拒绝。\n";
                break;
            case DEV_EXECUTION:
                head = "角色：执行者。按已批准的计划逐项执行，每次只调用一个工具。\n"
                        + "允许的工具仅限下列清单，清单外的工具调用会被网关拒绝。\n";
                break;
            case AUDIT_REVIEWING:
                head = "角色：审计者。本阶段只做只读检查与结论输出，不修改任何文件。\n"
                        + "允许的工具仅限下列清单，清单外的工具调用会被网关拒绝。\n";
                break;
            default:
                head = "";
                break;
        }
        return head + "输出格式（强制）：一个 JSON 对象，字段 tool / args / confidence / expectedEffect / rollbackHint。\n"
                + "禁止输出建议性自然语言（如\"建议你执行…\"），此类输出会被解析层直接拒绝。\n"
                + "confidence 低于 0.5 时将被要求补充信息，而不是被执行。\n"
                + "被拒绝时：读取返回的 code 与 message，修正参数后重试，同一意图最多重试 2 次。\n"
                + "\n"
                + toolSection(allowedTools);
    }

    private static String toolSection(Collection<String> tools) {
        if (tools == null || tools.isEmpty()) return "允许的工具：（无）\n";
        StringBuilder sb = new StringBuilder("允许的工具：\n");
        for (String t : new TreeSet<>(tools)) {
            sb.append("- ").append(t).append('\n');
        }
        return sb.toString();
    }

    /** 当前模式的一句话职责，用于审计与看板展示。 */
    public static String responsibility(AgentMode mode) {
        switch (mode) {
            case ARCHITECT_PLANNING:
                return "拆解需求、产出计划制品";
            case DEV_EXECUTION:
                return "按批准的计划执行工具调用";
            case AUDIT_REVIEWING:
                return "只读复核与结论输出";
            default:
                return "";
        }
    }

    /** 三种模式的固定前缀是否互不相同（供自检与测试使用）。 */
    public static List<AgentMode> modes() {
        return Arrays.asList(AgentMode.ARCHITECT_PLANNING, AgentMode.DEV_EXECUTION, AgentMode.AUDIT_REVIEWING);
    }
}
