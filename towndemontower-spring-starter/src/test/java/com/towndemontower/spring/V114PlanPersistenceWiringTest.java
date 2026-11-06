package com.towndemontower.spring;

import com.towndemontower.core.approval.PlanApproval;
import com.towndemontower.core.approval.PlanApprovalService;
import com.towndemontower.core.approval.PlanArtifact;
import com.towndemontower.core.approval.PlanStatus;
import com.towndemontower.core.approval.PlanStep;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.decorator.GovernancePass;
import com.towndemontower.core.mode.ModeToolPolicy;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [starter/test] V1.14 —— 计划单跨重启回放 + 工具覆盖。
 *
 * <p>同一 state-dir 启动两次：第一次提交/批准结构化计划后关容器；第二次启动不重新提交，
 * 闸门直接按计划覆盖范围放行。同时验证计划外的工具仍然被拦。</p>
 */
public class V114PlanPersistenceWiringTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        public PlanTools planTools() {
            return new PlanTools();
        }

        @Bean
        public ModeToolPolicy modeToolPolicy() {
            return mode -> Set.of("applyFix", "deleteFix", "plainFix");
        }
    }

    static class PlanTools {

        static final AtomicInteger APPLIED = new AtomicInteger();
        static final AtomicInteger DELETED = new AtomicInteger();
        static final AtomicInteger PLAIN = new AtomicInteger();

        @AgentSkill(name = "applyFix", risk = "R1", requiredPerms = {"plan:apply"},
                requiredArgs = {"file"}, requiresPlanApproval = true)
        public String applyFix(String file) {
            GovernancePass.require("applyFix");
            APPLIED.incrementAndGet();
            return "fixed:" + file;
        }

        @AgentSkill(name = "deleteFix", risk = "R1", requiredPerms = {"plan:apply"},
                requiredArgs = {"file"}, requiresPlanApproval = true)
        public String deleteFix(String file) {
            GovernancePass.require("deleteFix");
            DELETED.incrementAndGet();
            return "deleted:" + file;
        }

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
        String stateDir = "target/v114-state-" + System.nanoTime();
        String auditFile = "target/v114-audit-" + System.nanoTime() + ".log";
        String planId;

        // 第一次启动：提交 + 批准
        try (ConfigurableApplicationContext ctx = boot(stateDir, auditFile)) {
            PlanApprovalService plans = ctx.getBean(PlanApprovalService.class);
            PlanArtifact plan = PlanArtifact.of("只改不删", List.of(
                    PlanStep.of("applyFix", "改代码")));
            planId = plans.request("plan-agent", "plan-task", plan);
            plans.approve(planId, "human-1", "同意");
            check("首次-计划落为 APPROVED",
                    plans.get(planId).status == PlanStatus.APPROVED);
        }

        Path planFile = Path.of(stateDir, "plans.log");
        check("持久化-计划文件已生成", Files.exists(planFile));
        check("持久化-文件含批准记录",
                Files.readString(planFile).contains("#PLAN-STORE v1"));

        // 第二次启动：不重新提交，闸门按回放的计划放行
        try (ConfigurableApplicationContext ctx = boot(stateDir, auditFile)) {
            PlanTools tools = ctx.getBean(PlanTools.class);
            PlanApprovalService plans = ctx.getBean(PlanApprovalService.class);
            PlanApproval loaded = plans.get(planId);

            check("重启-计划单被回放", loaded != null
                    && loaded.status == PlanStatus.APPROVED);
            check("重启-步骤与工具范围保留", loaded != null
                    && loaded.plan.steps.size() == 1 && loaded.plan.coversTool("applyFix"));

            String out = tools.applyFix("A.java");
            check("重启-计划内工具免重新提交直接放行",
                    "fixed:A.java".equals(out) && PlanTools.APPLIED.get() == 1);

            String denied = deniedCode(() -> tools.deleteFix("A.java"));
            check("重启-计划外工具仍被拦",
                    ErrorCodes.PLAN_APPROVAL_REQUIRED.equals(denied)
                            && PlanTools.DELETED.get() == 0);

            String plain = tools.plainFix("B.java");
            check("重启-未声明工具不受影响",
                    "plain:B.java".equals(plain) && PlanTools.PLAIN.get() == 1);
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

    static ConfigurableApplicationContext boot(String stateDir, String auditFile) {
        return new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "towndemontower.audit-file=" + auditFile,
                        "towndemontower.state-dir=" + stateDir,
                        "towndemontower.permissions-bootstrap=plan:apply",
                        "towndemontower.agent-id=plan-agent",
                        "towndemontower.task-id=plan-task",
                        "towndemontower.tx.enabled=false")
                .run();
    }
}
