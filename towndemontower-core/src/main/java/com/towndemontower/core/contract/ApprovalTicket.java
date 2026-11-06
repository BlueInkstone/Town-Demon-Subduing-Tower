package com.towndemontower.core.contract;

/**
 * [core] 审批单。R2 必须附影响说明 + 回滚计划；R3 不进普通审批，只给转人工终端指引。
 */
public class ApprovalTicket {
    public final String id;
    public final String taskId;
    public final String tool;
    public final String riskLevel;
    public final String impactNote;
    public final String rollbackPlan;

    public ApprovalTicket(String id, String taskId, String tool, String riskLevel,
                          String impactNote, String rollbackPlan) {
        this.id = id;
        this.taskId = taskId;
        this.tool = tool;
        this.riskLevel = riskLevel;
        this.impactNote = impactNote;
        this.rollbackPlan = rollbackPlan;
    }
}
