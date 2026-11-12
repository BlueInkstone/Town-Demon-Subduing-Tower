package com.towndemontower.core.approval;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [core] 审批单记录（不可变）。字段用制表符分隔单行落盘，避免 core 引入 JSON 依赖。
 *
 * <p>双轨解释：{@link #impactNote} 是"术语原文"（给审批人看的影响说明），
 * {@link #rollbackPlan} 是"能否退回"的承诺原文。二者都必须非空才能建单——
 * 一张说不出影响、也说不出怎么退的审批单，不应该让任何人去点"同意"。</p>
 */
public final class ApprovalRecord {

    public final String id;
    public final String taskId;
    public final String tool;
    public final String riskLevel;
    public final String impactNote;
    public final String rollbackPlan;
    public final String requestedBy;
    public final ApprovalStatus status;
    public final Instant createdAt;
    public final Instant deadline;
    public final String approver;
    public final String comment;
    public final Instant decidedAt;

    public ApprovalRecord(String id, String taskId, String tool, String riskLevel,
                          String impactNote, String rollbackPlan, String requestedBy,
                          ApprovalStatus status, Instant createdAt, Instant deadline,
                          String approver, String comment, Instant decidedAt) {
        this.id = id;
        this.taskId = taskId;
        this.tool = tool;
        this.riskLevel = riskLevel;
        this.impactNote = impactNote;
        this.rollbackPlan = rollbackPlan;
        this.requestedBy = requestedBy;
        this.status = status;
        this.createdAt = createdAt;
        this.deadline = deadline;
        this.approver = approver;
        this.comment = comment;
        this.decidedAt = decidedAt;
    }

    public ApprovalRecord with(ApprovalStatus newStatus, String newApprover, String newComment, Instant at) {
        return new ApprovalRecord(id, taskId, tool, riskLevel, impactNote, rollbackPlan, requestedBy,
                newStatus, createdAt, deadline, newApprover, newComment, at);
    }

    /** 是否已终态。 */
    public boolean decided() {
        return status != ApprovalStatus.PENDING;
    }

    /* ---------- 落盘格式 ---------- */

    public String toLine() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("taskId", taskId);
        m.put("tool", tool);
        m.put("risk", riskLevel);
        m.put("impact", impactNote);
        m.put("rollback", rollbackPlan);
        m.put("by", requestedBy);
        m.put("status", status.name());
        m.put("createdAt", str(createdAt));
        m.put("deadline", str(deadline));
        m.put("approver", approver);
        m.put("comment", comment);
        m.put("decidedAt", str(decidedAt));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey()).append('\t').append(clean(e.getValue())).append('\n');
        }
        return sb.toString();
    }

    static ApprovalRecord parse(String blob) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : blob.split("\n")) {
            int i = line.indexOf('\t');
            if (i > 0) m.put(line.substring(0, i), line.substring(i + 1));
        }
        if (!m.containsKey("id")) return null;
        return new ApprovalRecord(
                m.get("id"), m.get("taskId"), m.get("tool"), m.get("risk"),
                m.get("impact"), m.get("rollback"), m.get("by"),
                ApprovalStatus.valueOf(orDefault(m.get("status"), "PENDING")),
                instant(m.get("createdAt")), instant(m.get("deadline")),
                m.get("approver"), m.get("comment"), instant(m.get("decidedAt")));
    }

    private static String orDefault(String v, String d) {
        return (v == null || v.trim().isEmpty()) ? d : v;
    }

    private static String str(Instant i) {
        return i == null ? "" : i.toString();
    }

    private static Instant instant(String s) {
        return (s == null || s.trim().isEmpty()) ? null : Instant.parse(s);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }
}
