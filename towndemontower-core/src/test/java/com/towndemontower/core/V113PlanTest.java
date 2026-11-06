package com.towndemontower.core;

import com.towndemontower.core.approval.ApprovalDecision;
import com.towndemontower.core.approval.ApprovalPolicy;
import com.towndemontower.core.approval.PlanApproval;
import com.towndemontower.core.approval.PlanApprovalService;
import com.towndemontower.core.approval.PlanStatus;
import com.towndemontower.core.audit.AuditRecord;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.decorator.RiskGateDecorator;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.policy.DefaultRiskGate;
import com.towndemontower.core.policy.SimpleShellAstAnalyzer;

import java.util.HashMap;

/**
 * [V1.13] Plan-and-Execute：计划审批闸门。
 *
 * <p>钉住四件事：未批计划不能动、批了才动、批的是"这个任务"不是"这个人"、以及
 * 服务未装配时**拒绝而不是放行**。</p>
 */
public class V113PlanTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) {
        serviceBasics();
        planLifecycleIsAudited();
        gateBlocksUntilApproved();
        scopeIsPerTaskAndAgent();
        failClosedWithoutService();

        System.out.println();
        System.out.println("RESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // 服务本身
    // ------------------------------------------------------------------

    static void serviceBasics() {
        PlanApprovalService svc = new PlanApprovalService();

        check("服务-初始未批准", !svc.isApproved("agent-1", "task-1"));

        String id = svc.request("agent-1", "task-1", "先拆任务再执行");
        check("服务-提交后为 PENDING",
                svc.get(id).status == PlanStatus.PENDING && !svc.isApproved("agent-1", "task-1"));

        check("服务-禁自批", throwsIae(() -> svc.approve(id, "agent-1", "自己批自己")));

        svc.approve(id, "human-1", "同意");
        check("服务-批准后生效", svc.isApproved("agent-1", "task-1")
                && svc.get(id).status == PlanStatus.APPROVED
                && "human-1".equals(svc.get(id).decidedBy));

        // 重复提交：已有 PENDING/已批的单会被复用，不会堆出一堆待办
        String again = svc.request("agent-1", "task-1", "再提一次");
        check("服务-重复提交复用同一张单", id.equals(again));

        // 否决后不可补批，必须重新提交
        String id2 = svc.request("agent-2", "task-2", "另一份计划");
        svc.reject(id2, "human-1", "方案不完整");
        check("服务-否决后状态为 REJECTED", svc.get(id2).status == PlanStatus.REJECTED);
        check("服务-否决不可补批", throwsIse(() -> svc.approve(id2, "human-1", "改主意了")));
        String id3 = svc.request("agent-2", "task-2", "修订版");
        check("服务-否决后可重新提交", !id3.equals(id2)
                && svc.get(id3).status == PlanStatus.PENDING);

        check("服务-单号不存在报错", throwsIae(() -> svc.approve("P-none", "human-1", "x")));
        check("服务-参数校验", throwsIae(() -> svc.request("", "task-1", "x")));
        check("服务-可列举", svc.all().size() == 3);
    }

    // ------------------------------------------------------------------
    // 审计：计划生命周期是治理事件
    // ------------------------------------------------------------------

    static void planLifecycleIsAudited() {
        HashChainAuditStore audit = new HashChainAuditStore(null);
        PlanApprovalService svc = new PlanApprovalService(audit);

        String id = svc.request("agent-a", "task-a", "敏感摘要：不要进审计");
        svc.approve(id, "human-a", "同意");
        svc.reject(svc.request("agent-b", "task-b", "另一份"), "human-b", "不做");

        String actions = audit.all().stream().map(r -> r.action).reduce("", (a, b) -> a + "," + b);
        check("审计-提交/批准/否决都留痕",
                actions.contains("PLAN_REQUESTED")
                        && actions.contains("PLAN_APPROVED")
                        && actions.contains("PLAN_REJECTED"));
        check("审计-审批人可追溯",
                audit.all().stream().anyMatch(r -> r.payload.contains("by=human-a"))
                        && audit.all().stream().anyMatch(r -> r.payload.contains("by=human-b")));
        check("审计-摘要不入链",
                audit.all().stream().noneMatch(r -> r.payload.contains("敏感摘要")));
        check("审计-哈希链完整", audit.verifyChain());
    }

    // ------------------------------------------------------------------
    // 闸门
    // ------------------------------------------------------------------

    static void gateBlocksUntilApproved() {
        SkillRegistry reg = new SkillRegistry();
        reg.register(new SkillDescriptor("applyPatch", "R1",
                new String[0], new String[0], new String[]{"file"}, true, "ephemeral",
                null, true));
        PlanApprovalService svc = new PlanApprovalService();
        Executor gate = gateOf(reg, svc);

        SkillResult denied = gate.execute(intent("applyPatch"), ctx("agent-1", "task-1"));
        check("闸门-未提交计划被拦", !denied.success
                && ErrorCodes.PLAN_APPROVAL_REQUIRED.equals(denied.code));
        check("闸门-提示里说明尚未提交",
                denied.message != null && denied.message.contains("尚未提交"));

        String id = svc.request("agent-1", "task-1", "改这三个文件");
        SkillResult pending = gate.execute(intent("applyPatch"), ctx("agent-1", "task-1"));
        check("闸门-待审批仍被拦", !pending.success
                && ErrorCodes.PLAN_APPROVAL_REQUIRED.equals(pending.code));
        check("闸门-提示里带计划单号",
                pending.message != null && pending.message.contains(id));

        svc.approve(id, "human-1", "同意");
        SkillResult ok = gate.execute(intent("applyPatch"), ctx("agent-1", "task-1"));
        check("闸门-批准后放行", ok.success);

        // 被否的计划不能执行
        String id2 = svc.request("agent-9", "task-9", "另一份");
        svc.reject(id2, "human-1", "不做");
        SkillResult rejected = gate.execute(intent("applyPatch"), ctx("agent-9", "task-9"));
        check("闸门-否决后仍被拦", !rejected.success
                && ErrorCodes.PLAN_APPROVAL_REQUIRED.equals(rejected.code));
    }

    static void scopeIsPerTaskAndAgent() {
        SkillRegistry reg = new SkillRegistry();
        reg.register(new SkillDescriptor("applyPatch", "R1",
                new String[0], new String[0], new String[]{"file"}, true, "ephemeral",
                null, true));
        PlanApprovalService svc = new PlanApprovalService();
        Executor gate = gateOf(reg, svc);

        String id = svc.request("agent-1", "task-1", "计划");
        svc.approve(id, "human-1", "同意");

        check("作用域-同任务放行", gate.execute(intent("applyPatch"), ctx("agent-1", "task-1")).success);
        check("作用域-换任务被拦",
                !gate.execute(intent("applyPatch"), ctx("agent-1", "task-2")).success);
        check("作用域-换 agent 被拦",
                !gate.execute(intent("applyPatch"), ctx("agent-2", "task-1")).success);
    }

    static void failClosedWithoutService() {
        SkillRegistry reg = new SkillRegistry();
        reg.register(new SkillDescriptor("applyPatch", "R1",
                new String[0], new String[0], new String[]{"file"}, true, "ephemeral",
                null, true));
        // 不传 PlanApprovalService：没接线 ≠ 不需要审批
        Executor gate = new RiskGateDecorator((i, c) -> SkillResult.success("ran"),
                new DefaultRiskGate(new SimpleShellAstAnalyzer()),
                approveAll(), reg);

        SkillResult r = gate.execute(intent("applyPatch"), ctx("agent-1", "task-1"));
        check("fail-closed-未装配服务时拒绝", !r.success
                && ErrorCodes.PLAN_APPROVAL_REQUIRED.equals(r.code)
                && r.message.contains("未装配"));

        // 未声明计划要求的存量工具不受影响
        reg.register(new SkillDescriptor("plain", "R1",
                new String[0], new String[0], new String[0], true, "ephemeral"));
        check("fail-closed-未声明工具照常放行",
                gate.execute(intent("plain"), ctx("agent-1", "task-1")).success);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    static Executor gateOf(SkillRegistry reg, PlanApprovalService svc) {
        return new RiskGateDecorator((i, c) -> SkillResult.success("ran"),
                new DefaultRiskGate(new SimpleShellAstAnalyzer()),
                approveAll(), reg, null, svc);
    }

    static ApprovalPolicy approveAll() {
        return (ticket, ctx) -> ApprovalDecision.APPROVE;
    }

    static IntentObject intent(String tool) {
        return new IntentObject(tool, new HashMap<>(), 0.9, null, null, null);
    }

    static ExecutionContext ctx(String agentId, String taskId) {
        return new ExecutionContext("s", taskId, "tenant", agentId, "owner", false,
                AgentMode.DEV_EXECUTION);
    }

    interface Call {
        void run();
    }

    static boolean throwsIae(Call c) {
        try {
            c.run();
            return false;
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }

    static boolean throwsIse(Call c) {
        try {
            c.run();
            return false;
        } catch (IllegalStateException ex) {
            return true;
        }
    }
}
