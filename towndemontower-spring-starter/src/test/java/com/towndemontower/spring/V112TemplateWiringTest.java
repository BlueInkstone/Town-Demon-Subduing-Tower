package com.towndemontower.spring;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [starter/test] V1.12 / I5 —— 注解上的命令模板真的生效。
 *
 * <p>验证三件事：注解里的模板进了 core 的 {@code SkillDescriptor}；形状正确的命令照常执行；
 * 形状不符的命令被 {@code COMMAND_TEMPLATE_MISMATCH} 拒绝（且业务方法没被调用）。</p>
 */
public class V112TemplateWiringTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        public OpsTools opsTools() {
            return new OpsTools();
        }

        /**
         * 默认模式白名单只认 core 预置工具名；本用例验证的是自定义工具，因此显式声明白名单。
         * 这也是真实接入方的常规做法（业务工具名通常不在 core 的预置集合里）。
         */
        @Bean
        public ModeToolPolicy modeToolPolicy() {
            return mode -> Set.of("restartSvc");
        }
    }

    static class OpsTools {

        /**
         * 用静态计数而不是实例字段：CGLIB 代理是新实例，实例字段读到的是代理自己的那份（恒为 0），
         * 这不是实现问题而是"怎么观察"的问题——静态计数器绕开代理，才测得到真实执行次数。
         */
        static final AtomicInteger EXECUTED = new AtomicInteger();

        @AgentSkill(name = "restartSvc", risk = "R1", requiredPerms = {"ops:restart"},
                requiredArgs = {"service", "command"},
                commandTemplate = "systemctl restart {service}")
        public String restart(String service, String command) {
            GovernancePass.require("restartSvc");
            EXECUTED.incrementAndGet();
            return "restarted:" + service;
        }
    }

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        fresh("target/v112-1.log");
        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-file=target/v112-1.log",
                "towndemontower.state-dir=target/v112-state",
                "towndemontower.permissions-bootstrap=ops:restart")) {

            OpsTools tools = ctx.getBean(OpsTools.class);
            SkillRegistry registry = ctx.getBean(SkillRegistry.class);

            // ① 形状正确 → 放行
            String out = tools.restart("nginx", "systemctl restart nginx");
            check("模板-形状正确放行", "restarted:nginx".equals(out) && OpsTools.EXECUTED.get() == 1);

            // ② 模板已从注解映射进描述符（描述符是首次调用时懒注册的，所以放在调用之后断言）
            String declared = registry.get("restartSvc") == null
                    ? null : registry.get("restartSvc").commandTemplate;
            check("模板-注解映射进描述符", "systemctl restart {service}".equals(declared));

            // ③ 追加命令 → 拒绝，且业务方法未被调用
            int before = OpsTools.EXECUTED.get();
            String code1 = deniedCode(() -> tools.restart("nginx", "systemctl restart nginx ; rm -rf /"));
            check("模板-追加命令被拒", ErrorCodes.COMMAND_TEMPLATE_MISMATCH.equals(code1));
            check("模板-拒绝后未执行", OpsTools.EXECUTED.get() == before);

            // ④ 换字面量 → 拒绝
            String code2 = deniedCode(() -> tools.restart("nginx", "systemctl stop nginx"));
            check("模板-字面量不符被拒", ErrorCodes.COMMAND_TEMPLATE_MISMATCH.equals(code2));

            // ⑤ 命令替换 → 拒绝
            String code3 = deniedCode(() -> tools.restart("nginx", "systemctl restart $(id)"));
            check("模板-命令替换被拒", ErrorCodes.COMMAND_TEMPLATE_MISMATCH.equals(code3));
        }

        System.out.println("\nRESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    interface Call {
        void run() throws Exception;
    }

    /** 取拒绝码；没抛异常返回 {@code "<no-denial>"}。 */
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

    static ConfigurableApplicationContext boot(String... props) {
        return new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(props)
                .run();
    }

    static void fresh(String file) throws Exception {
        Path f = Path.of(file);
        Files.deleteIfExists(f);
        Files.deleteIfExists(f.resolveSibling(f.getFileName() + ".anchor"));
    }
}
