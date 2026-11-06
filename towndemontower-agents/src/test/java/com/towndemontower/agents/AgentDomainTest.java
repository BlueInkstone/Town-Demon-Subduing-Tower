package com.towndemontower.agents;

import com.towndemontower.agents.domain.Agent;
import com.towndemontower.agents.domain.AgentRepository;
import com.towndemontower.agents.domain.AgentRole;
import com.towndemontower.agents.domain.AgentStatus;
import com.towndemontower.agents.domain.AgentSummary;
import com.towndemontower.agents.domain.AgentSummaryCalculator;
import com.towndemontower.agents.domain.InMemoryAgentRepository;
import com.towndemontower.agents.domain.ModelTier;
import com.towndemontower.agents.domain.RoleTemplate;
import com.towndemontower.agents.domain.SkillCatalog;
import com.towndemontower.agents.service.AgentService;
import com.towndemontower.agents.service.AgentValidationException;
import com.towndemontower.core.executor.SkillRegistry;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * [agents/test] 领域与服务层验证（纯 Java，零 Spring，零 token）。
 *
 * <p>覆盖：值对象不可变语义、状态的两个后果（能否执行 / 是否保留权限）、
 * 角色模板、汇总口径、注册与改档的字段校验、状态流转与注销约束、筛选。</p>
 *
 * <p>绕开 surefire（JDK 21 + surefire 3.2.5 有 LinkageError），以 main 直接验证。</p>
 */
public class AgentDomainTest {

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
        valueObject();
        statusSemantics();
        roleTemplate();
        summary();
        serviceValidation();
        serviceLifecycle();

        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------------- ① 值对象 ---------------- */

    static void valueObject() {
        LocalDateTime registered = LocalDateTime.of(2026, 11, 2, 9, 0);
        Agent a = Agent.of("build-runner-01", AgentRole.DEVELOPER,
                List.of("writeFile", "writeFile", " ", "runCompile"),
                List.of("fs:write", "fs:write"),
                ModelTier.FAST, registered, "编译与写入");

        eq("值对象-ID 待分配", a.id(), null);
        eq("值对象-技能去重去空", a.skillCount(), 2);
        eq("值对象-权限去重", a.permissionCount(), 1);
        eq("值对象-默认 ACTIVE", a.status(), AgentStatus.ACTIVE);
        check("值对象-可执行", a.canAct());
        check("值对象-具备 writeFile", a.hasSkill("writeFile"));
        check("值对象-不具备 deployService", !a.hasSkill("deployService"));

        Agent promoted = a.withProfile("build-runner-01", AgentRole.ARCHITECT, null, null,
                ModelTier.STRONG, null, null);
        eq("改档-角色已变", promoted.role(), AgentRole.ARCHITECT);
        eq("改档-null 技能保持原值", promoted.skillCount(), 2);
        eq("改档-ID 不变", promoted.id(), a.id());

        Agent cleared = a.withProfile(null, null, List.of(), null, null, null, null);
        eq("改档-空清单表示清空", cleared.skillCount(), 0);

        Agent suspended = a.withStatus(AgentStatus.SUSPENDED);
        check("状态-停用后不可执行", !suspended.canAct());
        check("状态-停用后不保留权限", !suspended.retainsPermissions());

        Agent withId = a.withId("AGT-0009");
        eq("落库-补齐 ID", withId.id(), "AGT-0009");
        eq("落库-其余字段保持", withId.name(), a.name());
    }

    /* ---------------- ② 状态语义 ---------------- */

    static void statusSemantics() {
        check("状态-ACTIVE 可执行且保留权限",
                AgentStatus.ACTIVE.canAct() && AgentStatus.ACTIVE.retainsPermissions());
        check("状态-PAUSED 不可执行但保留权限",
                !AgentStatus.PAUSED.canAct() && AgentStatus.PAUSED.retainsPermissions());
        check("状态-SUSPENDED 不可执行且回收权限",
                !AgentStatus.SUSPENDED.canAct() && !AgentStatus.SUSPENDED.retainsPermissions());
        check("状态-RETIRED 为终态",
                AgentStatus.RETIRED.isFinal() && !AgentStatus.RETIRED.isRegistered());
        check("状态-SUSPENDED 仍未注销", AgentStatus.SUSPENDED.isRegistered());
        eq("状态-中文标签解析", AgentStatus.parse("已注销"), AgentStatus.RETIRED);
        eq("状态-枚举名解析", AgentStatus.parse("paused"), AgentStatus.PAUSED);
        eq("状态-未知返回 null", AgentStatus.parse("WHATEVER"), null);

        eq("角色-短代码解析", AgentRole.parse("dev"), AgentRole.DEVELOPER);
        eq("角色-中文名解析", AgentRole.parse("测试工程师"), AgentRole.QA);
        eq("档位-capability 解析", ModelTier.parse("strong"), ModelTier.STRONG);
    }

