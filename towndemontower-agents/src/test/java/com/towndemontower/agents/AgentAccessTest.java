package com.towndemontower.agents;

import com.towndemontower.agents.domain.AccessProvisioner;
import com.towndemontower.agents.domain.Agent;
import com.towndemontower.agents.domain.AgentRole;
import com.towndemontower.agents.domain.AgentStatus;
import com.towndemontower.agents.domain.InMemoryAgentRepository;
import com.towndemontower.agents.domain.SkillCatalog;
import com.towndemontower.agents.governance.AgentGovernanceException;
import com.towndemontower.agents.governance.AgentGovernanceFacade;
import com.towndemontower.agents.governance.AgentsModeToolPolicy;
import com.towndemontower.agents.governance.CorePermissionProvisioner;
import com.towndemontower.agents.governance.RegistrySkillCatalog;
import com.towndemontower.agents.seed.DefaultAgents;
import com.towndemontower.agents.service.AgentService;
import com.towndemontower.agents.service.AgentValidationException;
import com.towndemontower.core.approval.HumanApprovalPolicy;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.executor.SnapshotService;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.policy.DefaultRiskGate;
import com.towndemontower.core.policy.SimpleShellAstAnalyzer;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [agents/test] 治理接入与权限闭环验证（纯 Java，零 Spring）。
 *
 * <p>本模块最关键的一组用例：Agent 档案不能只是一张自娱自乐的表，
 * 它必须真的改变治理链的判定结果。因此这里逐条验证：
 * <ol>
 *   <li>注册 → RBAC 中真的拿到权限；</li>
 *   <li>停用 / 注销 → 权限被整员回收（此后该 agentId 调不动需要权限的工具）；</li>
 *   <li>恢复 → 按档案重新授予；</li>
 *   <li>改档 → 覆盖式授予，旧角色权限不残留；</li>
 *   <li>无权限身份 → 被治理链拒绝（403 语义）；</li>
 *   <li>业务校验失败 → 穿透治理链保持 400 语义，不被降级成"治理拒绝"；</li>
 *   <li>所有写操作进入审计哈希链且链可校验；</li>
 *   <li>关闭治理链时，权限同步依然发生。</li>
 * </ol>
 */
