package com.towndemontower.spring.admin;

import com.towndemontower.core.approval.ApprovalCenter;
import com.towndemontower.core.approval.ApprovalRecord;
import com.towndemontower.core.approval.ApprovalStatus;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [starter/admin] R3 人工审批单管理接口。
 *
 * <p>对应 {@link ApprovalCenter}：高风险操作被风险闸门拦下后登记的单据。
 * 只提供"看单、批单、驳单"，<b>不提供建单</b>——建单必须由治理链在拦截时生成，
 * 否则等于给了一个绕过闸门、直接造审批凭据的口子。</p>
 *
 * <p>所有决策动作都会写审计（由 {@link ApprovalCenter} 落盘），本层不额外造审计事件，
 * 避免同一动作出现两条互相矛盾的证据。</p>
 */
@RestController
@RequestMapping("/towndemontower/admin/approvals")
public class ApprovalAdminController {

    private final ApprovalCenter center;
    private final AdminGuard guard;

    public ApprovalAdminController(ApprovalCenter center, TownDemontowerProperties props) {
        this.center = center;
        this.guard = new AdminGuard(props);
    }

    /** 审批单列表；{@code status} 可过滤 PENDING / APPROVED / DENIED / EXPIRED。 */
    @GetMapping
    public ApiResponse list(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token,
            @RequestParam(value = "status", required = false) String status) {
        guard.check(token);
        ApprovalStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = ApprovalStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new AdminApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS",
                        "未知审批状态：" + status + "（可选 PENDING/APPROVED/DENIED/EXPIRED）");
            }
        }
        List<ApprovalRecord> list = filter == null ? center.list() : center.list(filter);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", list.size());
        data.put("items", list.stream().map(ApprovalAdminController::view).toList());
        return ApiResponse.ok(data);
    }

    @GetMapping("/{id}")
    public ApiResponse get(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token,
            @PathVariable("id") String id) {
        guard.check(token);
        return ApiResponse.ok(view(mustFind(id)));
    }

    /** 批准。禁自批；非 PENDING 返回 409。 */
    @PostMapping("/{id}/approve")
    public ApiResponse approve(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token,
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        guard.check(token);
        ApprovalRecord record = mustFind(id);
        String approver = AdminParams.text(body, "approver");
        String comment = AdminParams.text(body, "comment");
        requireApprover(record, approver);
        requirePending(record);
        boolean ok = center.approve(id, approver, comment, Instant.now());
        if (!ok) {
            // 走到这里说明状态在"检查"和"决策"之间变了（并发或刚好超时）。
            // 把当前状态带回去，调用方据此刷新，而不是重试到成功。
            throw new AdminApiException(HttpStatus.CONFLICT, "CONFLICT",
                    "审批单状态已变更，当前状态：" + center.statusOf(id));
        }
        return ApiResponse.ok(view(center.find(id).orElse(record)));
    }

    /** 驳回。禁自驳；非 PENDING 返回 409。 */
    @PostMapping("/{id}/deny")
    public ApiResponse deny(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token,
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        guard.check(token);
        ApprovalRecord record = mustFind(id);
        String approver = AdminParams.text(body, "approver");
        String comment = AdminParams.text(body, "comment");
        requireApprover(record, approver);
        requirePending(record);
        boolean ok = center.deny(id, approver, comment, Instant.now());
        if (!ok) {
            throw new AdminApiException(HttpStatus.CONFLICT, "CONFLICT",
                    "审批单状态已变更，当前状态：" + center.statusOf(id));
        }
        return ApiResponse.ok(view(center.find(id).orElse(record)));
    }

    private ApprovalRecord mustFind(String id) {
        return center.find(id)
                .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "审批单不存在：" + id));
    }

    private static void requireApprover(ApprovalRecord record, String approver) {
        if (approver == null) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "approver 不能为空");
        }
        if (approver.equals(record.requestedBy)) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "SELF_APPROVAL",
                    "申请人不能自批：approver 不能是 " + record.requestedBy);
        }
    }

    private static void requirePending(ApprovalRecord record) {
        if (record.status != ApprovalStatus.PENDING) {
            throw new AdminApiException(HttpStatus.CONFLICT, "NOT_PENDING",
                    "审批单已是 " + record.status + "，不能再次决策");
        }
    }

    static Map<String, Object> view(ApprovalRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("taskId", r.taskId);
        m.put("tool", r.tool);
        m.put("riskLevel", r.riskLevel);
        m.put("impactNote", r.impactNote);
        m.put("rollbackPlan", r.rollbackPlan);
        m.put("requestedBy", r.requestedBy);
        m.put("status", r.status.name());
        m.put("createdAt", iso(r.createdAt));
        m.put("deadline", iso(r.deadline));
        m.put("approver", r.approver);
        m.put("comment", r.comment);
        m.put("decidedAt", iso(r.decidedAt));
        return m;
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }
}
