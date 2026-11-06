package com.towndemontower.spring;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.decorator.GovernancePass;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * [starter/test] 快速上手冒烟验证：完全按《快速上手》文档的方式接入，
 * 验证「普通 Spring Boot 应用 + 只加注解」这条最容易被照抄的路径真的能跑。
 *
 * <p>与 {@link StarterDemo} 的区别：StarterDemo 是手动 register 自动配置类，
 * 本类走**真实自动配置**（{@code @EnableAutoConfiguration} + META-INF imports），
 * 不手动注册任何治理 Bean、不手动开 AOP——这正是接入方项目里的形态。</p>
 *
 * <p>验证三件事：
 * <ol>
 *   <li>自动配置生效：容器里拿得到 {@code Executor} 与 {@code AuditStore}；</li>
 *   <li>AOP 生效：{@code @AgentSkill} 方法被拦截并执行真实逻辑；</li>
 *   <li>治理生效：审计链里有这次调用的记录，且链可校验。</li>
 * </ol>
 */
public class QuickstartSmokeTest {

    /** 最小可运行应用：与文档里让读者粘贴的代码一致。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class MiniApp {

        @Bean
        public OpsTools opsTools() {
            return new OpsTools();
        }
    }

    /** 读者的业务代码：只加注解 + 一行令牌声明，不写任何治理逻辑。 */
    static class OpsTools {

        @AgentSkill(name = "restartService", risk = "R1",
                requiredPerms = {"ops:restart"}, requiredArgs = {"service"},
                confidence = 1.0)
        public String restart(String service) {
            GovernancePass.require("restartService");
            return "restarted:" + service;
        }

        @AgentSkill(name = "deployService", risk = "R2",
                requiredPerms = {"ops:deploy"}, requiredArgs = {"target", "version"},
                confidence = 1.0)
        public String deploy(String target, String version) {
            GovernancePass.require("deployService");
            return "deployed:" + target + "@" + version;
        }
    }

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond, Object detail) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + name + (detail == null ? "" : " -> " + detail));
        } else {
            fail++;
            System.out.println("[FAIL] " + name + (detail == null ? "" : " -> " + detail));
        }
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplicationBuilder(MiniApp.class)
                .web(WebApplicationType.NONE)
                // 本地开发常用配置：声明了 requiredPerms 就自动授予当前 agent
                .properties(
                        "towndemontower.enabled=true",
                        "towndemontower.agent-id=quickstart-agent",
                        "towndemontower.mode=DEV_EXECUTION",
                        "towndemontower.auto-grant-declared-perms=true")
                .build();

        try (ConfigurableApplicationContext ctx = app.run()) {
            /* ① 自动配置是否生效 */
            check("自动配置-Executor 已装配",
                    ctx.getBean(com.towndemontower.core.contract.Executor.class) != null, null);
            AuditStore audit = ctx.getBean(HashChainAuditStore.class);
            check("自动配置-AuditStore 已装配", audit != null, null);

            OpsTools tools = ctx.getBean(OpsTools.class);

            /* ② R1：放行并执行真实方法 */
            String r1 = tools.restart("svc-1");
            check("R1 放行并执行真实方法", "restarted:svc-1".equals(r1), r1);

            /* ③ R2：转人工审批，业务方法不应被执行 */
            String r2code = null;
            try {
                String leaked = tools.deploy("web", "1.0");
                r2code = "NOT_DENIED:" + leaked;
            } catch (GovernanceDeniedException e) {
                r2code = e.getCode() + (e.getApprovalTicketId() == null ? "" : " ticket=" + e.getApprovalTicketId());
            }
            check("R2 转人工审批（业务方法未执行）", r2code.startsWith("PENDING_APPROVAL"), r2code);

            /* ④ 审计链 */
            int records = audit.all().size();
            check("审计-调用已落链", records >= 2, records + " 条");
            check("审计-哈希链可校验", audit.verifyChain(), null);
        }

        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
