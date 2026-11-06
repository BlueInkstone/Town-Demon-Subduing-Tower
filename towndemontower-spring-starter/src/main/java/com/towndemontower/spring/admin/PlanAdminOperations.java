package com.towndemontower.spring.admin;

import com.towndemontower.core.approval.PlanApproval;
import com.towndemontower.core.approval.PlanApprovalService;

import java.util.List;

/**
 * [starter/admin] 计划审批的管理面用例层。
 *
 * <p>存在的理由不是"再包一层"，而是让 Controller 不直接持有领域服务：
 * CP10-R4 把"Controller 直接注入 *Service"视为可能的绕过面（当前只告警，
 * {@code --strict} 下阻断）。管理面本身是治理入口，不该靠豁免注释绕开规则，
 * 也不该为对齐规则而改类名——所以把调用收敛到这里，Controller 只做路由与视图。</p>
 */
public class PlanAdminOperations {

    private final PlanApprovalService plans;

    public PlanAdminOperations(PlanApprovalService plans) {
        this.plans = plans;
    }

    public List<PlanApproval> all() {
        return plans.all();
    }

    public PlanApproval get(String id) {
        return plans.get(id);
    }

    public PlanApproval approve(String id, String approver, String note) {
        return plans.approve(id, approver, note);
    }

    public PlanApproval reject(String id, String approver, String note) {
        return plans.reject(id, approver, note);
    }
}
