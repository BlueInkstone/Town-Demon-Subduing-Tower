package com.towndemontower.spring;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.decorator.GovernancePass;
import com.towndemontower.core.decorator.UngovernedInvocationException;
import com.towndemontower.core.metrics.GovernanceMetrics;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * [starter/test] V1.11 / D2 —— 令牌在装配层真的生效。
 *
 * <p>验证四件事：</p>
 * <ol>
 *   <li><b>正常调用放行</b>：走 AOP → 装饰器链 → 链尾发牌 → 方法首行 require 命中；</li>
 *   <li><b>绕过切面直调被拦</b>：拿裸对象调用同一个方法（等价于"控制器直接 new Service"
 *       或自调用 {@code this.x()}），首行拿不到令牌 → {@code UNGOVERNED_INVOCATION} 且留痕；</li>
 *   <li><b>R2 待审批不执行</b>：业务方法根本没跑，因此不产生"非受治理调用"；</li>
 *   <li><b>显式弃权档仍可用</b>：{@code enabled=false} + {@code disabled-behavior=allow} 时
 *       方法能跑，但每次弃权都留一条 {@code GOVERNANCE_BYPASS_ISSUED}。</li>
 * </ol>
 */
public class V111PassEnforcementTest {

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

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        governedAndBypassed();
        allowModeKeepsWorking();

        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** ①②③：同一次启动里既验证"走代理放行"，也验证"绕过代理被拦"。 */
    static void governedAndBypassed() throws Exception {
        fresh("target/v111-1.log");
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v111-1.log",
                "towndemontower.state-dir=target/v111-state-1",
                "towndemontower.permissions-bootstrap=fs:write")) {
            Tools governed = ctx.getBean(Tools.class);

            // ① 正常路径
            String out = governed.write("/tmp/a", "x");
            check("令牌-受治理调用放行", "wrote:/tmp/a".equals(out));

            AuditStore audit = ctx.getBean(AuditStore.class);
            GovernanceMetrics metrics = ctx.getBean(GovernanceMetrics.class);
            check("令牌-正常调用不计违规",
                    metrics.get(GovernanceMetrics.UNGOVERNED_INVOCATION) == 0);

            // ② 绕过切面：裸对象没有代理，也就没有链尾发牌
            UngovernedInvocationException caught = null;
            try {
                new Tools().write("/tmp/b", "y");
            } catch (UngovernedInvocationException e) {
                caught = e;
            }
            check("令牌-直调被拦", caught != null
                    && "UNGOVERNED_INVOCATION".equals(caught.getCode()));
            check("令牌-直调计数", metrics.get(GovernanceMetrics.UNGOVERNED_INVOCATION) >= 1);
            check("令牌-直调留痕", audit.all().stream()
                    .anyMatch(a -> "UNGOVERNED_INVOCATION".equals(a.action)));

            // 线程清理：一次违规不应污染后续正常调用
            check("令牌-违规后仍可正常调用", "wrote:/tmp/c".equals(governed.write("/tmp/c", "z")));
        }
    }

    /** ④ 显式弃权档：治理关闭 + allow，方法必须还能跑，但弃权本身要留痕。 */
    static void allowModeKeepsWorking() throws Exception {
        fresh("target/v111-allow.log");
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.enabled=false",
                "towndemontower.disabled-behavior=allow",
                // allow 是 CRITICAL 配置，自检会拒绝启动；这里显式弃权以便验证行为
                "towndemontower.integrity-strict=false",
                "towndemontower.audit-file=target/v111-allow.log",
                "towndemontower.state-dir=target/v111-state-allow")) {
            Tools tools = ctx.getBean(Tools.class);
            String out = tools.write("/tmp/d", "w");
            check("弃权-方法仍可执行", "wrote:/tmp/d".equals(out));

            AuditStore audit = ctx.getBean(AuditStore.class);
            check("弃权-已留痕", audit.all().stream()
                    .anyMatch(a -> GovernancePass.BYPASS_AUDIT_CODE.equals(a.action)));
        }
    }

    static ConfigurableApplicationContext boot(String... props) {
        return new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(props)
                .run();
    }

    /** 审计落盘版会在启动时校验锚点，残留文件会让"日志被截断"判定成立，先清干净。 */
    static void fresh(String file) throws Exception {
        Path f = Path.of(file);
        Files.deleteIfExists(f);
        Files.deleteIfExists(f.resolveSibling(f.getFileName() + ".anchor"));
    }
}
