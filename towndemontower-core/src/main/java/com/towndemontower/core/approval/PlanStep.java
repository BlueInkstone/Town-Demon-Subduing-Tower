package com.towndemontower.core.approval;

/**
 * [core] 计划中的一步（V1.14）。
 *
 * <p>{@code tool} 是这一步允许使用的工具名；为空表示"只描述意图、不绑定具体工具"。
 * 闸门只认<b>显式工具名</b>：一个任务里只要计划列出了工具，没列出的工具就不能执行——
 * 这是把 v1.13 的"任务级批准"收窄成"计划覆盖范围"。</p>
 */
public final class PlanStep {

    /** 允许使用的工具名；空串表示未绑定工具。 */
    public final String tool;
    /** 这一步要做什么（人读）。 */
    public final String intent;

    public PlanStep(String tool, String intent) {
        this.tool = tool == null ? "" : tool.trim();
        this.intent = intent == null ? "" : intent.trim();
        if (this.intent.isEmpty()) {
            throw new IllegalArgumentException("计划步骤的 intent 不能为空");
        }
    }

    public static PlanStep of(String tool, String intent) {
        return new PlanStep(tool, intent);
    }

    /** 是否点名了这个工具。 */
    public boolean names(String toolName) {
        return toolName != null && this.tool.equals(toolName);
    }

    @Override
    public String toString() {
        return "PlanStep{" + (tool.isEmpty() ? "*" : tool) + ": " + intent + "}";
    }
}
