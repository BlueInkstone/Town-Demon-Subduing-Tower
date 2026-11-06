package com.towndemontower.core.context;

import com.towndemontower.core.mode.AgentMode;

/**
 * [core] 流水线状态（可落盘）。存：当前模式、子任务、门禁结果、PlanArtifact 引用、模型路由记录。
 * 每次子任务只取最小必要上下文，不把全量历史硬塞给模型。
 */
public class PipelineState {
    public String taskId;
    public AgentMode mode;
    public String[] subTasks;
    public String gateResults;
    public String planArtifactRef;
    public String modelRouteRecord;
    /** 最近一次治理裁决的结果码（OK / R3_DENIED / PENDING_APPROVAL / …）。 */
    public String lastDecision;
    /** 最近一次更新时间（ISO-8601），用于判断状态是否陈旧。 */
    public String updatedAt;

    public PipelineState() {
    }

    public PipelineState(String taskId, AgentMode mode, String[] subTasks,
                        String gateResults, String planArtifactRef, String modelRouteRecord) {
        this.taskId = taskId;
        this.mode = mode;
        this.subTasks = subTasks;
        this.gateResults = gateResults;
        this.planArtifactRef = planArtifactRef;
        this.modelRouteRecord = modelRouteRecord;
    }

    /** 深拷贝，避免落盘后被外部引用篡改。 */
    public PipelineState copy() {
        PipelineState c = new PipelineState(taskId, mode,
                subTasks == null ? null : subTasks.clone(),
                gateResults, planArtifactRef, modelRouteRecord);
        c.lastDecision = lastDecision;
        c.updatedAt = updatedAt;
        return c;
    }
}
