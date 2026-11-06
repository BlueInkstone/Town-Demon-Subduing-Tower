package com.towndemontower.spring;

import com.towndemontower.core.audit.AuditStore;
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
 * [starter/test] V1.9.2 / I4-b —— 审计收权与锚点追平在装配层的落地。
 *
 * <p>验证五件事：</p>
 * <ol>
 *   <li><b>默认不变松</b>：不配任何东西时 {@code anchorBatch==1}，写一条即入锚；</li>
 *   <li><b>批量化要显式开</b>：配 {@code audit-anchor-batch} 才落后，且自检报
 *       {@code AUDIT_ANCHOR_BATCHED}(INFO) 把代价写进启动日志；</li>
 *   <li><b>配错不会静默关锚点</b>：{@code 0} / 负数被夹到 1，不是"关掉追平"；</li>
 *   <li><b>退出必须追平</b>：容器关闭后锚点覆盖到文件最后一条——正常缩容/滚动重启
 *       不该留一条"锚点外的尾巴"；</li>
 *   <li><b>可观测</b>：{@code /integrity} 暴露锚点位置、落后量与批大小。</li>
 * </ol>
 */
public class V19AuditSealWiringTest {

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
        defaultIsEveryWrite();
        batchingRequiresExplicitOptIn();
        zeroDoesNotSilentlyDisable();
        shutdownFlushesAnchor();
        integrityExposesAnchorState();

        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- ① 默认：每条追平 ---------- */

    static void defaultIsEveryWrite() {
        String log = "target/v192-1.log";
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=" + log,
                "towndemontower.state-dir=target/v192-state-1")) {
            AuditStore audit = ctx.getBean(AuditStore.class);
            check("默认-审计落盘实现", audit instanceof FileAuditStore);
            FileAuditStore f = (FileAuditStore) audit;
            eq("默认-批大小为1", f.anchorBatch(), 1);
            eq("默认-不按时间追平", f.anchorIntervalMs(), 0L);

            long base = f.anchorRecords();
            audit.appendAuto("A", "1");
            audit.appendAuto("B", "2");
            eq("默认-锚点追平到最新", f.anchorRecords(), base + 2);
            eq("默认-无落后", f.anchorLag(), 0L);
            check("默认-链可验", audit.verifyChain());

            check("默认-自检不报批量化",
                    !checker(ctx).codes().contains("AUDIT_ANCHOR_BATCHED"));
            check("默认-启动仍合规", checker(ctx).compliant());
        }
    }

    /* ---------- ② 批量化：显式开、显式告警 ---------- */

    static void batchingRequiresExplicitOptIn() {
        String log = "target/v192-2.log";
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=" + log,
                "towndemontower.state-dir=target/v192-state-2",
                "towndemontower.audit-anchor-batch=5")) {
            AuditStore audit = ctx.getBean(AuditStore.class);
            FileAuditStore f = (FileAuditStore) audit;
            eq("批量-批大小生效", f.anchorBatch(), 5);

            long base = f.anchorRecords();
            // 启动时自检本身会写一条 GOVERNANCE_SELF_CHECK，那条在批量模式下也还没入锚，
            // 所以这里以"启动后的落后量"为基准，而不是假设 0
            long lagBefore = f.anchorLag();
            audit.appendAuto("A", "1");
            audit.appendAuto("A", "2");
            audit.appendAuto("A", "3");
            eq("批量-未到阈值不追平", f.anchorRecords(), base);
            eq("批量-落后3条", f.anchorLag(), lagBefore + 3);

            List<GovernanceIntegrityChecker.Finding> batched = checker(ctx).findings().stream()
                    .filter(x -> "AUDIT_ANCHOR_BATCHED".equals(x.code()))
                    .toList();
            eq("批量-自检报出", batched.size(), 1);
            eq("批量-级别为INFO", batched.get(0).severity(),
                    GovernanceIntegrityChecker.Severity.INFO);
            check("批量-说明里带上限", batched.get(0).detail().contains("4"));
            check("批量-不阻断启动", checker(ctx).compliant());

            // 追平后与文件一致
            f.flushAnchor();
            eq("批量-手动追平后无落后", f.anchorLag(), 0L);
        }
    }

    /* ---------- ③ 配 0 不等于关掉锚点 ---------- */

    static void zeroDoesNotSilentlyDisable() {
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v192-3.log",
                "towndemontower.state-dir=target/v192-state-3",
                "towndemontower.audit-anchor-batch=0",
                "towndemontower.audit-anchor-interval-ms=-5")) {
            FileAuditStore f = (FileAuditStore) ctx.getBean(AuditStore.class);
            eq("配0-夹到1（每条追平）", f.anchorBatch(), 1);
            eq("负间隔-夹到0", f.anchorIntervalMs(), 0L);
        }
    }

    /* ---------- ④ 关闭容器时追平锚点 ---------- */

    static void shutdownFlushesAnchor() throws IOException {
        Path log = Path.of("target/v192-4.log");
        Files.deleteIfExists(log);
        Files.deleteIfExists(log.resolveSibling(log.getFileName() + ".anchor"));

        long lagAtClose;
        ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=" + log,
                "towndemontower.state-dir=target/v192-state-4",
                "towndemontower.audit-anchor-batch=100");
        try {
            AuditStore audit = ctx.getBean(AuditStore.class);
            FileAuditStore f = (FileAuditStore) audit;
            for (int i = 0; i < 3; i++) audit.appendAuto("A", "p" + i);
            lagAtClose = f.anchorLag();
            check("退出-关闭前确实有落后", lagAtClose > 0);
        } finally {
            ctx.close();
        }

        long onDisk = anchorRecordsOnDisk(log);
        int inFile = logRecords(log);
        check("退出-关闭后锚点已覆盖全部记录", onDisk == inFile);
        eq("退出-锚点条数等于日志条数", onDisk, (long) inFile);
    }

    /* ---------- ⑤ /integrity 暴露锚点状态 ---------- */

    static void integrityExposesAnchorState() {
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v192-5.log",
                "towndemontower.state-dir=target/v192-state-5",
                "towndemontower.audit-anchor-batch=9",
                "towndemontower.metrics-endpoint=true",
                "towndemontower.metrics-token=tok-seal")) {
            GovernanceMetricsController controller = ctx.getBean(GovernanceMetricsController.class);
            Map<String, Object> out = controller.integrity("tok-seal");
            check("端点-有锚点条数", out.containsKey("auditAnchorRecords"));
            check("端点-有落后量", out.containsKey("auditAnchorLag"));
            check("端点-有批大小", out.containsKey("auditAnchorBatch"));
            eq("端点-批大小正确", out.get("auditAnchorBatch"), 9);
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

    /** 直接读锚点文件里的 {@code records=}，绕开"重开即重写锚点"的干扰。 */
    static long anchorRecordsOnDisk(Path log) throws IOException {
        Path anchor = log.resolveSibling(log.getFileName() + ".anchor");
        if (!Files.exists(anchor)) return -1;
        String text = Files.readString(anchor, StandardCharsets.UTF_8).trim();
        for (String part : text.split("\t")) {
            if (part.startsWith("records=")) {
                return Long.parseLong(part.substring("records=".length()));
            }
        }
        return -2;
    }

    static int logRecords(Path log) throws IOException {
        int n = 0;
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) continue;
            if (line.startsWith("#SEGMENT")) continue;
            n++;
        }
        return n;
    }
}
