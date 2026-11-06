package com.towndemontower.spring;

import com.towndemontower.core.audit.AnchorReplica;
import com.towndemontower.core.audit.AnchorReplicaState;
import com.towndemontower.core.audit.AnchorSnapshot;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.FileAnchorReplica;
import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.core.decorator.GovernancePass;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * [starter/test] V1.10 / I4-c —— 锚点副本在装配层的落地。
 *
 * <p>验证六件事：</p>
 * <ol>
 *   <li><b>不配就没有</b>：默认零副本，自检报 {@code ANCHOR_SINGLE_COPY}(INFO)，说明
 *       "整个目录回滚"仍是敞口；</li>
 *   <li><b>配了就真的推</b>：写审计后副本账本同步前进；</li>
 *   <li><b>同机副本不算异地</b>：{@code file:} 副本触发 {@code ANCHOR_REPLICA_NOT_OFFHOST}(WARN)，
 *       {@code file+offhost:} 才消掉；</li>
 *   <li><b>写错配置启动失败</b>：拼错的副本地址不会被当成"没配"；</li>
 *   <li><b>降级模式要留痕</b>：{@code required=false} 时自检报
 *       {@code ANCHOR_REPLICA_BEST_EFFORT}(WARN)；</li>
 *   <li><b>可观测</b>：{@code /integrity} 暴露每个副本的条数与失败计数。</li>
 * </ol>
 */
