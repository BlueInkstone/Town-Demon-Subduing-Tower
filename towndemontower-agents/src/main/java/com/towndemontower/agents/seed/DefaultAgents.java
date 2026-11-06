package com.towndemontower.agents.seed;

import com.towndemontower.agents.domain.Agent;
import com.towndemontower.agents.domain.AgentRole;
import com.towndemontower.agents.domain.AgentStatus;
import com.towndemontower.agents.domain.ModelTier;
import com.towndemontower.agents.governance.AgentGovernanceFacade;
import com.towndemontower.core.contract.ExecutionContext;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [agents/seed] 演示用 Agent 基线。
 *
 * <p>覆盖四种状态与五种角色，便于验证筛选、汇总与权限回收：
 * 其中 SUSPENDED 与 RETIRED 两个的档案里权限配置仍在，但 RBAC 中已被回收——
 * 正好用来演示"应然权限 vs 实然权限"的账实分离。</p>
 *
 * <p><b>时间口径</b>：注册时间固定为基线日，是事实而非相对时间；
 * 状态变更是播种过程中的真实操作，因此会正常写入审计哈希链。</p>
 */
public final class DefaultAgents {

    /** 基线日。 */
    private static final LocalDateTime BASELINE = LocalDateTime.of(2026, 11, 2, 9, 0);

    private DefaultAgents() {
    }

    /**
     * 播种演示 Agent。
     *
     * <p>走 {@link AgentGovernanceFacade} 而不是领域服务，是为了让授权同步一并发生：
     * 播种结束后，ACTIVE / PAUSED 的权限在 RBAC 里真实生效，SUSPENDED / RETIRED 的已被回收。
     * 若绕过门面直接写仓储，汇总里的权限覆盖率就会显示为 0。</p>
     */
    public static void seed(AgentGovernanceFacade facade, ExecutionContext ctx) {
        // 产品 / 规划
        facade.register(ctx, "planner", AgentRole.PM,
                List.of("splitTask", "writeDesign", "readTestReport"),
                List.of("plan:write", "delivery:read", "delivery:write", "agents:read"),
                ModelTier.FAST, BASELINE, "需求拆解与验收标准");

        // 架构：唯一默认使用强推理档
        facade.register(ctx, "architect", AgentRole.ARCHITECT,
                List.of("writeDesign", "splitTask", "readDiff", "runRuleScan"),
                List.of("plan:write", "review:read", "agents:read"),
                ModelTier.STRONG, BASELINE, "契约评审与风险分级");

        // 开发（在岗）
        facade.register(ctx, "dev-core", AgentRole.DEVELOPER,
                null, null, null, BASELINE, "装饰器链与执行器");

        // 开发（暂停）：权限保留，恢复即用，但暂停期间不允许执行
        Agent paused = facade.register(ctx, "dev-ui", AgentRole.DEVELOPER,
                null, null, null, BASELINE, "前端与看板");
        facade.changeStatus(ctx, paused.id(), AgentStatus.PAUSED);

        // 测试（停用）：档案保留权限配置，RBAC 中已整员回收
        Agent suspended = facade.register(ctx, "qa-gate", AgentRole.QA,
                null, null, null, BASELINE, "回归用例与质量门禁");
        facade.changeStatus(ctx, suspended.id(), AgentStatus.SUSPENDED);

        // 运维（在岗）
        facade.register(ctx, "ops-deploy", AgentRole.OPS,
                null, null, null, BASELINE, "部署与应急");

        // 运维（已注销）：ID 封存，权限永久回收，仅留档案供审计追溯
        Agent retired = facade.register(ctx, "ops-ci", AgentRole.OPS,
                null, null, null, BASELINE, "容器沙箱与 CI");
        facade.changeStatus(ctx, retired.id(), AgentStatus.RETIRED);
    }

    /* ---------- 供测试断言的口径 ---------- */

    public static int expectedTotal() {
        return 7;
    }

    public static int expectedActive() {
        return 4;
    }

    public static int expectedPaused() {
        return 1;
    }

    public static int expectedSuspended() {
        return 1;
    }

    public static int expectedRetired() {
        return 1;
    }

    public static int expectedExecutable() {
        return 4;
    }
}
