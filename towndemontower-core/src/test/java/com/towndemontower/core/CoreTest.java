package com.towndemontower.core;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.approval.ApprovalDecision;
import com.towndemontower.core.approval.ApprovalPolicy;
import com.towndemontower.core.approval.GuardianPrecheckImpl;
import com.towndemontower.core.approval.GuardianOpinion;
import com.towndemontower.core.approval.HumanApprovalPolicy;
import com.towndemontower.core.contract.*;
import com.towndemontower.core.context.InMemoryPipelineStateStore;
import com.towndemontower.core.context.PipelineState;
import com.towndemontower.core.context.PipelineStateStore;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.executor.ExecutorFactory;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;
import com.towndemontower.core.executor.SkillInvoker;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.executor.SnapshotService;
import com.towndemontower.core.gate.*;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.mode.DefaultModeToolPolicy;
import com.towndemontower.core.mode.ModeEvent;
import com.towndemontower.core.mode.ModeStateMachine;
import com.towndemontower.core.mode.ModeTransitionInvalidException;
import com.towndemontower.core.policy.DefaultRiskGate;
import com.towndemontower.core.policy.SimpleShellAstAnalyzer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * [core] 纯 Java 单元测试运行器（零 token、零 Spring、零外部依赖）。
 * 运行：javac 编译后 java com.towndemontower.core.CoreTest
 * 覆盖 PM AC-1~AC-6 与 8.4 测试清单核心项。
 */
public class CoreTest {

