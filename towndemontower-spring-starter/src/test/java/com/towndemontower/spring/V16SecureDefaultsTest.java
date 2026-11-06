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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * V1.6 安全默认值（starter 侧）：在<b>零配置</b>下验证默认行为是安全的。
 *
 * <p>与功能测试的区别：这里不验证"能不能跑通"，而验证"不配就是安全的"——
 * 默认不自动授权、默认拒绝未知入口、默认把拒绝写进审计、默认拒绝启动明显降级的配置。</p>
 */
public class V16SecureDefaultsTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        public Tools tools() {
            return new Tools();
        }
    }

    static class Tools {

        @AgentSkill(name = "restartService", risk = "R1", requiredPerms = {"ops:restart"},
                requiredArgs = {"service"}, confidence = 1.0)
        public String restart(String service) {
            GovernancePass.require("restartService");
            return "restarted:" + service;
        }

        @AgentSkill(name = "writeFile", risk = "R1", requiredPerms = {"fs:write"},
                requiredArgs = {"path", "content"}, confidence = 1.0)
        public String write(String path, String content) {
            GovernancePass.require("writeFile");
            return "wrote:" + path;
        }
    }

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        denyByDefault();
        bootstrapGrants();
        inputRejected();
        failClosedWhenDisabled();
        strictStartupBlocksRiskyDefaults();
        auditDurableByDefault();

        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- ① 默认不自动授权：没有显式授予就拒绝 ---------- */
    static void denyByDefault() {
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v16-1.log",
                "towndemontower.state-dir=target/v16-state-1")) {
            AuditStore audit = ctx.getBean(AuditStore.class);
            int before = audit.all().size();
            Tools tools = ctx.getBean(Tools.class);

            String code = null;
            try {
                tools.restart("svc-1");
            } catch (GovernanceDeniedException e) {
                code = e.getCode();
            }
            check("默认-未授权调用被拒", "PERMISSION_DENIED".equals(code));
            check("默认-拒绝写入审计", audit.all().size() > before);
        }
    }

    /* ---------- ② 显式授权（bootstrap）后可用 ---------- */
    static void bootstrapGrants() {
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v16-2.log",
                "towndemontower.state-dir=target/v16-state-2",
                "towndemontower.permissions-bootstrap=ops:restart,fs:write")) {
            Tools tools = ctx.getBean(Tools.class);
            check("授权-显式授予后可执行", "restarted:svc-1".equals(tools.restart("svc-1")));
        }
    }

    /* ---------- ③ 输入校验：AOP 入口即拒，且留痕 ---------- */
    static void inputRejected() {
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v16-3.log",
                "towndemontower.state-dir=target/v16-state-3",
                "towndemontower.permissions-bootstrap=fs:write")) {
            Tools tools = ctx.getBean(Tools.class);
            AuditStore audit = ctx.getBean(AuditStore.class);
            int before = audit.all().size();

            // 默认上限 1 MiB：这里造一个明显超过上限的输入（1.2 MB）
            check("输入-超长参数被拒", rejectedCode(() -> tools.write("/tmp/a", "x".repeat(1_200_000))).equals("INPUT_REJECTED"));
            check("输入-路径穿越被拒", rejectedCode(() -> tools.write("../../etc/passwd", "x")).equals("INPUT_REJECTED"));
            check("输入-控制字符被拒", rejectedCode(() -> tools.write("/tmp/a", "a\u0000b")).equals("INPUT_REJECTED"));
            check("输入-正常参数可执行", tools.write("/tmp/a", "hello").startsWith("wrote:"));
            check("输入-拒绝均留痕", audit.all().stream()
                    .filter(a -> a.payload != null && a.payload.contains("INPUT_REJECTED"))
                    .count() >= 3);
            check("输入-拒绝不写业务数据", audit.all().size() > before);
        }
    }

    /* ---------- ④ fail-closed：关闭治理 = 拒绝（首次宽限仅一次） ---------- */
    static void failClosedWhenDisabled() {
        // ④-1 默认 legacy-grace=true：首次调用先告警留痕再放行，第二次起熔断
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.enabled=false",
                "towndemontower.audit-file=target/v16-4.log",
                "towndemontower.state-dir=target/v16-state-4",
                "towndemontower.permissions-bootstrap=ops:restart")) {
            Tools tools = ctx.getBean(Tools.class);
            AuditStore audit = ctx.getBean(AuditStore.class);

            String first = null;
            try {
                first = tools.restart("svc-1");
            } catch (GovernanceDeniedException e) {
                first = "DENIED:" + e.getCode();
            }
            check("熔断-首次宽限放行", "restarted:svc-1".equals(first));
            check("熔断-首次宽限留痕", audit.all().stream()
                    .anyMatch(a -> LegacyGovernanceGrace.AUDIT_CODE.equals(a.action)));

            String code = null;
            try {
                tools.restart("svc-2");
            } catch (GovernanceDeniedException e) {
                code = e.getCode();
            }
            check("熔断-第二次起拒绝", "GOVERNANCE_DISABLED".equals(code));
            check("熔断-拒绝留痕", audit.all().stream()
                    .anyMatch(a -> "GOVERNANCE_DISABLED_CALL".equals(a.action)));

            // 宽限是「进程级」的一次，不是「每工具一次」：换个工具也不会再给
            String otherTool = null;
            try {
                otherTool = tools.write("/tmp/a", "x");
            } catch (GovernanceDeniedException e) {
                otherTool = "DENIED:" + e.getCode();
            }
            check("熔断-宽限不按工具重置", "DENIED:GOVERNANCE_DISABLED".equals(otherTool));
        }

        // ④-2 legacy-grace=false：首次即硬拒绝（不需要过渡的接入方直接选这一档）
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.enabled=false",
                "towndemontower.legacy-grace=false",
                "towndemontower.audit-file=target/v16-4b.log",
                "towndemontower.state-dir=target/v16-state-4b")) {
            Tools tools = ctx.getBean(Tools.class);
            String code = null;
            try {
                tools.restart("svc-3");
            } catch (GovernanceDeniedException e) {
                code = e.getCode();
            }
            check("熔断-关闭宽限后首次即拒", "GOVERNANCE_DISABLED".equals(code));
        }
    }

    /* ---------- ⑤ strict：明显降级的配置拒绝启动 ---------- */
    static void strictStartupBlocksRiskyDefaults() {
        check("strict-内存审计拒绝启动", startupFailsWith("AUDIT_VOLATILE",
                "towndemontower.audit-storage=memory",
                "towndemontower.state-dir=target/v16-state-5",
                "towndemontower.approval-dir=target/v16-appr-5"));

        check("strict-端点无令牌拒绝启动", startupFailsWith("ENDPOINT_WITHOUT_TOKEN",
                "towndemontower.audit-file=target/v16-6.log",
                "towndemontower.state-dir=target/v16-state-6",
                "towndemontower.metrics-endpoint=true"));

        check("strict-空审批目录拒绝启动", startupFailsWith("APPROVAL_NOT_PERSISTED",
                "towndemontower.audit-file=target/v16-7.log",
                "towndemontower.state-dir=target/v16-state-7",
                "towndemontower.approval-dir="));

        // 对照组：显式关掉 strict 后允许启动（弃权必须显式）
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-storage=memory",
                "towndemontower.state-dir=target/v16-state-8",
                "towndemontower.integrity-strict=false")) {
            check("strict-显式弃权可启动", ctx.getBean(Tools.class) != null);
        }
    }

    /* ---------- ⑥ 默认审计落盘且可校验 ---------- */
    static void auditDurableByDefault() throws Exception {
        Path f = Path.of("target/v16-9.log");
        Files.deleteIfExists(f);
        // V1.9.2 起：删日志必须连外锚一起删。只剩锚点而日志没了，启动会按"日志被截断"
        // 拒绝——这是有意为之（证据不能被无声丢弃），运维轮转日志时同样适用
        Files.deleteIfExists(f.resolveSibling(f.getFileName() + ".anchor"));
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v16-9.log",
                "towndemontower.state-dir=target/v16-state-9",
                "towndemontower.permissions-bootstrap=ops:restart")) {
            ctx.getBean(Tools.class).restart("svc-9");
            AuditStore audit = ctx.getBean(AuditStore.class);
            check("审计-默认实现为落盘版", audit instanceof FileAuditStore);
            check("审计-文件已生成", Files.exists(f));
            check("审计-链可校验", audit.verifyChain());
            check("审计-含自检记录", audit.all().stream()
                    .anyMatch(a -> "GOVERNANCE_SELF_CHECK".equals(a.action)));
        }
        List<String> lines = Files.readAllLines(f);
        check("审计-磁盘上有记录", lines.stream().anyMatch(l -> l.startsWith("1\t")));
    }

    /* ---------- 辅助 ---------- */

    static ConfigurableApplicationContext boot(String... props) {
        return new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(props)
                .run();
    }

    static boolean startupFailsWith(String expectedCode, String... props) {
        try (ConfigurableApplicationContext ctx = boot(props)) {
            return false;
        } catch (RuntimeException e) {
            Throwable t = e;
            while (t != null) {
                if (t.getMessage() != null && t.getMessage().contains(expectedCode)) return true;
                t = t.getCause();
            }
            return false;
        }
    }

    @FunctionalInterface
    interface Call {
        Object run();
    }

    static String rejectedCode(Call call) {
        try {
            call.run();
            return "NONE";
        } catch (GovernanceDeniedException e) {
            return e.getCode();
        }
    }

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }
}