    /* ---------------- ③ 角色模板 ---------------- */

    static void roleTemplate() {
        SkillRegistry registry = new SkillRegistry();

        eq("模板-PM 默认技能数", RoleTemplate.defaultSkills(AgentRole.PM).size(), 2);
        eq("模板-架构师走强推理", RoleTemplate.defaultModelTier(AgentRole.ARCHITECT), ModelTier.STRONG);
        eq("模板-开发走快速档", RoleTemplate.defaultModelTier(AgentRole.DEVELOPER), ModelTier.FAST);

        boolean allExist = true;
        for (AgentRole r : AgentRole.values()) {
            for (String s : RoleTemplate.defaultSkills(r)) {
                if (!registry.contains(s)) {
                    allExist = false;
                    System.out.println("  [WARN] 模板引用了不存在的工具：" + r + " -> " + s);
                }
            }
        }
        check("模板-所有默认技能都存在于 core 工具注册表", allExist);

        boolean permsWellFormed = true;
        for (AgentRole r : AgentRole.values()) {
            for (String p : RoleTemplate.defaultPermissions(r)) {
                if (!p.contains(":")) permsWellFormed = false;
            }
        }
        check("模板-所有默认权限都符合「域:动作」格式", permsWellFormed);
    }

    /* ---------------- ④ 汇总 ---------------- */

    static void summary() {
        LocalDateTime reg = LocalDateTime.of(2026, 11, 2, 9, 0);
        List<Agent> agents = List.of(
                Agent.of("dev-a", AgentRole.DEVELOPER,
                        List.of("writeFile", "runCompile"), List.of("fs:write", "build:run"),
                        ModelTier.FAST, reg, null),
                Agent.of("qa-a", AgentRole.QA,
                        List.of("runRuleScan"), List.of("review:read"),
                        ModelTier.FAST, reg, null).withStatus(AgentStatus.PAUSED),
                Agent.of("ops-a", AgentRole.OPS,
                        List.of("restartService"), List.of("ops:restart"),
                        ModelTier.FAST, reg, null).withStatus(AgentStatus.SUSPENDED),
                Agent.of("pm-a", AgentRole.PM,
                        List.of("splitTask"), List.of("plan:write"),
                        ModelTier.FAST, reg, null).withStatus(AgentStatus.RETIRED)
        );

        AgentSummary s = AgentSummaryCalculator.calculate(
                agents, Instant.parse("2026-11-03T00:00:00Z"));

        eq("汇总-总数", s.total(), 4);
        eq("汇总-ACTIVE", s.activeCount(), 1);
        eq("汇总-PAUSED", s.pausedCount(), 1);
        eq("汇总-SUSPENDED", s.suspendedCount(), 1);
        eq("汇总-RETIRED", s.retiredCount(), 1);
        eq("汇总-未注销不含 RETIRED", s.registeredCount(), 3);
        eq("汇总-可执行仅 ACTIVE 且有技能", s.executableCount(), 1);
        eq("汇总-角色分布 DEV", s.byRole().get("DEV"), 1);
        eq("汇总-角色分布不含已注销", s.byRole().get("PM"), null);
        eq("汇总-技能覆盖 writeFile", s.skillUsage().get("writeFile"), 1);
        eq("汇总-权限覆盖率分母排除停用", s.permissionCoverage(), 100.0);
        eq("汇总-平均技能数", s.avgSkillCount(), 1.3);

        AgentSummary empty = AgentSummaryCalculator.calculate(List.of(), Instant.now());
        eq("汇总-空注册表不除零", empty.permissionCoverage(), 0.0);
        eq("汇总-空注册表平均技能", empty.avgSkillCount(), 0.0);
    }

    /* ---------------- ⑤ 注册校验 ---------------- */

    static void serviceValidation() {
        AgentRepository repo = new InMemoryAgentRepository();
        AgentService service = new AgentService(repo, new SkillCatalogStub());

        Agent dev = service.register("build-runner-01", AgentRole.DEVELOPER,
                null, null, null, null, "编译与写入");
        check("注册-分配 ID", dev.id() != null && dev.id().startsWith("AGT-"));
        eq("注册-套用角色模板技能数", dev.skillCount(), 3);
        eq("注册-默认 ACTIVE", dev.status(), AgentStatus.ACTIVE);
        eq("注册-注册时间自动补当前", dev.registeredAt() != null, true);

        expectFail("注册-名称不可为空", () ->
                service.register("  ", AgentRole.DEVELOPER, null, null, null, null, null), "name");
        expectFail("注册-角色不可为空", () ->
                service.register("someone", null, null, null, null, null, null), "role");
        expectFail("注册-同名不可重复", () ->
                service.register("build-runner-01", AgentRole.QA, null, null, null, null, null), "name");
        expectFail("注册-技能必须在注册表中", () ->
                service.register("someone", AgentRole.DEVELOPER, List.of("deployServices"),
                        null, null, null, null), "skills");
        expectFail("注册-权限格式必须合法", () ->
                service.register("someone", AgentRole.DEVELOPER, null, List.of("delivery"),
                        null, null, null), "permissions");

        Agent noSkill = service.register("minimal-agent", AgentRole.DEVELOPER,
                List.of(), List.of(), null, null, null);
        eq("注册-空清单表示明确不配", noSkill.skillCount(), 0);
        eq("注册-空权限清单", noSkill.permissionCount(), 0);

        Agent custom = service.register("scan-agent", AgentRole.QA,
                List.of("readDiff"), List.of("review:read"), ModelTier.SMALL, null, null);
        eq("注册-自定义技能覆盖模板", custom.skillCount(), 1);
        eq("注册-自定义档位", custom.modelTier(), ModelTier.SMALL);

        eq("注册-总数", service.count(), 3);
    }