    /** 门禁密钥在单测中显式注入（生产只认环境变量 TOWNDEMONTOWER_GATE_KEY）。 */
    static final byte[] GATE_KEY =
            "core-test-gate-key-0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== Town Demon-Subduing Tower core 单元测试（纯 Java / 零 Spring） ===");

        testRiskGateLevels();
        testCompoundCommandMaxRisk();
        testUnknownDefaultR2();
        testRedirectNotReadonly();
        testAstFallbackR2();
        testConfidenceLowNeedMoreInfo();
        testSuggestionTextRejected();
        testModeWhitelistPLANNING();
        testModeWhitelistDEV();
        testIllegalModeTransition();
        testR3DeniedEvenForce();
        testR2PendingApproval();
        testPermissionDeniedStillAudited();
        testPipelineStateSaveLoad();
        testGateSignatureMismatch();
        testGateRetryExhausted();
        testGateRetrySucceed();
        testApprovalPolicyR3ReferTerminal();
        testGuardianCannotApprove();

        System.out.println("========================================");
        System.out.println("RESULT: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.out.println(">>> 存在失败用例，QA 不通过 <<<");
            System.exit(1);
        } else {
            System.out.println(">>> 全部通过，Phase 0 验收达成 <<<");
        }
    }

    // ---------- 测试基元 ----------
    static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    static Executor buildExecutor(PermissionEngine pe, AuditStore audit, boolean snapshotFail) {
        SkillRegistry reg = new SkillRegistry();
        SnapshotService ss = new SnapshotService();
        ss.failNext = snapshotFail;
        return ExecutorFactory.buildDefault(
                reg,
                new DefaultModeToolPolicy(),
                new DefaultRiskGate(new SimpleShellAstAnalyzer()),
                pe,
                ss,
                (d, intent) -> "executed:" + d.name,
                audit,
                new HumanApprovalPolicy());
    }

    static PermissionEngine permsAll() {
        PermissionEngine pe = new PermissionEngine();
        PermissionProvisioner.of(pe).grant("agent", "ops:restart", "ops:deploy", "data:delete",
                "fs:write", "build:run", "log:read", "plan:write", "review:read");
        return pe;
    }

    static ExecutionContext ctx(AgentMode mode, boolean tainted, String agent) {
        return new ExecutionContext("S", "T", "ten", agent, "owner", tainted, mode);
    }

    static IntentObject intent(String tool, double c, String reason, Map<String, Object> args) {
        return new IntentObject(tool, args, c, reason, "eff", "rollback");
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---------- 用例 ----------
    static void testRiskGateLevels() {
        DefaultRiskGate rg = new DefaultRiskGate(new SimpleShellAstAnalyzer());
        SkillRegistry reg = new SkillRegistry();
        check("R0 readLog=OK 级", rg.classify(reg.get("readLog"),
                intent("readLog", 0.9, "r", map("path", "x"))) == RiskLevel.R0);
        check("R1 restartService=R1", rg.classify(reg.get("restartService"),
                intent("restartService", 0.9, "r", map("service", "x"))) == RiskLevel.R1);
        check("R2 deployService=R2", rg.classify(reg.get("deployService"),
                intent("deployService", 0.9, "r", map("target", "x", "version", "1"))) == RiskLevel.R2);
        check("R3 deleteData=R3", rg.classify(reg.get("deleteData"),
                intent("deleteData", 0.9, "r", map("table", "x"))) == RiskLevel.R3);
    }

    static void testCompoundCommandMaxRisk() {
        DefaultRiskGate rg = new DefaultRiskGate(new SimpleShellAstAnalyzer());
        SkillDescriptor d = new SkillRegistry().get("readLog"); // R0 基线
        // 复合命令：含 rm -rf，取最高 → R3
        IntentObject it = intent("readLog", 0.9, "r", map("path", "x", "command", "rm -rf / ; echo hi"));
        check("复合命令取最高(含 rm -rf)=R3", rg.classify(d, it) == RiskLevel.R3);
    }

    static void testUnknownDefaultR2() {
        DefaultRiskGate rg = new DefaultRiskGate(new SimpleShellAstAnalyzer());
        SkillDescriptor unknown = new SkillDescriptor("weird", "ZZ",
                new String[]{}, new String[]{}, new String[]{}, false, "none");
        check("未知 risk 默认 R2", rg.classify(unknown, intent("weird", 0.9, "r", map())) == RiskLevel.R2);
        SkillDescriptor nullRisk = new SkillDescriptor("weird2", null,
                new String[]{}, new String[]{}, new String[]{}, false, "none");
        check("空 risk 默认 R2", rg.classify(nullRisk, intent("weird2", 0.9, "r", map())) == RiskLevel.R2);
    }

    static void testRedirectNotReadonly() {
        DefaultRiskGate rg = new DefaultRiskGate(new SimpleShellAstAnalyzer());
        SkillDescriptor d = new SkillRegistry().get("readLog"); // R0
        IntentObject it = intent("readLog", 0.9, "r", map("path", "a", "command", "cat a > b"));
        check("带重定向不按只读(升级 R1)", rg.classify(d, it) == RiskLevel.R1);
    }

    static void testAstFallbackR2() {
        DefaultRiskGate rg = new DefaultRiskGate(new SimpleShellAstAnalyzer());
        SkillDescriptor d = new SkillRegistry().get("readLog");
        IntentObject it = intent("readLog", 0.9, "r", map("path", "a", "command", "echo 'unclosed"));
        check("AST 解析失败默认 R2", rg.classify(d, it) == RiskLevel.R2);
        check("AST 失败计数+1", rg.getAstFallbackCount() >= 1);
    }

    static void testConfidenceLowNeedMoreInfo() {
        AuditStore audit = new HashChainAuditStore();
        Executor ex = buildExecutor(permsAll(), audit, false);
        SkillResult r = ex.execute(intent("restartService", 0.3, "低置信", map("service", "x")),
                ctx(AgentMode.DEV_EXECUTION, false, "agent"));
        check("置信度<0.5 -> NEED_MORE_INFO", !r.success && "NEED_MORE_INFO".equals(r.code));
    }

    static void testSuggestionTextRejected() {
        AuditStore audit = new HashChainAuditStore();
        Executor ex = buildExecutor(permsAll(), audit, false);
        // 工具在 DEV 可见，但 reason 含建议文本 → INTENT_FORMAT_INVALID
        SkillResult r = ex.execute(intent("writeFile", 0.9, "建议你执行 X", map("path", "A", "content", "x")),
                ctx(AgentMode.DEV_EXECUTION, false, "agent"));
        check("建议文本被拒 INTENT_FORMAT_INVALID", !r.success && "INTENT_FORMAT_INVALID".equals(r.code));
    }

    static void testModeWhitelistPLANNING() {
        AuditStore audit = new HashChainAuditStore();
        Executor ex = buildExecutor(permsAll(), audit, false);
        // PLANNING 看不到 writeFile、restartService
        SkillResult r1 = ex.execute(intent("writeFile", 0.9, "r", map("path", "A", "content", "x")),
                ctx(AgentMode.ARCHITECT_PLANNING, false, "agent"));
        check("PLANNING 调 writeFile -> MODE_TOOL_NOT_ALLOWED", "MODE_TOOL_NOT_ALLOWED".equals(r1.code));
        SkillResult r2 = ex.execute(intent("splitTask", 0.9, "r", map("requirement", "x")),
                ctx(AgentMode.ARCHITECT_PLANNING, false, "agent"));
        check("PLANNING 调 splitTask 放行", r2.success);
    }

    static void testModeWhitelistDEV() {
        AuditStore audit = new HashChainAuditStore();
        Executor ex = buildExecutor(permsAll(), audit, false);
        // DEV 看不到 splitTask（重规划工具）
        SkillResult r = ex.execute(intent("splitTask", 0.9, "r", map("requirement", "x")),
                ctx(AgentMode.DEV_EXECUTION, false, "agent"));
        check("DEV 调 splitTask(重规划) -> MODE_TOOL_NOT_ALLOWED", "MODE_TOOL_NOT_ALLOWED".equals(r.code));
        SkillResult r2 = ex.execute(intent("writeFile", 0.9, "r", map("path", "A", "content", "x")),
                ctx(AgentMode.DEV_EXECUTION, false, "agent"));
        check("DEV 调 writeFile 放行", r2.success);
    }

    static void testIllegalModeTransition() {
        ModeStateMachine ms = new ModeStateMachine();
        boolean threw = false;
        try {
            ms.transition(AgentMode.AUDIT_REVIEWING, ModeEvent.PLAN_APPROVED); // 跳过 REPLAN
        } catch (ModeTransitionInvalidException e) {
            threw = true;
            check("非法模式转换抛 MODE_TRANSITION_INVALID", e.getMessage().contains("MODE_TRANSITION_INVALID"));
        }
        check("非法模式转换被拒", threw);
        // 合法：PLANNING --PLAN_APPROVED--> DEV_EXECUTION
        check("合法 PLANNING->DEV", ms.transition(AgentMode.ARCHITECT_PLANNING, ModeEvent.PLAN_APPROVED) == AgentMode.DEV_EXECUTION);
    }

    static void testR3DeniedEvenForce() {
        AuditStore audit = new HashChainAuditStore();
        Executor ex = buildExecutor(permsAll(), audit, false);
        // 带 --force 的删除意图，仍应 R3_DENIED
        SkillResult r = ex.execute(intent("deleteData", 0.99, "清理 --force", map("table", "orders")),
                ctx(AgentMode.DEV_EXECUTION, false, "agent"));
        check("R3 即使高置信/--force 仍 R3_DENIED", "R3_DENIED".equals(r.code));
    }

    static void testR2PendingApproval() {
        AuditStore audit = new HashChainAuditStore();
        Executor ex = buildExecutor(permsAll(), audit, false);
        SkillResult r = ex.execute(intent("deployService", 0.9, "上线", map("target", "prod", "version", "2")),
                ctx(AgentMode.DEV_EXECUTION, false, "agent"));
        check("R2 部署 -> PENDING_APPROVAL(挂人工)", "PENDING_APPROVAL".equals(r.code) && r.approvalTicketId != null);
    }

    static void testPermissionDeniedStillAudited() {
        AuditStore audit = new HashChainAuditStore();
        PermissionEngine pe = new PermissionEngine(); // agent 无任何权限
        Executor ex = buildExecutor(pe, audit, false);
        SkillResult r = ex.execute(intent("restartService", 0.9, "r", map("service", "x")),
                ctx(AgentMode.DEV_EXECUTION, false, "no-perm"));
        check("权限不足被拒 PERMISSION_DENIED", !r.success && "PERMISSION_DENIED".equals(r.code));
        check("权限拒绝仍写审计(记录数>=1)", audit.all().size() >= 1);
        check("审计哈希链仍完整", audit.verifyChain());
    }

    static void testPipelineStateSaveLoad() {
        PipelineStateStore store = new InMemoryPipelineStateStore();
        PipelineState ps = new PipelineState("T1", AgentMode.DEV_EXECUTION,
                new String[]{"a", "b"}, "compile:ok", "plan-1", "fast-llm");
        store.save("T1", ps);
        PipelineState restored = store.load("T1");
        check("PipelineState 恢复 mode 一致", restored != null && restored.mode == AgentMode.DEV_EXECUTION);
        check("PipelineState 恢复 subTasks 一致", restored.subTasks.length == 2 && "b".equals(restored.subTasks[1]));
        check("PipelineState 恢复 gate 一致", "compile:ok".equals(restored.gateResults));
    }

    static void testGateSignatureMismatch() {
        // 脚本签名被篡改 → 直接拒绝
        GateScript tampered = new GateScript() {
            public String name() { return "compile"; }
            public String command() { return "mvn compile"; }
            public String signedHash() { return "TAMPERED"; } // 与期望不符
            public Duration timeout() { return Duration.ofSeconds(10); }
            public int maxRetry() { return 3; }
        };
        GateScriptRunner runner = new GateScriptRunner(s -> true, GATE_KEY);
        GateResult res = runner.run(tampered);
        check("门禁脚本签名篡改 -> 拒绝", !res.passed && res.output.contains("SIGNATURE_MISMATCH"));
    }

    static void testGateRetryExhausted() {
        GateScript script = new SimpleGateScript("compile", "mvn compile", Duration.ofSeconds(5), 2, GATE_KEY);
        GateScriptRunner runner = new GateScriptRunner(s -> false, GATE_KEY); // 永远失败
        GateResult res = runner.run(script);
        check("门禁失败重试耗尽 -> 失败", !res.passed && res.attempts == 3); // maxRetry=2 => 1+2=3 次
    }

    static void testGateRetrySucceed() {
        GateScript script = new SimpleGateScript("compile", "mvn compile", Duration.ofSeconds(5), 2, GATE_KEY);
        GateScriptRunner runner = new GateScriptRunner(s -> true, GATE_KEY);
        GateResult res = runner.run(script);
        check("门禁通过 -> passed", res.passed && res.attempts == 1);
    }

    static void testApprovalPolicyR3ReferTerminal() {
        ApprovalPolicy p = new HumanApprovalPolicy();
        ApprovalTicket t = new ApprovalTicket("T", "T", "deleteData", "R3", "eff", "rb");
        check("R3 审批策略 -> REFER_MANUAL_TERMINAL", p.decide(t, ctx(AgentMode.DEV_EXECUTION, false, "a")) == ApprovalDecision.REFER_MANUAL_TERMINAL);
        ApprovalTicket t2 = new ApprovalTicket("T", "T", "deployService", "R2", "eff", "rb");
        check("R2 审批策略 -> ESCALATE_TO_HUMAN", p.decide(t2, ctx(AgentMode.DEV_EXECUTION, false, "a")) == ApprovalDecision.ESCALATE_TO_HUMAN);
    }

    static void testGuardianCannotApprove() {
        GuardianPrecheckImpl g = new GuardianPrecheckImpl();
        GuardianOpinion o = g.precheck(new ApprovalTicket("T", "T", "deployService", "R2", "eff", "rb"));
        check("Guardian 对 R2 标记 risky(仅预审不代批)", o.risky);
        // 关键：Guardian 不实现 ApprovalPolicy，无法返回 APPROVE 给 R2
        check("Guardian 仅输出建议文本", o.advice != null && !o.advice.isEmpty());
    }
}