public class V19AnchorReplicaWiringTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        public Tools tools() {
            return new Tools();
        }
    }

    static class Tools {
        @AgentSkill(name = "writeFile", risk = "R1", requiredPerms = {"fs:write"},
                requiredArgs = {"path", "content"}, confidence = 1.0)
        public String write(String path, String content) {
            GovernancePass.require("writeFile");
            return "wrote:" + path;
        }
    }

    static final String KEY_ENV = "TOWNDEMONTOWER_AUDIT_KEY";
    static final String TOKEN_ENV = "TOWNDEMONTOWER_ANCHOR_TOKEN";

    static int pass = 0, fail = 0;

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

    public static void main(String[] args) throws Exception {
        // 有了审计密钥，副本账本的 MAC 链才可校验（无密钥时只靠条数单调性）
        System.setProperty("dummy", "1");
        try {
            noReplicaByDefault();
            replicaReceivesPushes();
            sameHostReplicaIsFlagged();
            offHostDeclarationClearsWarning();
            badSpecFailsFast();
            bestEffortIsFlagged();
            integrityExposesReplicas();
        } finally {
            System.clearProperty("dummy");
        }

        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- ① 默认无副本 ---------- */

    static void noReplicaByDefault() {
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v110-1.log",
                "towndemontower.state-dir=target/v110-state-1")) {
            FileAuditStore f = (FileAuditStore) ctx.getBean(AuditStore.class);
            check("默认-零副本", f.replicas().isEmpty());

            List<GovernanceIntegrityChecker.Finding> single = findings(ctx, "ANCHOR_SINGLE_COPY");
            eq("默认-自检报单副本", single.size(), 1);
            eq("默认-级别为INFO", single.get(0).severity(),
                    GovernanceIntegrityChecker.Severity.INFO);
            check("默认-说明点出目录回滚", single.get(0).detail().contains("回滚"));
            check("默认-不阻断启动", checker(ctx).compliant());
        }
    }

    /* ---------- ② 副本真的收到推送 ---------- */

    static void replicaReceivesPushes() throws IOException {
        Path ledger = Path.of("target/v110-2.ledger");
        Files.deleteIfExists(ledger);
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v110-2.log",
                "towndemontower.state-dir=target/v110-state-2",
                "towndemontower.audit-anchor-replicas=file:" + ledger.toAbsolutePath())) {
            FileAuditStore f = (FileAuditStore) ctx.getBean(AuditStore.class);
            eq("副本-装配1个", f.replicas().size(), 1);
            long base = f.anchorRecords();
            check("副本-启动即已推送", base > 0);
            eq("副本-本地锚点与日志一致", base, (long) logRecords(Path.of("target/v110-2.log")));

            f.appendAuto("A", "1");
            f.appendAuto("A", "2");
            f.flushAnchor();

            AnchorSnapshot snap = new FileAnchorReplica(ledger, null).pull();
            eq("副本-账本追到本地锚点", snap.records(), f.anchorRecords());
            eq("副本-哈希与本地一致", snap.hash(), f.anchorHash());
            eq("副本-无失败", f.replicaFailures(), 0L);
            check("副本-状态可读", f.replicaStates().get(0).readable());
        }
    }

    /* ---------- ③ 同机副本不算异地 ---------- */

    static void sameHostReplicaIsFlagged() throws IOException {
        Path ledger = Path.of("target/v110-3.ledger");
        Files.deleteIfExists(ledger);
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v110-3.log",
                "towndemontower.state-dir=target/v110-state-3",
                "towndemontower.audit-anchor-replicas=file:" + ledger.toAbsolutePath())) {
            List<GovernanceIntegrityChecker.Finding> warn = findings(ctx, "ANCHOR_REPLICA_NOT_OFFHOST");
            eq("同机-自检告警", warn.size(), 1);
            eq("同机-级别为WARN", warn.get(0).severity(),
                    GovernanceIntegrityChecker.Severity.WARN);
            check("同机-说明点出权限域隔离", warn.get(0).detail().contains("权限域"));
            // WARN 不阻断：同机副本仍然挡得住"删日志"，只是挡不住"整盘回滚"
            check("同机-不阻断启动", checker(ctx).compliant());
        }
    }

    /* ---------- ④ 声明异地后告警消失 ---------- */

    static void offHostDeclarationClearsWarning() throws IOException {
        Path ledger = Path.of("target/v110-4.ledger");
        Files.deleteIfExists(ledger);
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v110-4.log",
                "towndemontower.state-dir=target/v110-state-4",
                "towndemontower.audit-anchor-replicas=file+offhost:" + ledger.toAbsolutePath())) {
            FileAuditStore f = (FileAuditStore) ctx.getBean(AuditStore.class);
            check("异地-副本标记offHost", f.replicas().get(0).offHost());
            eq("异地-不再告警同机",
                    findings(ctx, "ANCHOR_REPLICA_NOT_OFFHOST").size(), 0);
            eq("异地-不再报单副本", findings(ctx, "ANCHOR_SINGLE_COPY").size(), 0);
            check("异地-推送照常", new FileAnchorReplica(ledger, null).pull().records() > 0);
        }
    }

    /* ---------- ⑤ 配错启动失败 ---------- */

    static void badSpecFailsFast() {
        boolean failed = false;
        String msg = null;
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v110-5.log",
                "towndemontower.state-dir=target/v110-state-5",
                "towndemontower.audit-anchor-replicas=s3://bucket/prefix")) {
            // 不该走到这里
            ctx.getBean(AuditStore.class);
        } catch (RuntimeException e) {
            failed = true;
            msg = rootMessage(e);
        }
        check("配错-启动失败", failed);
        check("配错-提示可用形式", msg != null && msg.contains("file:")
                && msg.contains("http"));
        check("配错-不静默当成没配", msg != null && msg.contains("audit-anchor-replicas"));
    }

    /* ---------- ⑥ 降级模式留痕 ---------- */

    static void bestEffortIsFlagged() throws IOException {
        Path ledger = Path.of("target/v110-6.ledger");
        Files.deleteIfExists(ledger);
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v110-6.log",
                "towndemontower.state-dir=target/v110-state-6",
                "towndemontower.audit-anchor-replicas=file+offhost:" + ledger.toAbsolutePath(),
                "towndemontower.audit-anchor-replica-required=false")) {
            List<GovernanceIntegrityChecker.Finding> warn = findings(ctx, "ANCHOR_REPLICA_BEST_EFFORT");
            eq("降级-自检告警", warn.size(), 1);
            eq("降级-级别为WARN", warn.get(0).severity(),
                    GovernanceIntegrityChecker.Severity.WARN);
            check("降级-说明点出证据可能缺失", warn.get(0).detail().contains("异地证据"));
        }
    }

    /* ---------- ⑦ /integrity 暴露副本状态 ---------- */

    static void integrityExposesReplicas() throws IOException {
        Path ledger = Path.of("target/v110-7.ledger");
        Files.deleteIfExists(ledger);
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v110-7.log",
                "towndemontower.state-dir=target/v110-state-7",
                "towndemontower.audit-anchor-replicas=file+offhost:" + ledger.toAbsolutePath(),
                "towndemontower.metrics-endpoint=true",
                "towndemontower.metrics-token=tok-rep")) {
            GovernanceMetricsController controller = ctx.getBean(GovernanceMetricsController.class);
            Map<String, Object> out = controller.integrity("tok-rep");
            check("端点-有副本列表", out.containsKey("auditAnchorReplicas"));
            check("端点-有失败计数", out.containsKey("auditAnchorReplicaFailures"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reps = (List<Map<String, Object>>) out.get("auditAnchorReplicas");
            eq("端点-1个副本", reps.size(), 1);
            eq("端点-副本标记异地", reps.get(0).get("offHost"), true);
            check("端点-副本条数为正", ((Number) reps.get(0).get("records")).longValue() > 0);
            check("端点-副本可读", Boolean.TRUE.equals(reps.get(0).get("readable")));
            eq("端点-失败计数为0", out.get("auditAnchorReplicaFailures"), 0L);
        }
    }

    /* ---------- 辅助 ---------- */

    static ConfigurableApplicationContext boot(String... props) {
        return new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(props)
                .run();
    }

    static GovernanceIntegrityChecker checker(ConfigurableApplicationContext ctx) {
        return ctx.getBean(GovernanceIntegrityChecker.class);
    }

    static List<GovernanceIntegrityChecker.Finding> findings(ConfigurableApplicationContext ctx,
                                                             String code) {
        return checker(ctx).findings().stream()
                .filter(x -> code.equals(x.code()))
                .toList();
    }

    static String rootMessage(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null) sb.append(' ').append(t.getMessage());
        }
        return sb.toString();
    }

    static int logRecords(Path log) throws IOException {
        if (!Files.exists(log)) return 0;
        int n = 0;
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) continue;
            if (line.startsWith("#SEGMENT")) continue;
            n++;
        }
        return n;
    }
}
