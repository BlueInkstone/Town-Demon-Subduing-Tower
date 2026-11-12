package com.towndemontower.core;


import java.nio.charset.StandardCharsets;
import com.towndemontower.core.contract.*;
import com.towndemontower.core.context.FilePipelineStateStore;
import com.towndemontower.core.context.PipelineState;
import com.towndemontower.core.credential.CredentialBroker;
import com.towndemontower.core.decorator.ProcessSandboxExecutor;
import com.towndemontower.core.decorator.ProcessSandboxStrategy;
import com.towndemontower.core.decorator.SandboxedToolExecutor;
import com.towndemontower.core.executor.CancellationToken;
import com.towndemontower.core.executor.PermissionDeniedException;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;
import com.towndemontower.core.executor.SkillInvoker;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.gate.GateExecutor;
import com.towndemontower.core.gate.GateResult;
import com.towndemontower.core.gate.GateScript;
import com.towndemontower.core.gate.GateScriptRunner;
import com.towndemontower.core.gate.SimpleGateScript;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.contract.RiskLevel;
import com.towndemontower.core.policy.AstRisk;
import com.towndemontower.core.policy.DefaultRiskGate;
import com.towndemontower.core.policy.SimpleShellAstAnalyzer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [V1.2] 功能验证：凭据代理、文件状态持久化、AST 增强、进程沙箱、取消令牌、门禁重试。
 * 纯 Java 单测，零 token，直接用 JDK21 编译运行。
 */
public class V12FeatureTest {

    /** 门禁密钥显式注入（生产只认环境变量 TOWNDEMONTOWER_GATE_KEY）。 */
    static final byte[] GATE_KEY =
            "v12-test-gate-key-0123456789abcdefg".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static int pass = 0, fail = 0;
    static boolean windows = System.getProperty("os.name").toLowerCase().contains("win");

    static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    static IntentObject intent(String tool, String command) {
        java.util.Map<String, Object> args = new java.util.HashMap<>();
        if (command != null) args.put("command", command);
        return new IntentObject(tool, args, 0.9, null, null, null);
    }

    public static void main(String[] args) throws Exception {
        credentialBroker();
        fileStateStore();
        astEnhancement();
        riskGateSubshell();
        processSandbox();
        cancellationToken();
        gateRetry();
        permissionRevoke();
        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // [V1.2] CredentialBroker：脱敏/注入/轮换/哈希审计
    static void credentialBroker() {
        CredentialBroker b = new CredentialBroker();
        b.register("DB_PWD", "s3cr3t");
        check("broker.redact", b.redact("pwd=s3cr3t").equals("pwd=${DB_PWD}"));
        check("broker.inject", b.inject("conn ${DB_PWD}").equals("conn s3cr3t"));
        b.rotate("DB_PWD", "n3w");
        check("broker.rotate", b.inject("${DB_PWD}").equals("n3w"));
        Map<String, String> h = b.secretHashes();
        check("broker.hashOnly", h.containsKey("DB_PWD") && !h.get("DB_PWD").equals("n3w") && h.get("DB_PWD").length() == 12);
    }

    // [V1.2] FilePipelineStateStore：落盘与恢复一致
    static void fileStateStore() throws Exception {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "tdt-state-" + System.nanoTime());
        FilePipelineStateStore store = new FilePipelineStateStore(dir);
        PipelineState s = new PipelineState("t1", AgentMode.DEV_EXECUTION,
                new String[]{"a", "b"}, "gate-ok", "plan-1", "model-1");
        store.save("t1", s);
        check("state.exists", store.exists("t1"));
        PipelineState r = store.load("t1");
        check("state.mode", r != null && r.mode == AgentMode.DEV_EXECUTION);
        check("state.subTasks", r.subTasks.length == 2 && "a".equals(r.subTasks[0]) && "b".equals(r.subTasks[1]));
        check("state.gate", "gate-ok".equals(r.gateResults) && "plan-1".equals(r.planArtifactRef));
        check("state.missing-null", store.load("nope") == null);
    }

    // [V1.2] AST 增强：管道、子 shell、重定向
    static void astEnhancement() {
        SimpleShellAstAnalyzer ast = new SimpleShellAstAnalyzer();
        check("ast.pipe-dangerous", ast.analyze("cat x | rm -rf /").level == RiskLevel.R3);
        check("ast.simple", ast.analyze("ls -l").level == RiskLevel.R0);
        check("ast.redirect-not-readonly", ast.analyze("cat a > b").level == RiskLevel.R1);
        AstRisk sub = ast.analyze("echo $(rm -rf /)");
        check("ast.subshell-flag", sub.subshell);
        check("ast.subshell-minR1", sub.level.order() >= RiskLevel.R1.order());
    }

    // [V1.2] DefaultRiskGate：子 shell 至少升到 R1
    static void riskGateSubshell() {
        DefaultRiskGate gate = new DefaultRiskGate(new SimpleShellAstAnalyzer());
        SkillDescriptor d = new SkillDescriptor("shell", "R0",
                new String[0], new String[0], new String[0], false, "none");
        IntentObject intent = intent("shell", "echo $(rm -rf /)");
        check("gate.subshell-bump", gate.classify(d, intent).order() >= RiskLevel.R1.order());
    }

