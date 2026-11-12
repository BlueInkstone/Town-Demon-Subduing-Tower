package com.towndemontower.core;

import java.nio.file.Paths;
import java.util.Arrays;

import com.towndemontower.core.approval.ApprovalDecision;
import com.towndemontower.core.approval.ApprovalPolicy;
import com.towndemontower.core.approval.FilePlanApprovalStore;
import com.towndemontower.core.approval.PlanApproval;
import com.towndemontower.core.approval.PlanApprovalService;
import com.towndemontower.core.approval.PlanArtifact;
import com.towndemontower.core.approval.PlanStatus;
import com.towndemontower.core.approval.PlanStep;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;

/**
 * [V1.14] 结构化计划 + 计划单跨重启回放。
 *
 * <p>钉住五件事：旧格式计划仍按任务级放行；结构化计划只放行点名的工具；
 * 计划单能从文件回放（状态/步骤/审批人不丢）；重启后单号不撞车；文件损坏拒绝启动。</p>
 */
public class V114PlanArtifactTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        artifactSemantics();
        serviceWithSteps();
        gateChecksToolCoverage();
        reviseIsAudited();
        persistenceRoundTrip();
        persistenceRejectedAndSeq();
        corruptFileFailsClosed();
        latestTieBreakBySeq();

        System.out.println();
        System.out.println("RESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // 模型
    // ------------------------------------------------------------------

    static void artifactSemantics() {
        PlanArtifact legacy = PlanArtifact.text("旧格式");
        check("模型-旧格式不限定工具", !legacy.hasToolScope() && legacy.coversTool("applyPatch"));
        check("模型-旧格式工具范围文案", legacy.describeTools().contains("未限定"));

        PlanArtifact structured = PlanArtifact.of("结构化", Arrays.asList(
                PlanStep.of("applyPatch", "改三个文件"),
                PlanStep.of("runTests", "跑回归"),
                PlanStep.of("", "人工复核")));
        check("模型-工具去重", structured.tools().size() == 2
                && structured.tools().contains("applyPatch") && structured.tools().contains("runTests"));
        check("模型-覆盖点名工具", structured.coversTool("applyPatch"));
        check("模型-不覆盖未点名工具", !structured.coversTool("deleteFile"));
        check("模型-空工具名不受理", !structured.coversTool(null) && !structured.coversTool(" "));
        PlanArtifact generic = PlanArtifact.of("x", Arrays.asList(PlanStep.of("", "看看再说")));
        check("模型-纯描述步骤退回任务级",
                !generic.hasToolScope() && generic.coversTool("applyPatch"));
        check("模型-步骤意图必填", throwsIae(() -> PlanStep.of("applyPatch", "  ")));
    }

    // ------------------------------------------------------------------
    // 服务
    // ------------------------------------------------------------------

    static void serviceWithSteps() {
        PlanApprovalService svc = new PlanApprovalService();
        PlanArtifact plan = PlanArtifact.of("修复任务", Arrays.asList(
                PlanStep.of("applyPatch", "改代码"),
                PlanStep.of("runTests", "跑测试")));

        String id = svc.request("agent-1", "task-1", plan);
        PlanApproval p = svc.get(id);
        check("服务-结构化计划落单", p.status == PlanStatus.PENDING
                && p.plan.steps.size() == 2 && p.plan.tools().size() == 2);
        check("服务-未批准时不按工具放行", !svc.isApprovedFor("agent-1", "task-1", "applyPatch"));

        svc.approve(id, "human-1", "同意");
        check("服务-批准后覆盖点名工具", svc.isApprovedFor("agent-1", "task-1", "applyPatch"));
        check("服务-批准后仍不覆盖未点名工具", !svc.isApprovedFor("agent-1", "task-1", "deleteFile"));
        check("服务-摘要沿用计划",
                "修复任务".equals(p.summary) && "修复任务".equals(p.plan.summary));
    }

    // ------------------------------------------------------------------
    // 闸门：工具覆盖
    // ------------------------------------------------------------------

    static void gateChecksToolCoverage() {
        SkillRegistry reg = new SkillRegistry();
        reg.register(new SkillDescriptor("applyPatch", "R1",
                new String[0], new String[0], new String[]{"file"}, true, "ephemeral",
                null, true));
        reg.register(new SkillDescriptor("deleteFile", "R1",
                new String[0], new String[0], new String[]{"file"}, true, "ephemeral",
                null, true));
        PlanApprovalService svc = new PlanApprovalService();
        Executor gate = gateOf(reg, svc);

        PlanArtifact plan = PlanArtifact.of("只改不删", Arrays.asList(
                PlanStep.of("applyPatch", "改代码")));
        String id = svc.request("agent-1", "task-1", plan);
        svc.approve(id, "human-1", "同意");

        check("闸门-计划内工具放行",
                gate.execute(intent("applyPatch"), ctx("agent-1", "task-1")).success);
        SkillResult out = gate.execute(intent("deleteFile"), ctx("agent-1", "task-1"));
        check("闸门-计划外工具被拦", !out.success
                && ErrorCodes.PLAN_APPROVAL_REQUIRED.equals(out.code));
        check("闸门-拒绝文案说明范围", out.message != null
                && out.message.contains(id) && out.message.contains("applyPatch")
                && out.message.contains("不在已批准计划") && out.message.contains("revise"));

        // 扩大范围必须显式修订并重新审批；新单 PENDING 期间原工具也暂停（fail-closed）。
        String revised = svc.revise("agent-1", "task-1", PlanArtifact.of("改删都要", Arrays.asList(
                PlanStep.of("applyPatch", "改代码"),
                PlanStep.of("deleteFile", "删临时文件"))));
        check("修订-生成新单", !revised.equals(id)
                && svc.get(revised).status == PlanStatus.PENDING);
        check("修订-待审批期间原工具也暂停",
                !gate.execute(intent("applyPatch"), ctx("agent-1", "task-1")).success);
        svc.approve(revised, "human-1", "同意扩范围");
        check("修订-批准后两个工具都放行",
                gate.execute(intent("applyPatch"), ctx("agent-1", "task-1")).success
                        && gate.execute(intent("deleteFile"), ctx("agent-1", "task-1")).success);

        // 旧格式计划：仍然按任务级放行
        PlanApprovalService legacySvc = new PlanApprovalService();
        String legacyId = legacySvc.request("agent-2", "task-2", "旧格式计划");
        legacySvc.approve(legacyId, "human-1", "同意");
        Executor legacyGate = gateOf(reg, legacySvc);
        check("闸门-旧格式计划任务级放行",
                legacyGate.execute(intent("deleteFile"), ctx("agent-2", "task-2")).success);
    }

    static void reviseIsAudited() {
        HashChainAuditStore audit = new HashChainAuditStore(null);
        PlanApprovalService svc = new PlanApprovalService(audit);
        String id = svc.request("agent-1", "task-1", PlanArtifact.text("初始"));
        svc.approve(id, "human-1", "同意");
        String revised = svc.revise("agent-1", "task-1", PlanArtifact.of("扩大", Arrays.asList(
                PlanStep.of("applyPatch", "改代码"))));

        check("修订-审计动作可识别",
                audit.all().stream().anyMatch(r -> "PLAN_REVISED".equals(r.action)
                        && r.payload.contains(revised)));
        check("修订-修订不覆盖原批准动作",
                audit.all().stream().anyMatch(r -> "PLAN_APPROVED".equals(r.action)
                        && r.payload.contains(id)));
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    static void persistenceRoundTrip() throws Exception {
        Path file = tempFile("roundtrip");
        FilePlanApprovalStore store = new FilePlanApprovalStore(file);
        PlanApprovalService first = new PlanApprovalService(store, null);

        PlanArtifact plan = PlanArtifact.of("发布计划", Arrays.asList(
                PlanStep.of("applyPatch", "改配置"),
                PlanStep.of("runTests", "跑回归")));
        String id = first.request("agent-1", "task-1", plan);
        first.approve(id, "human-1", "同意发布");

        PlanApprovalService second = new PlanApprovalService(
                new FilePlanApprovalStore(file), null);
        PlanApproval p = second.get(id);
        check("持久化-重启后单还在", p != null);
        check("持久化-状态保留", p != null && p.status == PlanStatus.APPROVED);
        check("持久化-步骤保留", p != null && p.plan.steps.size() == 2
                && p.plan.coversTool("applyPatch") && p.plan.coversTool("runTests"));
        check("持久化-审批人与意见保留", p != null
                && "human-1".equals(p.decidedBy) && "同意发布".equals(p.note));
        check("持久化-重启后闸门直接放行",
                second.isApprovedFor("agent-1", "task-1", "applyPatch"));
        check("持久化-文件含表头", new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .startsWith("#PLAN-STORE v1"));
        check("持久化-快照行数=提交+批准", store.load().size() == 2);
    }

    static void persistenceRejectedAndSeq() throws Exception {
        Path file = tempFile("seq");
        FilePlanApprovalStore store = new FilePlanApprovalStore(file);
        PlanApprovalService first = new PlanApprovalService(store, null);

        String rejected = first.request("agent-2", "task-2", "会被否的计划");
        first.reject(rejected, "human-1", "窗口期不合适");
        String approvedId = first.request("agent-1", "task-1", "先批的计划");
        first.approve(approvedId, "human-1", "同意");

        long oldSeq = seqOf(rejected);
        PlanApprovalService second = new PlanApprovalService(
                new FilePlanApprovalStore(file), null);
        PlanApproval rej = second.get(rejected);
        check("持久化-否决状态保留", rej != null && rej.status == PlanStatus.REJECTED);
        check("持久化-否决理由保留", rej != null && "窗口期不合适".equals(rej.note));

        String next = second.request("agent-3", "task-3", "重启后的新计划");
        long newSeq = seqOf(next);
        check("持久化-重启后序号不回退", newSeq > oldSeq);
        check("持久化-新单不撞旧单", second.get(rejected) != null
                && !next.equals(rejected) && !next.equals(approvedId));
    }

    static void corruptFileFailsClosed() throws Exception {
        Path file = tempFile("corrupt");
        Files.write(file, ("#PLAN-STORE v1\nbroken-line\n").getBytes(StandardCharsets.UTF_8));
        check("损坏-拒绝启动", throwsIse(() -> new PlanApprovalService(
                new FilePlanApprovalStore(file), null)));
    }

    static void latestTieBreakBySeq() throws Exception {
        Path file = tempFile("tie");
        FilePlanApprovalStore store = new FilePlanApprovalStore(file);
        Instant same = Instant.ofEpochMilli(1000L);
        // 同一毫秒两张单：旧单 REJECTED、新单 PENDING，latest 必须是序号大的那张。
        store.save(new PlanApproval("P1000-1", "agent-1", "task-1", "旧计划",
                PlanStatus.REJECTED, same, same, "human-1", "否"));
        store.save(new PlanApproval("P1000-2", "agent-1", "task-1", "新计划",
                PlanStatus.PENDING, same, null, null, null));

        PlanApprovalService svc = new PlanApprovalService(
                new FilePlanApprovalStore(file), null);
        PlanApproval latest = svc.latest("agent-1", "task-1");
        check("回放-同毫秒取序号更大者", latest != null && "P1000-2".equals(latest.id)
                && latest.status == PlanStatus.PENDING);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    static Path tempFile(String tag) throws Exception {
        Path dir = Paths.get("target", "v114");
        Files.createDirectories(dir);
        return dir.resolve(tag + "-" + System.nanoTime() + ".log");
    }

    static long seqOf(String id) {
        return Long.parseLong(id.substring(id.lastIndexOf('-') + 1));
    }

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
        void run() throws Exception;
    }

    static boolean throwsIae(Call c) {
        try {
            c.run();
            return false;
        } catch (IllegalArgumentException ex) {
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    static boolean throwsIse(Call c) {
        try {
            c.run();
            return false;
        } catch (IllegalStateException ex) {
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