public class AgentAccessTest {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }

    static void eq(String name, Object actual, Object expected) {
        boolean ok = (expected == null) ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name + " = " + actual);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + " expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) {
        accessLifecycle();
        governanceGuard();
        businessErrorPassthrough();
        auditChain();
        ungovernedStillSyncs();
        defaultAgentsSeed();

        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------------- 装配 ---------------- */

    static final String API_IDENTITY = "agents-api-agent";

    static SkillRegistry registry;
    static PermissionEngine engine;
    static AuditStore audit;
    static AgentService service;
    static AgentGovernanceFacade facade;

    static void build() {
        registry = new SkillRegistry();
        engine = new PermissionEngine();
        audit = new HashChainAuditStore();
        SkillCatalog catalog = new RegistrySkillCatalog(registry);
        service = new AgentService(new InMemoryAgentRepository(), catalog);
        PermissionProvisioner port = PermissionProvisioner.of(engine);
        AccessProvisioner provisioner = new CorePermissionProvisioner(port, engine);
        facade = new AgentGovernanceFacade(
                registry, new AgentsModeToolPolicy(),
                new DefaultRiskGate(new SimpleShellAstAnalyzer()),
                engine, new SnapshotService(), audit, new HumanApprovalPolicy(),
                service, provisioner, true);
        facade.grantPermissions(port, API_IDENTITY);
    }

    static ExecutionContext ctx() {
        return ctx(API_IDENTITY);
    }

    static ExecutionContext ctx(String agentId) {
        return new ExecutionContext("test-session", "test-task", "tenant-default",
                agentId, "test-owner", false, AgentMode.DEV_EXECUTION);
    }

    /* ---------------- ① 权限生命周期 ---------------- */

    static void accessLifecycle() {
        build();

        Agent dev = facade.register(ctx(), "build-runner-01", AgentRole.DEVELOPER,
                null, null, null, LocalDateTime.of(2026, 11, 2, 9, 0), "编译与写入");
        eq("注册-ID 已分配", dev.id() != null && dev.id().startsWith("AGT-"), true);
        eq("注册-档案权限数", dev.permissionCount(), 4);
        eq("注册-RBAC 生效权限数", facade.effectivePermissions(dev.id()).size(), 4);
        check("注册-实然含 build:run", facade.effectivePermissions(dev.id()).contains("build:run"));

        /* 停用：档案保留，RBAC 清空 */
        facade.changeStatus(ctx(), dev.id(), AgentStatus.SUSPENDED);
        eq("停用-实然权限已清空", facade.effectivePermissions(dev.id()).size(), 0);
        eq("停用-档案权限仍保留", service.findById(dev.id()).orElseThrow().permissionCount(), 4);
        eq("停用-档案状态", service.findById(dev.id()).orElseThrow().status(), AgentStatus.SUSPENDED);

        /* 恢复：按档案重新授予 */
        facade.changeStatus(ctx(), dev.id(), AgentStatus.ACTIVE);
        eq("恢复-实然权限已恢复", facade.effectivePermissions(dev.id()).size(), 4);

        /* 改档：覆盖式授予，旧权限不残留 */
        Agent qa = facade.update(ctx(), dev.id(), null, AgentRole.QA,
                null, List.of("review:read"), null, null, null);
        eq("改档-角色已变", qa.role(), AgentRole.QA);
        eq("改档-实然权限只剩新清单", facade.effectivePermissions(dev.id()).size(), 1);
        check("改档-旧权限 build:run 已撤销",
                !facade.effectivePermissions(dev.id()).contains("build:run"));
        check("改档-新权限 review:read 已生效",
                facade.effectivePermissions(dev.id()).contains("review:read"));

        /* 注销 → 删除 */
        facade.changeStatus(ctx(), dev.id(), AgentStatus.RETIRED);
        eq("注销-实然权限已清空", facade.effectivePermissions(dev.id()).size(), 0);
        facade.delete(ctx(), dev.id());
        eq("删除-档案已移除", service.findById(dev.id()).isEmpty(), true);

        /* 暂停：保留权限但不允许执行 */
        Agent paused = facade.register(ctx(), "paused-agent", AgentRole.DEVELOPER,
                null, null, null, null, null);
        facade.changeStatus(ctx(), paused.id(), AgentStatus.PAUSED);
        eq("暂停-实然权限保留", facade.effectivePermissions(paused.id()).size(), 4);
        boolean blocked = false;
        try {
            facade.assertCanAct(paused.id());
        } catch (RuntimeException e) {
            blocked = true;
        }
        check("暂停-执行前守卫拦截", blocked);

        /* 汇总与档案一致 */
        eq("汇总-与仓储一致", facade.summary(ctx()).total(), service.count());
    }

    /* ---------------- ② 治理链守卫 ---------------- */

    static void governanceGuard() {
        build();

        boolean denied = false;
        String code = null;
        try {
            facade.register(ctx("nobody-without-perm"), "unauthorized-agent", AgentRole.DEVELOPER,
                    null, null, null, null, null);
        } catch (AgentGovernanceException e) {
            denied = true;
            code = e.code();
        }
        check("治理-无 agents:write 身份被拒绝", denied);
        eq("治理-拒绝码", code, "PERMISSION_DENIED");
        eq("治理-越权调用未落库", service.count(), 0);

        /* 只读身份：能查不能写 */
        PermissionProvisioner.of(engine).grant("reader", "agents:read");
        eq("治理-只读身份可查询", facade.list(ctx("reader"), null, null, null).size(), 0);
        boolean writeDenied = false;
        try {
            facade.register(ctx("reader"), "readonly-agent", AgentRole.QA, null, null, null, null, null);
        } catch (AgentGovernanceException e) {
            writeDenied = true;
        }
        check("治理-只读身份不能注册", writeDenied);

        /* 工具元数据已注册进共享注册表 */
        check("注册表-注册表工具已注册", registry.contains("agents.registerAgent"));
        check("注册表-业务工具仍在", registry.contains("restartService"));
        check("注册表-技能目录可枚举",
                new RegistrySkillCatalog(registry).all().contains("deployService"));
    }

    /* ---------------- ③ 业务异常穿透 ---------------- */

    static void businessErrorPassthrough() {
        build();

        /* 技能拼错：应该是 400 语义的业务异常，而不是 403 治理拒绝 */
        Throwable caught = null;
        try {
            facade.register(ctx(), "bad-skill-agent", AgentRole.DEVELOPER,
                    List.of("deployServices"), null, null, null, null);
        } catch (RuntimeException e) {
            caught = e;
        }
        check("穿透-未知技能抛业务校验异常", caught instanceof AgentValidationException);
        eq("穿透-错误字段", caught instanceof AgentValidationException v ? v.field() : null, "skills");

        /* 同名：同样是业务异常 */
        facade.register(ctx(), "dup-agent", AgentRole.PM, null, null, null, null, null);
        Throwable dup = null;
        try {
            facade.register(ctx(), "dup-agent", AgentRole.QA, null, null, null, null, null);
        } catch (RuntimeException e) {
            dup = e;
        }
        check("穿透-同名重复注册抛业务校验异常", dup instanceof AgentValidationException);

        /* 业务异常也必须留痕（调用确实发生过） */
        check("穿透-失败调用仍记入审计", audit.all().size() >= 3);
    }

    /* ---------------- ④ 审计链 ---------------- */

    static void auditChain() {
        build();
        Agent a = facade.register(ctx(), "audit-a", AgentRole.DEVELOPER, null, null, null, null, null);
        facade.update(ctx(), a.id(), "audit-b", null, null, null, null, null, null);
        facade.changeStatus(ctx(), a.id(), AgentStatus.SUSPENDED);

        int records = audit.all().size();
        check("审计-写操作已落链(" + records + " 条)", records >= 3);
        check("审计-哈希链校验通过", audit.verifyChain());
        boolean sawTool = audit.all().stream()
                .anyMatch(r -> r.payload != null && r.payload.contains("agents."));
        check("审计-包含注册表工具调用", sawTool);
    }

    /* ---------------- ⑤ 关闭治理链仍同步权限 ---------------- */

    static void ungovernedStillSyncs() {
        SkillRegistry r2 = new SkillRegistry();
        PermissionEngine e2 = new PermissionEngine();
        AgentService s2 = new AgentService(
                new InMemoryAgentRepository(), new RegistrySkillCatalog(r2));
        AgentGovernanceFacade f2 = new AgentGovernanceFacade(
                r2, new AgentsModeToolPolicy(),
                new DefaultRiskGate(new SimpleShellAstAnalyzer()),
                e2, new SnapshotService(), new HashChainAuditStore(),
                new HumanApprovalPolicy(), s2, new CorePermissionProvisioner(PermissionProvisioner.of(e2), e2), false);

        Agent a = f2.register(ctx("no-perm-identity"), "lite-agent", AgentRole.OPS,
                null, null, null, null, null);
        eq("轻量模式-免治理仍授予权限", e2.permissionsOf(a.id()).size(), 4);

        f2.changeStatus(ctx("no-perm-identity"), a.id(), AgentStatus.SUSPENDED);
        eq("轻量模式-停用仍回收权限", e2.permissionsOf(a.id()).size(), 0);
    }

    /* ---------------- ⑥ 演示种子数据 ---------------- */

    /**
     * 演示数据要能一眼看出权限闭环：断言数量口径，并逐个核对
     * SUSPENDED / RETIRED / PAUSED 三类的实然权限。
     */
    static void defaultAgentsSeed() {
        build();
        DefaultAgents.seed(facade, ctx());

        var summary = facade.summary(ctx());
        eq("种子-总数", summary.total(), DefaultAgents.expectedTotal());
        eq("种子-ACTIVE", summary.activeCount(), DefaultAgents.expectedActive());
        eq("种子-PAUSED", summary.pausedCount(), DefaultAgents.expectedPaused());
        eq("种子-SUSPENDED", summary.suspendedCount(), DefaultAgents.expectedSuspended());
        eq("种子-RETIRED", summary.retiredCount(), DefaultAgents.expectedRetired());
        eq("种子-可执行", summary.executableCount(), DefaultAgents.expectedExecutable());
        eq("种子-角色分布覆盖五个角色", summary.byRole().size(), 5);

        Agent suspended = service.list(null, null, "qa-gate").get(0);
        eq("种子-停用档案权限保留", suspended.permissionCount(), 3);
        eq("种子-停用实然权限已回收", facade.effectivePermissions(suspended.id()).size(), 0);

        Agent retired = service.list(null, null, "ops-ci").get(0);
        eq("种子-已注销档案权限保留", retired.permissionCount(), 4);
        eq("种子-已注销实然权限已回收", facade.effectivePermissions(retired.id()).size(), 0);

        Agent paused = service.list(null, null, "dev-ui").get(0);
        eq("种子-暂停实然权限仍保留", facade.effectivePermissions(paused.id()).size(), 4);
        check("种子-暂停不可执行", !paused.canAct());

        Agent active = service.list(null, null, "dev-core").get(0);
        eq("种子-可用实然权限已授予", facade.effectivePermissions(active.id()).size(), 4);

        check("种子-写操作全部留痕", audit.all().size() >= DefaultAgents.expectedTotal());
        check("种子-审计链可校验", audit.verifyChain());
    }
}
