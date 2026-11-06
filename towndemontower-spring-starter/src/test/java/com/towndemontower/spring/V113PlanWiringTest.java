package com.towndemontower.spring;

import com.towndemontower.core.approval.PlanApprovalService;
import com.towndemontower.core.approval.PlanStatus;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.decorator.GovernancePass;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.mode.ModeToolPolicy;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [starter/test] V1.13 / Plan-and-Execute —— 注解上的计划审批要求真的生效。
 *
 * <p>钉住五件事：注解属性进入描述符；未提交计划时拒绝且业务方法不执行；
 * 提交后仍是 PENDING 拒绝并回显单号；人工批准后放行；计划生命周期进审计链。</p>
 */
public class V113PlanWiringTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        public PlanTools planTools() {
            return new PlanTools();
        }

        /** 自定义工具名不在 core 预置白名单里，显式声明（真实接入方的常规做法）。 */
        @Bean
        public ModeToolPolicy modeToolPolicy() {
            return mode -> Set.of("applyFix", "plainFix");
        }
    }

    static class PlanTools {

        static final AtomicInteger APPLIED = new AtomicInteger();
        static final AtomicInteger PLAIN = new AtomicInteger();

        @AgentSkill(name = "applyFix", risk = "R1", requiredPerms = {"plan:apply"},
                requiredArgs = {"file"}, requiresPlanApproval = true)
        public String applyFix(String file) {
            GovernancePass.require("applyFix");
            APPLIED.incrementAndGet();
            return "fixed:" + file;
        }

        /** 对照：没声明计划要求的工具不受影响。 */
        @AgentSkill(name = "plainFix", risk = "R1", requiredPerms = {"plan:apply"},
                requiredArgs = {"file"})
        public String plainFix(String file) {
            GovernancePass.require("plainFix");
            PLAIN.incrementAndGet();
            return "plain:" + file;
        }
    }

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        String tag = Long.toString(System.nanoTime());
        String auditFile = "target/v113-" + tag + ".log";
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=" + auditFile,
                // v1.14 起计划单默认落盘，测试目录必须每次唯一，避免上一轮已批准的计划串场。
                "towndemontower.state-dir=target/v113-state-" + tag,
                "towndemontower.permissions-bootstrap=plan:apply",
                "towndemontower.agent-id=plan-agent",
                "towndemontower.task-id=plan-task",
                // 本用例只验证计划闸门，不需要事务一致性落库（也避免测试依赖建表脚本）。
                "towndemontower.tx.enabled=false")) {

            PlanTools tools = ctx.getBean(PlanTools.class);
            PlanApprovalService plans = ctx.getBean(PlanApprovalService.class);
            SkillRegistry registry = ctx.getBean(SkillRegistry.class);
            AuditStore audit = ctx.getBean(AuditStore.class);

            // ① 未提交计划 → 拒绝，且业务方法没执行
            String code0 = deniedCode(() -> tools.applyFix("A.java"));
            check("计划-未提交被拒", ErrorCodes.PLAN_APPROVAL_REQUIRED.equals(code0));
            check("计划-拒绝后未执行", PlanTools.APPLIED.get() == 0);

            // ② 注解属性映射进描述符（懒注册发生在首次调用之后）
            check("计划-注解映射进描述符",
                    registry.get("applyFix") != null && registry.get("applyFix").requiresPlanApproval);

            // ③ 提交计划 → 仍为待审批，拒绝文案带单号
            String id = plans.request("plan-agent", "plan-task", "修复三个文件");
            String pending = deniedMessage(() -> tools.applyFix("A.java"));
            check("计划-待审批仍被拒", pending != null && pending.contains(id));
            check("计划-待审批未执行", PlanTools.APPLIED.get() == 0);
            check("计划-单状态为 PENDING", plans.get(id).status == PlanStatus.PENDING);

            // ④ 自批被拒（审批动作不能由提交者自己完成）
            check("计划-禁自批", throwsIae(() -> plans.approve(id, "plan-agent", "自己批自己")));

            // ⑤ 人工批准 → 放行
            plans.approve(id, "human-1", "同意");
            String out = tools.applyFix("A.java");
            check("计划-批准后放行", "fixed:A.java".equals(out) && PlanTools.APPLIED.get() == 1);

            // ⑥ 未声明计划要求的工具不受影响
            String plain = tools.plainFix("B.java");
            check("计划-未声明工具照常放行", "plain:B.java".equals(plain) && PlanTools.PLAIN.get() == 1);

            // ⑦ 计划生命周期进同一条审计链
            boolean requested = audit.all().stream()
                    .anyMatch(r -> "PLAN_REQUESTED".equals(r.action) && r.payload.contains(id));
            boolean approved = audit.all().stream()
                    .anyMatch(r -> "PLAN_APPROVED".equals(r.action) && r.payload.contains("by=human-1"));
            check("计划-提交与批准进审计链", requested && approved);
            check("计划-审计链完整", audit.verifyChain());
        }

        System.out.println("\nRESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    interface Call {
        void run() throws Exception;
    }

    static String deniedCode(Call call) {
        try {
            call.run();
            return "<no-denial>";
        } catch (GovernanceDeniedException ex) {
            return ex.getCode();
        } catch (Exception ex) {
            return "<" + ex.getClass().getSimpleName() + ">";
        }
    }

    static String deniedMessage(Call call) {
        try {
            call.run();
            return null;
        } catch (GovernanceDeniedException ex) {
            return ex.getMessage();
        } catch (Exception ex) {
            return null;
        }
    }

    static boolean throwsIae(Call call) {
        try {
            call.run();
            return false;
        } catch (IllegalArgumentException ex) {
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    static ConfigurableApplicationContext boot(String... props) {
        return new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(props)
                .run();
    }
}
