package com.towndemontower.spring;

import com.towndemontower.core.approval.FilePlanApprovalStore;
import com.towndemontower.core.approval.PlanApprovalService;
import com.towndemontower.core.approval.PlanArtifact;
import com.towndemontower.core.audit.AuditStore;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * [starter/test] V1.15 计划单按大小轮转的接线。
 *
 * <p>钉住四件事：{@code plan-max-bytes} 能绑到属性上；超过阈值后真的产生归档并压缩主文件；
 * 轮转不影响审计链；重启后仍能回放计划单且单号不回退。</p>
 */
public class V115PlanRotationWiringTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
    }

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        String tag = Long.toString(System.nanoTime());
        String stateDir = "target/v115-rot-state-" + tag;
        String auditFile = "target/v115-rot-audit-" + tag + ".log";
        Path planFile = Path.of(stateDir, "plans.log");
        List<String> ids = new ArrayList<>();

        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=" + auditFile,
                "towndemontower.state-dir=" + stateDir,
                "towndemontower.plan-max-bytes=300",
                "towndemontower.permissions-bootstrap=plan:apply",
                "towndemontower.agent-id=plan-agent",
                "towndemontower.task-id=plan-task",
                "towndemontower.tx.enabled=false")) {

            TownDemontowerProperties props = ctx.getBean(TownDemontowerProperties.class);
            PlanApprovalService plans = ctx.getBean(PlanApprovalService.class);
            AuditStore audit = ctx.getBean(AuditStore.class);

            check("属性-轮转阈值已绑定", props.getPlanMaxBytes() == 300L);

            for (int i = 0; i < 10; i++) {
                ids.add(plans.request("plan-agent", "task-" + i, PlanArtifact.text("计划")));
            }

            Path dir = Path.of(stateDir);
            check("轮转-产生归档文件", archiveCount(dir) >= 1);
            check("轮转-主文件不超过单数", recordLines(planFile) <= 10);
            check("轮转-计划单全部可查", ids.stream().allMatch(id -> plans.get(id) != null));
            check("轮转-审计链仍完整", audit.verifyChain());
        }

        PlanApprovalService restarted = new PlanApprovalService(
                new FilePlanApprovalStore(planFile), null);
        check("回放-轮转后单号可查", restarted.get(ids.get(0)) != null);
        check("回放-轮转后状态保留", restarted.get(ids.get(0)).status != null);
        String fresh = restarted.request("plan-agent", "task-new", PlanArtifact.text("新计划"));
        check("回放-序号不回退", seqOf(fresh) > seqOf(ids.get(ids.size() - 1)));

        System.out.println("\nRESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    static ConfigurableApplicationContext boot(String... props) {
        return new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(props)
                .run();
    }

    static long recordLines(Path log) throws Exception {
        if (!Files.exists(log)) return 0;
        try (Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank() && !l.startsWith("#")).count();
        }
    }

    static long archiveCount(Path dir) throws Exception {
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".archive")).count();
        }
    }

    static long seqOf(String id) {
        return Long.parseLong(id.substring(id.lastIndexOf('-') + 1));
    }
}
