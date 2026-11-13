package com.towndemontower.agents;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.executor.SkillRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [agents/test] 端到端验证（真实 Spring Boot 上下文 + 真实 HTTP + 内嵌 Tomcat）。
 *
 * <p>验证：注册 / 改档 / 状态流转 / 注销的 REST 全链路、筛选与汇总、
 * 字段校验与 404、权限账实一致性、审计哈希链、共享注册表与白名单隔离，
 * 以及<b>花名册页面可访问</b>（只读页面，写操作仍走 REST + 治理链）。</p>
 *
 * <p>绕开 surefire（JDK 21 + surefire 3.2.5 有 LinkageError），以 main 直接验证。</p>
 */
public class AgentWebTest {

    /**
     * 注册表不需要数据库。
     *
     * <p>starter 传递引入了 spring-boot-starter-jdbc，若不排除数据源自动配置，
     * Spring Boot 会因"找不到驱动"启动失败。排除后 starter 的事务 Bean
     * （{@code @ConditionalOnBean(DataSource.class)}）自动跳过，不影响治理链验证。</p>
     */
    @SpringBootApplication(scanBasePackages = "com.towndemontower.agents",
            exclude = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
    static class TestApp {
    }

    static int pass = 0;
    static int fail = 0;

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

    static TestRestTemplate rest;

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(TestApp.class);
        ConfigurableApplicationContext ctx = app.run(
                "--server.port=0",
                "--towndemontower.agents.demo-data=false");
        try {
            rest = new TestRestTemplate();
            run(ctx);
        } finally {
            ctx.close();
        }
        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    @SuppressWarnings("unchecked")
    static void run(ConfigurableApplicationContext springCtx) {
        String base = "http://127.0.0.1:" + springCtx.getEnvironment().getProperty("local.server.port");

        /* ---------- ① 元数据 ---------- */
        ResponseEntity<Map> meta = rest.getForEntity(base + "/api/agents/meta", Map.class);
        eq("meta-状态码", meta.getStatusCode(), HttpStatus.OK);
        eq("meta-角色数", ((List<?>) meta.getBody().get("roles")).size(), 5);
        eq("meta-状态数", ((List<?>) meta.getBody().get("statuses")).size(), 4);
        eq("meta-档位数", ((List<?>) meta.getBody().get("modelTiers")).size(), 4);
        List<String> skills = (List<String>) meta.getBody().get("skills");
        check("meta-技能清单含业务工具", skills.contains("writeFile"));
        check("meta-技能清单含注册表工具", skills.contains("agents.registerAgent"));

        /* ---------- ② 注册（套角色模板） ---------- */
        ResponseEntity<Map> created = rest.postForEntity(base + "/api/agents",
                body("build-runner-01", "DEVELOPER"), Map.class);
        eq("注册-状态码201", created.getStatusCode(), HttpStatus.CREATED);
        String id = (String) created.getBody().get("id");
        check("注册-ID 前缀", id != null && id.startsWith("AGT-"));
        eq("注册-名称", created.getBody().get("name"), "build-runner-01");
        eq("注册-角色标签", created.getBody().get("roleLabel"), "开发工程师");
        eq("注册-默认 ACTIVE", created.getBody().get("status"), "ACTIVE");
        eq("注册-可执行", created.getBody().get("canAct"), Boolean.TRUE);
        eq("注册-套模板技能数", ((List<?>) created.getBody().get("skills")).size(), 3);
        eq("注册-套模板权限数", ((List<?>) created.getBody().get("permissions")).size(), 4);
        eq("注册-实然权限已授予", ((List<?>) created.getBody().get("effectivePermissions")).size(), 4);
        eq("注册-账实一致", created.getBody().get("permissionsInSync"), Boolean.TRUE);

        /* ---------- ③ 注册（自定义技能/权限/档位） ---------- */
        Map<String, Object> custom = body("arch-agent", "ARCHITECT");
        custom.put("skills", List.of("writeDesign", "readDiff"));
        custom.put("permissions", List.of("plan:write"));
        custom.put("modelTier", "STRONG");
        custom.put("note", "契约评审");
        ResponseEntity<Map> created2 = rest.postForEntity(base + "/api/agents", custom, Map.class);
        eq("注册-自定义状态码", created2.getStatusCode(), HttpStatus.CREATED);
        String id2 = (String) created2.getBody().get("id");
        eq("注册-自定义技能数", ((List<?>) created2.getBody().get("skills")).size(), 2);
        eq("注册-自定义档位", created2.getBody().get("modelTier"), "STRONG");

        /* ---------- ④ 列表与筛选 ---------- */
        eq("列表-总数", rest.getForEntity(base + "/api/agents", List.class).getBody().size(), 2);
        eq("筛选-ACTIVE", rest.getForEntity(base + "/api/agents?status=ACTIVE", List.class).getBody().size(), 2);
        eq("筛选-开发角色", rest.getForEntity(base + "/api/agents?role=DEVELOPER", List.class).getBody().size(), 1);
        eq("筛选-关键字", rest.getForEntity(base + "/api/agents?keyword=build-runner", List.class).getBody().size(), 1);
        eq("筛选-未知状态400", rest.getForEntity(base + "/api/agents?status=NOPE", Map.class)
                .getStatusCode(), HttpStatus.BAD_REQUEST);
        eq("筛选-未知角色400", rest.getForEntity(base + "/api/agents?role=NOPE", Map.class)
                .getStatusCode(), HttpStatus.BAD_REQUEST);

        /* ---------- ⑤ 汇总 ---------- */
        ResponseEntity<Map> summary = rest.getForEntity(base + "/api/agents/summary", Map.class);
        eq("汇总-状态码", summary.getStatusCode(), HttpStatus.OK);
        eq("汇总-总数", summary.getBody().get("total"), 2);
        eq("汇总-ACTIVE", summary.getBody().get("activeCount"), 2);
        eq("汇总-可执行", summary.getBody().get("executableCount"), 2);
        eq("汇总-权限覆盖率", summary.getBody().get("permissionCoverage"), 100.0);
        eq("汇总-角色分布项数", ((List<?>) summary.getBody().get("byRole")).size(), 5);

        /* ---------- ⑥ 改档：覆盖式授权 ---------- */
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("role", "QA");
        change.put("permissions", List.of("review:read"));
        ResponseEntity<Map> updated = rest.exchange(base + "/api/agents/" + id, HttpMethod.PUT,
                new HttpEntity<>(change, jsonHeaders()), Map.class);
        eq("改档-状态码", updated.getStatusCode(), HttpStatus.OK);
        eq("改档-角色已变", updated.getBody().get("role"), "QA");
        eq("改档-名称保持", updated.getBody().get("name"), "build-runner-01");
        eq("改档-权限已覆盖", ((List<?>) updated.getBody().get("effectivePermissions")).size(), 1);
        check("改档-旧权限已撤销",
                !((List<?>) updated.getBody().get("effectivePermissions")).contains("build:run"));

        /* ---------- ⑦ 状态流转：停用回收权限 ---------- */
        ResponseEntity<Map> suspended = rest.exchange(base + "/api/agents/" + id + "/status",
                HttpMethod.PUT, new HttpEntity<>(Map.of("status", "SUSPENDED"), jsonHeaders()), Map.class);
        eq("状态-停用成功", suspended.getStatusCode(), HttpStatus.OK);
        eq("状态-停用标签", suspended.getBody().get("statusLabel"), "停用");
        eq("状态-不可执行", suspended.getBody().get("canAct"), Boolean.FALSE);
        eq("状态-实然权限已清空", ((List<?>) suspended.getBody().get("effectivePermissions")).size(), 0);
        eq("状态-档案权限仍保留", ((List<?>) suspended.getBody().get("permissions")).size(), 1);
        eq("状态-账实一致（停用即应收空）", suspended.getBody().get("permissionsInSync"), Boolean.TRUE);
        eq("状态-筛选停用", rest.getForEntity(base + "/api/agents?status=SUSPENDED", List.class)
                .getBody().size(), 1);

        /* ---------- ⑧ 恢复 → 注销为终态 ---------- */
        ResponseEntity<Map> active = rest.exchange(base + "/api/agents/" + id + "/status",
                HttpMethod.PUT, new HttpEntity<>(Map.of("status", "ACTIVE"), jsonHeaders()), Map.class);
        eq("状态-恢复后权限回来", ((List<?>) active.getBody().get("effectivePermissions")).size(), 1);

        rest.exchange(base + "/api/agents/" + id + "/status", HttpMethod.PUT,
                new HttpEntity<>(Map.of("status", "RETIRED"), jsonHeaders()), Map.class);
        ResponseEntity<Map> revive = rest.exchange(base + "/api/agents/" + id + "/status",
                HttpMethod.PUT, new HttpEntity<>(Map.of("status", "ACTIVE"), jsonHeaders()), Map.class);
        eq("状态-已注销不可恢复400", revive.getStatusCode(), HttpStatus.BAD_REQUEST);
        eq("状态-终态错误字段", revive.getBody().get("field"), "status");

        /* ---------- ⑨ 删除约束 ---------- */
        ResponseEntity<Map> delActive = rest.exchange(base + "/api/agents/" + id2,
                HttpMethod.DELETE, null, Map.class);
        eq("删除-未注销被拒绝400", delActive.getStatusCode(), HttpStatus.BAD_REQUEST);
        eq("删除-拒绝提示指向状态", delActive.getBody().get("field"), "status");

        ResponseEntity<Map> delNotFound = rest.exchange(base + "/api/agents/AGT-9999",
                HttpMethod.DELETE, null, Map.class);
        eq("删除-不存在404", delNotFound.getStatusCode(), HttpStatus.NOT_FOUND);

        rest.exchange(base + "/api/agents/" + id2 + "/status", HttpMethod.PUT,
                new HttpEntity<>(Map.of("status", "RETIRED"), jsonHeaders()), Map.class);
        ResponseEntity<Map> deleted = rest.exchange(base + "/api/agents/" + id2,
                HttpMethod.DELETE, null, Map.class);
        eq("删除-注销后可删除", deleted.getStatusCode(), HttpStatus.OK);
        eq("删除-返回标记", deleted.getBody().get("deleted"), Boolean.TRUE);

        /* ---------- ⑩ 校验失败 → 400 ---------- */
        ResponseEntity<Map> blank = rest.postForEntity(base + "/api/agents",
                body("  ", "DEVELOPER"), Map.class);
        eq("校验-空名称400", blank.getStatusCode(), HttpStatus.BAD_REQUEST);
        eq("校验-空名称字段", blank.getBody().get("field"), "name");

        ResponseEntity<Map> badRole = rest.postForEntity(base + "/api/agents",
                body("someone", "NOPE"), Map.class);
        eq("校验-未知角色400", badRole.getStatusCode(), HttpStatus.BAD_REQUEST);
        eq("校验-未知角色字段", badRole.getBody().get("field"), "role");

        Map<String, Object> badSkill = body("someone", "DEVELOPER");
        badSkill.put("skills", List.of("deployServices"));
        ResponseEntity<Map> badSkillResp = rest.postForEntity(base + "/api/agents", badSkill, Map.class);
        eq("校验-未知技能400", badSkillResp.getStatusCode(), HttpStatus.BAD_REQUEST);
        eq("校验-未知技能字段", badSkillResp.getBody().get("field"), "skills");

        Map<String, Object> badPerm = body("someone", "DEVELOPER");
        badPerm.put("permissions", List.of("delivery"));
        ResponseEntity<Map> badPermResp = rest.postForEntity(base + "/api/agents", badPerm, Map.class);
        eq("校验-非法权限格式400", badPermResp.getStatusCode(), HttpStatus.BAD_REQUEST);
        eq("校验-非法权限字段", badPermResp.getBody().get("field"), "permissions");

        ResponseEntity<Map> dup = rest.postForEntity(base + "/api/agents",
                body("build-runner-01", "PM"), Map.class);
        eq("校验-同名重复注册400", dup.getStatusCode(), HttpStatus.BAD_REQUEST);
        eq("校验-同名错误字段", dup.getBody().get("field"), "name");

        /* ---------- ⑪ 查询不存在 ---------- */
        ResponseEntity<Map> notFound = rest.getForEntity(base + "/api/agents/AGT-9999", Map.class);
        eq("查询不存在404", notFound.getStatusCode(), HttpStatus.NOT_FOUND);
        eq("查询不存在错误码", notFound.getBody().get("error"), "NOT_FOUND");

        /* ---------- ⑫ 审计哈希链 ---------- */
        AuditStore audit = springCtx.getBean(HashChainAuditStore.class);
        int records = audit.all().size();
        check("审计-写操作已落链(" + records + " 条)", records >= 10);
        check("审计-哈希链校验通过", audit.verifyChain());
        check("审计-包含注册表工具调用", audit.all().stream()
                .anyMatch(r -> r.payload != null && r.payload.contains("agents.")));

        /* ---------- ⑬ 共享注册表 + 白名单隔离 ---------- */
        SkillRegistry registry = springCtx.getBean(SkillRegistry.class);
        check("共享注册表-注册表工具已注册", registry.contains("agents.registerAgent"));
        check("共享注册表-业务工具仍在", registry.contains("restartService"));

        int policyBeans = springCtx.getBeanNamesForType(
                com.towndemontower.core.mode.ModeToolPolicy.class).length;
        eq("模式白名单-容器内仅一个策略 Bean", policyBeans, 1);
        com.towndemontower.core.mode.ModeToolPolicy policy =
                springCtx.getBean(com.towndemontower.core.mode.ModeToolPolicy.class);
        check("模式白名单-主链不含注册表工具",
                !policy.allowedTools(com.towndemontower.core.mode.AgentMode.DEV_EXECUTION)
                        .contains("agents.registerAgent"));
        check("模式白名单-主链仍含业务工具",
                policy.allowedTools(com.towndemontower.core.mode.AgentMode.DEV_EXECUTION)
                        .contains("restartService"));

        /* ---------- ⑭ 花名册页面（V2.1 起提供；此前这三条断言的是 404） ---------- */
        String rosterHtml = rest.getForObject(base + "/agents/index.html", String.class);
        // 断言用 ASCII 标记：静态资源响应的 Content-Type 不带 charset，客户端按 ISO-8859-1 解码，
        // 中文会变乱码——这不是页面问题，但会让中文断言假失败。
        check("页面-花名册可访问且内容正确",
                rosterHtml != null && rosterHtml.contains("/api/agents") && rosterHtml.length() > 4000);
        eq("页面-根路径进入花名册", rest.getForEntity(base + "/", String.class)
                .getStatusCode(), HttpStatus.OK);
        eq("页面-短地址进入花名册", rest.getForEntity(base + "/agents/", String.class)
                .getStatusCode(), HttpStatus.OK);
    }

    /* ---------------- 辅助 ---------------- */

    static Map<String, Object> body(String name, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("role", role);
        return m;
    }

    static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
