package com.towndemontower.spring.tx;

import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.executor.SkillInvoker;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.spring.DelegatingSkillInvoker;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Map;

/**
 * [starter/tx/test] 事务一致性端到端验证（离线可运行 main）。
 *
 * <p>启动真实 Spring Boot 上下文（内嵌 H2 + JDBC 事务管理器 + schema.sql），验证：
 * ① R2 待审批 / R3 拒绝 / R1 放行 三类决策都能在同一事务内落库 governance_decision 与 outbox；
 * ② 事务提交后 Outbox 中继将事件送达 WS 发布器，并将 outbox 标记为 PUBLISHED；
 * ③ WS 不可用时 outbox 保持 PENDING 并累加重试，恢复后由中继（轮询/崩溃恢复路径）补发。</p>
 *
 * <p>绕开 surefire（JDK 21 + surefire 3.2.5 有 LinkageError），以 main 直接验证。</p>
 */
public class TownDemontowerTxTest {

    /** 测试用工具执行器：直接返回结果（生产环境由 AgentSkillAspect 经 ThreadLocal 注入真实方法执行）。 */
    static final SkillInvoker TEST_INVOKER = (d, i) -> "executed:" + d.name + " args=" + i.args;

    @SpringBootApplication(scanBasePackages = "com.towndemontower.spring.tx")
    static class TxTestConfig {
        /** 捕获型发布器：覆盖默认内存总线，用于断言"事件已送达 WS"。 */
        @Bean
        CapturingPublisher capturingPublisher() {
            return new CapturingPublisher();
        }
    }

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(TxTestConfig.class, args);
        try {
            run(ctx);
        } finally {
            ctx.close();
        }
        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void run(ConfigurableApplicationContext ctx) {
        Executor executor = ctx.getBean(Executor.class);
        SkillRegistry registry = ctx.getBean(SkillRegistry.class);
        TransactionalGovernanceDecisionRecorder recorder = ctx.getBean(TransactionalGovernanceDecisionRecorder.class);
        OutboxRelay relay = ctx.getBean(OutboxRelay.class);
        GovernanceDecisionRepository decisionRepo = ctx.getBean(GovernanceDecisionRepository.class);
        OutboxRepository outboxRepo = ctx.getBean(OutboxRepository.class);
        CapturingPublisher publisher = ctx.getBean(CapturingPublisher.class);

        // 预注册工具（空权限，聚焦事务一致性验证）
        registry.register(new SkillDescriptor("deployService", "R2", new String[0], new String[0], new String[]{"target", "version"}, false, "docker"));
        registry.register(new SkillDescriptor("deleteData", "R3", new String[0], new String[0], new String[]{"table"}, false, "none"));
        registry.register(new SkillDescriptor("restartService", "R1", new String[0], new String[0], new String[]{"service"}, false, "proc"));

        ExecutionContext ec = new ExecutionContext("S1", "T1", "tenant", "agent-ops", "owner", false, AgentMode.DEV_EXECUTION);

        // ① R2 部署 → PENDING_APPROVAL，落库 + Outbox + WS 推送一致
        int before = publisher.received.size();
        SkillResult r2 = decide(recorder, executor, intent("deployService", map("target", "web", "version", "1.0")), ec, "R2");
        check("tx.r2.pending", !r2.success && "PENDING_APPROVAL".equals(r2.code) && r2.approvalTicketId != null);
        check("tx.r2.ws-pushed", publisher.received.size() == before + 1);
        check("tx.r2.outbox-published", outboxRepo.countPending() == 0);

        // ② R3 删除 → R3_DENIED，落库 + 推送
        SkillResult r3 = decide(recorder, executor, intent("deleteData", map("table", "orders")), ec, "R3");
        check("tx.r3.denied", !r3.success && "R3_DENIED".equals(r3.code));
        check("tx.r3.ws-pushed", publisher.received.size() == before + 2);

        // ③ R1 重启 → 成功执行，落库 + 推送
        SkillResult r1 = decide(recorder, executor, intent("restartService", map("service", "svc-1")), ec, "R1");
        check("tx.r1.ok", r1.success);

        // 决策总数 = 3，且每条都有对应 WS 事件
        check("tx.decisions=3", decisionRepo.count() == 3);
        check("tx.ws-count=3", publisher.received.size() == before + 3);

        // 审批事件内容校验
        boolean hasApprovalEvent = publisher.received.stream().anyMatch(e ->
                "APPROVAL_REQUESTED".equals(e.eventType()) && e.approvalTicketId() != null && "deployService".equals(e.tool()));
        check("tx.ws-approval-event", hasApprovalEvent);

        // ④ 失败重试 / 崩溃恢复：模拟 WS 不可用 → Outbox 保持 PENDING + 累加重试，事件未真正送达
        int beforeFail = publisher.received.size();
        publisher.failNext = true;
        decide(recorder, executor, intent("restartService", map("service", "svc-2")), ec, "R1");
        check("tx.fail.outbox-pending", outboxRepo.countPending() >= 1);
        check("tx.fail.not-pushed", publisher.received.size() == beforeFail);

        // 恢复后由中继轮询补发（等价于进程崩溃后的恢复路径）
        relay.poll();
        check("tx.recover.published", outboxRepo.countPending() == 0);
        check("tx.recover.pushed", publisher.received.size() == beforeFail + 1);

        check("tx.decisions=4", decisionRepo.count() == 4);
    }

    static IntentObject intent(String tool, Map<String, Object> args) {
        return new IntentObject(tool, args, 0.85, "reason", "effect", "rollback");
    }

    /** 绑定测试用工具执行器到链尾（与生产 AgentSkillAspect 经 ThreadLocal 注入真实方法执行同机制）。 */
    static SkillResult decide(TransactionalGovernanceDecisionRecorder recorder, Executor executor,
                              IntentObject intent, ExecutionContext ec, String risk) {
        DelegatingSkillInvoker.CURRENT.set(TEST_INVOKER);
        try {
            return recorder.decide(executor, intent, ec, risk);
        } finally {
            DelegatingSkillInvoker.CURRENT.remove();
        }
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
