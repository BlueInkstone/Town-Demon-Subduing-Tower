package com.towndemontower.spring.admin;

import com.towndemontower.core.approval.PlanApproval;
import com.towndemontower.core.approval.PlanStatus;
import com.towndemontower.core.approval.PlanStep;
import com.towndemontower.spring.TownDemontowerProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [starter/admin] 执行计划审批接口。
 *
 * <p>对应 {@link PlanApprovalService}：{@code PLAN_APPROVAL_REQUIRED} 闸门拦下任务后
 * 生成的计划单。与 R3 审批单的区别是"批准的是任务范围内的工具集"，不是单次操作。</p>
 *
 * <p><b>不提供提交/修订接口</b>：计划由 agent 在任务执行中提交，管理面只负责人工决策。
 * 允许管理面代提交，会让"谁提的计划"失去意义，也绕开了提交时的工具范围声明。</p>
 */
@RestController
@RequestMapping("/towndemontower/admin/plans")
public class PlanAdminController {

    private final PlanAdminOperations plans;
    private final AdminGuard guard;

    public PlanAdminController(PlanAdminOperations plans, TownDemontowerProperties props) {
        this.plans = plans;
        this.guard = new AdminGuard(props);
    }

    /** 计划列表（按提交时间升序）；可按状态 / agent / 任务过滤。 */
    @GetMapping
    public ApiResponse list(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "taskId", required = false) String taskId) {
        guard.check(token);
        PlanStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = PlanStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new AdminApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS",
                        "未知计划状态：" + status + "（可选 PENDING/APPROVED/REJECTED）");
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (PlanApproval p : plans.all()) {
            if (filter != null && p.status != filter) continue;
            if (agentId != null && !agentId.isBlank() && !agentId.equals(p.agentId)) continue;
            if (taskId != null && !taskId.isBlank() && !taskId.equals(p.taskId)) continue;
            items.add(view(p));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", items.size());
        data.put("items", items);
        return ApiResponse.ok(data);
    }

    @GetMapping("/{id}")
    public ApiResponse get(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token,
            @PathVariable("id") String id) {
        guard.check(token);
        return ApiResponse.ok(view(mustFind(id)));
    }

    /** 批准计划；已批准时幂等返回当前单，已否决返回 409。 */
    @PostMapping("/{id}/approve")
    public ApiResponse approve(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token,
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        guard.check(token);
        PlanApproval plan = mustFind(id);
        String approver = AdminParams.text(body, "approver");
        String note = AdminParams.text(body, "note");
        requireApprover(plan, approver);
        if (plan.status == PlanStatus.REJECTED) {
            throw new AdminApiException(HttpStatus.CONFLICT, "PLAN_REJECTED",
                    "计划 " + id + " 已被否决，不能补批；请让 agent 重新提交计划");
        }
        return ApiResponse.ok(view(plans.approve(id, approver, note)));
    }

    /** 否决计划；已否决时幂等返回当前单。 */
    @PostMapping("/{id}/reject")
    public ApiResponse reject(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token,
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        guard.check(token);
        PlanApproval plan = mustFind(id);
        String approver = AdminParams.text(body, "approver");
        String note = AdminParams.text(body, "note");
        requireApprover(plan, approver);
        return ApiResponse.ok(view(plans.reject(id, approver, note)));
    }

    private PlanApproval mustFind(String id) {
        PlanApproval p = plans.get(id);
        if (p == null) {
            throw new AdminApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "计划单不存在：" + id);
        }
        return p;
    }

    private static void requireApprover(PlanApproval plan, String approver) {
        if (approver == null) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "approver 不能为空");
        }
        if (approver.equals(plan.agentId)) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "SELF_APPROVAL",
                    "不能自批：审批人不能是提交计划的 agent（" + plan.agentId + "）");
        }
    }

    static Map<String, Object> view(PlanApproval p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("agentId", p.agentId);
        m.put("taskId", p.taskId);
        m.put("status", p.status.name());
        m.put("summary", p.summary);
        m.put("requestedAt", iso(p.requestedAt));
        m.put("decidedAt", iso(p.decidedAt));
        m.put("decidedBy", p.decidedBy);
        m.put("note", p.note);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("summary", p.plan.summary);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (PlanStep s : p.plan.steps) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("tool", s.tool);
            one.put("intent", s.intent);
            steps.add(one);
        }
        plan.put("steps", steps);
        m.put("plan", plan);
        return m;
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }
}