    /* ---------------- ⑥ 生命周期 ---------------- */

    static void serviceLifecycle() {
        AgentRepository repo = new InMemoryAgentRepository();
        AgentService service = new AgentService(repo, new SkillCatalogStub());

        Agent a = service.register("agent-a", AgentRole.DEVELOPER, null, null, null, null, null);
        String id = a.id();

        Agent renamed = service.update(id, "agent-b", AgentRole.QA, null, null, null, null, null);
        eq("改档-改名", renamed.name(), "agent-b");
        eq("改档-改角色", renamed.role(), AgentRole.QA);
        eq("改档-未传技能保持", renamed.skillCount(), a.skillCount());

        Agent cleared = service.update(id, null, null, List.of(), List.of(), null, null, null);
        eq("改档-空清单清空技能", cleared.skillCount(), 0);
        eq("改档-空清单清空权限", cleared.permissionCount(), 0);

        eq("状态-转 PAUSED", service.changeStatus(id, AgentStatus.PAUSED).status(), AgentStatus.PAUSED);
        check("状态-PAUSED 后不可执行", !service.findById(id).orElseThrow().canAct());

        expectFail("注销-未置 RETIRED 不可删除", () -> service.delete(id), "status");

        service.changeStatus(id, AgentStatus.RETIRED);
        eq("状态-转 RETIRED", service.findById(id).orElseThrow().status(), AgentStatus.RETIRED);
        expectFail("状态-已注销不可恢复", () -> service.changeStatus(id, AgentStatus.ACTIVE), "status");

        boolean guarded = false;
        try {
            service.assertCanAct(id);
        } catch (RuntimeException e) {
            guarded = e.getClass().getSimpleName().equals("AgentInactiveException");
        }
        check("守卫-已注销不允许执行", guarded);

        service.delete(id);
        eq("删除-RETIRED 后可删除", service.count(), 0);

        /* 筛选 */
        service.register("alpha", AgentRole.PM, null, null, null, null, null);
        Agent d = service.register("beta", AgentRole.DEVELOPER, List.of("runCompile"),
                null, null, null, null);
        service.changeStatus(d.id(), AgentStatus.PAUSED);

        eq("筛选-全部", service.list(null, null, null).size(), 2);
        eq("筛选-按状态", service.list(AgentStatus.PAUSED, null, null).size(), 1);
        eq("筛选-按角色", service.list(null, AgentRole.PM, null).size(), 1);
        eq("筛选-按关键字（名称）", service.list(null, null, "beta").size(), 1);
        eq("筛选-按关键字（技能）", service.list(null, null, "runCompile").size(), 1);
        eq("筛选-关键字无匹配", service.list(null, null, "nothing").size(), 0);

        eq("汇总-与注册表一致", service.summary().total(), 2);
    }

    /* ---------------- 辅助 ---------------- */

    /** 只认 core 内置工具名的目录桩。 */
    static class SkillCatalogStub implements SkillCatalog {
        private final SkillRegistry registry = new SkillRegistry();

        @Override
        public boolean exists(String skill) {
            return skill != null && registry.contains(skill.trim());
        }

        @Override
        public java.util.Set<String> all() {
            return registry.names();
        }
    }

    static void expectFail(String name, Runnable action, String expectedField) {
        try {
            action.run();
            fail++;
            System.out.println("[FAIL] " + name + "（未抛出异常）");
        } catch (AgentValidationException e) {
            boolean ok = expectedField.equals(e.field());
            if (ok) {
                pass++;
                System.out.println("[PASS] " + name + " -> " + e.getMessage());
            } else {
                fail++;
                System.out.println("[FAIL] " + name + " field expected=" + expectedField
                        + " actual=" + e.field());
            }
        } catch (RuntimeException e) {
            fail++;
            System.out.println("[FAIL] " + name + " 抛出了非预期异常：" + e);
        }
    }
}