    // [V1.2] 进程级沙箱：真实执行 + 取消令牌急停
    static void processSandbox() throws Exception {
        CancellationToken tok = new CancellationToken("s1");
        ProcessSandboxStrategy strategy = new ProcessSandboxStrategy(10000);
        String out = strategy.execute("echo hello", tok);
        check("sandbox.echo", out.contains("hello"));

        // 取消令牌：启动长命令后立即取消，应抛 CancellationException
        CancellationToken tok2 = new CancellationToken("s2");
        String longCmd = windows ? "ping -n 6 127.0.0.1" : "sleep 5";
        boolean threw = false;
        try {
            tok2.cancel();
            strategy.execute(longCmd, tok2);
        } catch (java.util.concurrent.CancellationException e) {
            threw = true;
        }
        check("sandbox.cancel", threw);

        // ProcessSandboxExecutor 接入装饰器链形态
        SkillRegistry reg = new SkillRegistry();
        reg.register(new SkillDescriptor("run", "R1", new String[0], new String[0], new String[0], false, "proc"));
        ProcessSandboxExecutor exec = new ProcessSandboxExecutor(reg, strategy, null);
        IntentObject io = intent("run", "echo box");
        SkillResult r = exec.execute(io, new ExecutionContext("s", "t", "tenant", "agent", "owner", false, AgentMode.DEV_EXECUTION));
        check("sandbox.executor", r.success && r.output != null && r.output.toString().contains("box"));
    }

    // [V1.2] 取消令牌贯穿 SandboxedToolExecutor
    static void cancellationToken() {
        SkillRegistry reg = new SkillRegistry();
        reg.register(new SkillDescriptor("tool1", "R1", new String[0], new String[0], new String[0], false, "none"));
        SkillInvoker invoker = (d, i) -> "should-not-run";
        SandboxedToolExecutor exec = new SandboxedToolExecutor(invoker, reg);
        CancellationToken tok = new CancellationToken("c1");
        tok.cancel();
        IntentObject io = intent("tool1", null);
        ExecutionContext ctx = new ExecutionContext("s", "t", "tenant", "agent", "owner", false, AgentMode.DEV_EXECUTION, tok);
        SkillResult r = exec.execute(io, ctx);
        check("token.cancelled", !r.success && ErrorCodes.CANCELLED.equals(r.code));
    }

    // [V1.2] 门禁运行时：签名校验 + 重试 + 超限 escalate
    static void gateRetry() {
        // 重试：前两次失败，第三次通过
        AtomicInteger calls = new AtomicInteger(0);
        GateExecutor flaky = s -> {
            int n = calls.incrementAndGet();
            return n >= 3; // 第三次才成功
        };
        GateScriptRunner runner = new GateScriptRunner(flaky, GATE_KEY);
        GateScript gs = new SimpleGateScript("lint", "lint .", Duration.ofSeconds(5), 2, GATE_KEY);
        GateResult ok = runner.run(gs);
        check("gate.retry-pass", ok.passed && ok.attempts == 3);

        // 始终失败 → 超限 escalate（独立执行器，不共享前例的计数器）
        GateExecutor alwaysFail = s -> false;
        GateScriptRunner failRunner = new GateScriptRunner(alwaysFail, GATE_KEY);
        GateResult bad = failRunner.run(new SimpleGateScript("lint2", "lint .", Duration.ofSeconds(5), 2, GATE_KEY));
        check("gate.escalate", !bad.passed && bad.attempts == 3);

        // 签名校验：signedHash 不匹配直接拒绝
        GateScript tampered = new GateScript() {
            public String name() { return "x"; }
            public String command() { return "ls"; }
            public String signedHash() { return "TAMPERED"; }
            public Duration timeout() { return Duration.ofSeconds(1); }
            public int maxRetry() { return 0; }
        };
        GateResult sig = runner.run(tampered);
        check("gate.signature", !sig.passed && sig.attempts == 0);
    }

    /** V1.4：RBAC 的授予与回收必须成对——离职/停用要能真正收回权限。 */
    static void permissionRevoke() {
        PermissionEngine engine = new PermissionEngine();
        PermissionProvisioner port = PermissionProvisioner.of(engine);
        port.grant("EMP-0001", "delivery:read", "delivery:write");
        check("rbac.grant", engine.isGranted("EMP-0001", "delivery:read"));

        port.revoke("EMP-0001", "delivery:write");
        check("rbac.revoke-precise", !engine.isGranted("EMP-0001", "delivery:write"));
        check("rbac.revoke-keeps-others", engine.isGranted("EMP-0001", "delivery:read"));

        port.grant("EMP-0001", "delivery:write");
        check("rbac.revoke-all-count", port.revokeAll("EMP-0001") == 2);
        check("rbac.revoke-all-empty", engine.permissionsOf("EMP-0001").isEmpty());

        boolean denied = false;
        try {
            engine.assertAllowed("EMP-0001", new String[]{"delivery:read"});
        } catch (PermissionDeniedException e) {
            denied = true;
        }
        check("rbac.denied-after-revoke", denied);
        check("rbac.revoke-idempotent", port.revokeAll("EMP-0001") == 0);
        check("rbac.unknown-agent-empty", engine.permissionsOf("EMP-9999").isEmpty());
    }
}
