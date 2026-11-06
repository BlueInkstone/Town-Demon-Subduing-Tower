package com.towndemontower;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.approval.ApprovalPolicy;
import com.towndemontower.core.approval.GuardianPrecheckImpl;
import com.towndemontower.core.approval.HumanApprovalPolicy;
import com.towndemontower.core.contract.*;
import com.towndemontower.core.context.InMemoryPipelineStateStore;
import com.towndemontower.core.context.PipelineState;
import com.towndemontower.core.context.PipelineStateStore;
import com.towndemontower.core.executor.*;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.mode.DefaultModeToolPolicy;
import com.towndemontower.core.mode.ModeStateMachine;
import com.towndemontower.core.policy.DefaultRiskGate;
import com.towndemontower.core.policy.SimpleShellAstAnalyzer;

import java.util.HashMap;
import java.util.Map;

/**
 * [core] Dev 阶段演示：纯 Java 跑通
 *  意图校验 → 模式白名单 → 风险分级 → 拒绝/放行 最小链路，
 *  并演示模式状态机、PipelineState 落盘恢复、哈希链审计。
 * 运行：javac 编译后 java com.towndemontower.DemoRunner
 */
public class DemoRunner {
    public static void main(String[] args) {
        SkillRegistry registry = new SkillRegistry();
        AuditStore audit = new HashChainAuditStore();
        Executor executor = ExecutorFactory.buildDefault(
                registry,
                new DefaultModeToolPolicy(),
                new DefaultRiskGate(new SimpleShellAstAnalyzer()),
                perms(),
                new SnapshotService(),
                (d, intent) -> "executed:" + d.name + " args=" + intent.args,
                audit,
                new HumanApprovalPolicy()
        );

        System.out.println("=== Town Demon-Subduing Tower core 最小链路演示 ===");

        // 1) R1 重启服务（DEV 模式，权限齐）-> 放行
        SkillResult r1 = executor.execute(
                intent("restartService", 0.9, "健康检查失败", map("service", "order-api")),
                ctx(AgentMode.DEV_EXECUTION, false, "agent-ops"));
        System.out.println("[R1 restart] -> " + r1.code + " / " + r1.output);

        // 2) R3 删数据（DEV 模式）-> R3_DENIED
        SkillResult r2 = executor.execute(
                intent("deleteData", 0.99, "清理脏表", map("table", "orders")),
                ctx(AgentMode.DEV_EXECUTION, false, "agent-ops"));
        System.out.println("[R3 delete] -> " + r2.code);

        // 3) PLANNING 模式调 writeFile -> MODE_TOOL_NOT_ALLOWED
        SkillResult r3 = executor.execute(
                intent("writeFile", 0.8, "写实现", map("path", "A.java", "content", "x")),
                ctx(AgentMode.ARCHITECT_PLANNING, false, "agent-dev"));
        System.out.println("[PLANNING writeFile] -> " + r3.code);

        // 4) R2 部署 -> 走人工审批，返回 pendingApproval
        SkillResult r4 = executor.execute(
                intent("deployService", 0.85, "上线 v2", map("target", "prod", "version", "2.0")),
                ctx(AgentMode.DEV_EXECUTION, false, "agent-ops"));
        System.out.println("[R2 deploy] -> " + r4.code + " ticket=" + r4.approvalTicketId);

        // 5) 模式状态机：PLANNING --PLAN_APPROVED--> DEV_EXECUTION
        ModeStateMachine ms = new ModeStateMachine();
        AgentMode m = ms.transition(AgentMode.ARCHITECT_PLANNING, com.towndemontower.core.mode.ModeEvent.PLAN_APPROVED);
        System.out.println("[mode transition] PLANNING --PLAN_APPROVED--> " + m);
        try {
            ms.transition(AgentMode.AUDIT_REVIEWING, com.towndemontower.core.mode.ModeEvent.PLAN_APPROVED);
            System.out.println("[mode transition] 应被拒但未抛异常！");
        } catch (Exception ex) {
            System.out.println("[mode transition] 非法转换被拒 -> " + ex.getMessage());
        }

        // 6) PipelineState 落盘与恢复
        PipelineStateStore store = new InMemoryPipelineStateStore();
        PipelineState ps = new PipelineState("T1", AgentMode.DEV_EXECUTION,
                new String[]{"impl", "test"}, "compile:ok", "plan-1", "fast-llm");
        store.save("T1", ps);
        PipelineState restored = store.load("T1");
        System.out.println("[pipeline] 恢复 mode=" + restored.mode + " subTasks="
                + String.join(",", restored.subTasks) + " gate=" + restored.gateResults);

        // 7) 审计哈希链校验
        System.out.println("[audit] 记录数=" + audit.all().size() + " 链完整=" + audit.verifyChain());

        // 8) Guardian 预审（仅建议，不代批）
        GuardianPrecheckImpl guardian = new GuardianPrecheckImpl();
        System.out.println("[guardian] " + guardian.precheck(
                new ApprovalTicket("T1", "T1", "deployService", "R2", "上线", "回滚")).summary);

        System.out.println("=== 演示结束 ===");
    }

    private static ExecutionContext ctx(AgentMode mode, boolean tainted, String agent) {
        return new ExecutionContext("S1", "T1", "tenant1", agent, "owner1", tainted, mode);
    }

    private static IntentObject intent(String tool, double conf, String reason, Map<String, Object> args) {
        return new IntentObject(tool, args, conf, reason, "预期效果", "回滚提示");
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static PermissionEngine perms() {
        PermissionEngine pe = new PermissionEngine();
        // V1.9：引擎只读，授权必须经端口（这里是本地演示，故未挂审计）
        PermissionProvisioner port = PermissionProvisioner.of(pe);
        port.grant("agent-ops", "ops:restart", "ops:deploy", "data:delete", "fs:write",
                "build:run", "log:read", "plan:write", "review:read");
        port.grant("agent-dev", "fs:write", "build:run", "log:read", "plan:write", "review:read");
        return pe;
    }
}
