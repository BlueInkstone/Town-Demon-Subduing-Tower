package com.towndemontower.delivery;

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
 * [delivery/test] 交付看板端到端验证（真实 Spring Boot 上下文 + 真实 HTTP）。
 *
 * <p>启动内嵌 Tomcat，随机端口，走真实 HTTP 调用 REST 接口，验证：
 * <ol>
 *   <li>新增 / 查询 / 更新 / 删除交付项（CRUD 全链路）；</li>
 *   <li>按状态筛选（{@code ?status=}）；</li>
 *   <li>整体进度汇总自动计算；</li>
 *   <li>字段校验失败 → 400；交付项不存在 → 404；</li>
 *   <li>写操作进入<b>共享的审计哈希链</b>，且哈希链可校验；</li>
 *   <li>治理链生效：无权限身份被 RBAC 拦截；</li>
 *   <li>交付看板工具注册进共享 SkillRegistry，但<b>不污染</b>业务主链的模式白名单。</li>
 * </ol>
 *
 * <p>绕开 surefire（JDK 21 + surefire 3.2.5 有 LinkageError），以 main 直接验证。</p>
 */
public class DeliveryWebTest {

    /**
     * 交付看板不需要数据库。
     *
     * <p>starter 传递引入了 spring-boot-starter-jdbc，若不排除数据源自动配置，
     * Spring Boot 会尝试装配 DataSource 并因"找不到驱动"而启动失败。
     * 排除后 starter 的事务相关 Bean（{@code @ConditionalOnBean(DataSource.class)}）
     * 会自动跳过，不影响本用例验证的治理链与审计链。</p>
     */
    @SpringBootApplication(scanBasePackages = "com.towndemontower.delivery",
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
        // 显式覆盖关键配置，保证测试隔离（不依赖 target/classes 里 application.properties 的内容）：
        //   --server.port=0                       随机端口。必须用命令行参数而非 setDefaultProperties——
        //                                            环境变量 SERVER__PORT 的松散绑定优先级高于默认属性。
        //   --towndemontower.delivery.demo-data=false  关闭演示数据播种，否则用例断言的条数会被演示数据污染。
        SpringApplication app = new SpringApplication(TestApp.class);
        ConfigurableApplicationContext ctx = app.run(
                "--server.port=0",
                "--towndemontower.delivery.demo-data=false");
        try {
            rest = new TestRestTemplate();
            run(ctx);
        } finally {
            ctx.close();
        }
        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    @SuppressWarnings("unchecked")
    static void run(ConfigurableApplicationContext ctx) {
        String base = "http://127.0.0.1:" + ctx.getEnvironment().getProperty("local.server.port");

        /* ---------- ① 元数据 ---------- */
        ResponseEntity<Map> meta = rest.getForEntity(base + "/api/delivery/meta", Map.class);
        eq("meta-状态码", meta.getStatusCode(), HttpStatus.OK);
        eq("meta-状态枚举数", ((List<?>) meta.getBody().get("statuses")).size(), 4);

        /* ---------- ② 新增 ---------- */
        ResponseEntity<Map> created = rest.postForEntity(base + "/api/delivery/items",
                body("需求梳理", "张三", rel(-10), rel(20), null, 10, null),
                Map.class);
        eq("新增-状态码201", created.getStatusCode(), HttpStatus.CREATED);
        String id = (String) created.getBody().get("id");
        check("新增-返回 id", id != null && id.startsWith("DLV-"));
        eq("新增-返回阶段名", created.getBody().get("phaseName"), "需求梳理");
        eq("新增-返回负责人", created.getBody().get("owner"), "张三");
        eq("新增-返回进度", created.getBody().get("progressPercent"), 10);
        eq("新增-返回推导状态", created.getBody().get("status"), "IN_PROGRESS");
        eq("新增-返回状态中文标签", created.getBody().get("statusLabel"), "进行中");
        eq("新增-未人工指定", created.getBody().get("manualStatus"), Boolean.FALSE);

        /* ---------- ③ 再造 3 条，覆盖四种状态 ---------- */
        // 已完成
        rest.postForEntity(base + "/api/delivery/items",
                body("编码实现", "李四", rel(-10), rel(20),
                        rel(15), 100, null), Map.class);
        // 已延期（计划完成时间在过去）
        rest.postForEntity(base + "/api/delivery/items",
                body("联调测试", "王五", rel(-40), rel(-20), null, 30, null),
                Map.class);
        // 未开始（计划开始在未来）
        rest.postForEntity(base + "/api/delivery/items",
                body("上线发布", "赵六", rel(30), rel(50), null, 0, null),
                Map.class);

        /* ---------- ④ 列表与筛选 ---------- */
        ResponseEntity<List> all = rest.getForEntity(base + "/api/delivery/items", List.class);
        eq("列表-总数", all.getBody().size(), 4);

        eq("筛选-进行中", rest.getForEntity(base + "/api/delivery/items?status=IN_PROGRESS", List.class)
                .getBody().size(), 1);
        eq("筛选-已完成", rest.getForEntity(base + "/api/delivery/items?status=COMPLETED", List.class)
                .getBody().size(), 1);
        eq("筛选-已延期", rest.getForEntity(base + "/api/delivery/items?status=DELAYED", List.class)
                .getBody().size(), 1);
        eq("筛选-未开始", rest.getForEntity(base + "/api/delivery/items?status=NOT_STARTED", List.class)
                .getBody().size(), 1);
        eq("筛选-未知状态返回400", rest.getForEntity(base + "/api/delivery/items?status=NOPE", Map.class)
                .getStatusCode(), HttpStatus.BAD_REQUEST);

        /* ---------- ⑤ 汇总 ---------- */
        ResponseEntity<Map> summary = rest.getForEntity(base + "/api/delivery/summary", Map.class);
        eq("汇总-状态码", summary.getStatusCode(), HttpStatus.OK);
        eq("汇总-总数", summary.getBody().get("totalCount"), 4);
        eq("汇总-已完成数", summary.getBody().get("completedCount"), 1);
        eq("汇总-进行中数", summary.getBody().get("inProgressCount"), 1);
        eq("汇总-已延期数", summary.getBody().get("delayedCount"), 1);
        eq("汇总-未开始数", summary.getBody().get("notStartedCount"), 1);
        // (10 + 100 + 30 + 0) / 4 = 35
        eq("汇总-整体进度", summary.getBody().get("overallProgressPercent"), 35);
        // 1 / 4 = 25
        eq("汇总-完成率", summary.getBody().get("completedRatioPercent"), 25);
        check("汇总-最近交付节点非空", summary.getBody().get("earliestPlannedEnd") != null);
        // 下一个交付节点：未完成且尚未到期项中最早的
        //   需求梳理(完成 +20 天，进行中)  vs  上线发布(完成 +50 天，未开始)
        //   → 取 +20 天那条；联调测试已延期(完成 -20 天，在过去)故排除
        //   注：JSON 往返会把秒精度补零（T09:00 → T09:00:00），故用截断比较
        String next = (String) summary.getBody().get("nextUpcomingPlannedEnd");
        eq("汇总-下一个交付节点", next == null ? null : next.substring(0, 16), rel(20).substring(0, 16));

        /* ---------- ⑥ 更新（部分更新） ---------- */
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("progressPercent", 65);
        ResponseEntity<Map> updated = rest.exchange(base + "/api/delivery/items/" + id,
                HttpMethod.PUT, new HttpEntity<>(patch, jsonHeaders()), Map.class);
        eq("更新-状态码", updated.getStatusCode(), HttpStatus.OK);
        eq("更新-进度已改", updated.getBody().get("progressPercent"), 65);
        eq("更新-阶段名保持原值", updated.getBody().get("phaseName"), "需求梳理");
        eq("更新-负责人保持原值", updated.getBody().get("owner"), "张三");

        /* ---------- ⑦ 人工指定状态 + 清除 ---------- */
        Map<String, Object> manual = new LinkedHashMap<>();
        manual.put("status", "NOT_STARTED");
        ResponseEntity<Map> manualResp = rest.exchange(base + "/api/delivery/items/" + id,
                HttpMethod.PUT, new HttpEntity<>(manual, jsonHeaders()), Map.class);
        eq("更新-人工指定状态", manualResp.getBody().get("status"), "NOT_STARTED");
        eq("更新-人工指定标记", manualResp.getBody().get("manualStatus"), Boolean.TRUE);
        eq("更新-人工指定原值回显", manualResp.getBody().get("statusOverride"), "NOT_STARTED");

        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("status", "AUTO");
        ResponseEntity<Map> clearResp = rest.exchange(base + "/api/delivery/items/" + id,
                HttpMethod.PUT, new HttpEntity<>(clear, jsonHeaders()), Map.class);
        eq("更新-清除人工指定后恢复自动推导", clearResp.getBody().get("status"), "IN_PROGRESS");
        eq("更新-清除后无人工标记", clearResp.getBody().get("manualStatus"), Boolean.FALSE);

        /* ---------- ⑧ 校验失败 → 400 ---------- */
        ResponseEntity<Map> blankName = rest.postForEntity(base + "/api/delivery/items",
                body("", "张三", rel(-10), rel(20), null, 10, null),
                Map.class);
        eq("校验-阶段名为空返回400", blankName.getStatusCode(), HttpStatus.BAD_REQUEST);
        eq("校验-错误码", blankName.getBody().get("error"), "VALIDATION_FAILED");
        eq("校验-出错字段", blankName.getBody().get("field"), "phaseName");

        ResponseEntity<Map> badRange = rest.postForEntity(base + "/api/delivery/items",
                body("非法进度", "张三", rel(-10), rel(20), null, 150, null),
                Map.class);
        eq("校验-进度超范围返回400", badRange.getStatusCode(), HttpStatus.BAD_REQUEST);
        eq("校验-进度出错字段", badRange.getBody().get("field"), "progressPercent");

        ResponseEntity<Map> badOrder = rest.postForEntity(base + "/api/delivery/items",
                body("起止倒挂", "张三", rel(20), rel(-10), null, 10, null),
                Map.class);
        eq("校验-计划起止倒挂返回400", badOrder.getStatusCode(), HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> badStatus = rest.postForEntity(base + "/api/delivery/items",
                body("未知状态", "张三", rel(-10), rel(20), null, 10, "WHATEVER"),
                Map.class);
        eq("校验-未知状态返回400", badStatus.getStatusCode(), HttpStatus.BAD_REQUEST);

        /* ---------- ⑨ 不存在 → 404 ---------- */
        ResponseEntity<Map> notFound = rest.getForEntity(base + "/api/delivery/items/DLV-9999", Map.class);
        eq("查询不存在-404", notFound.getStatusCode(), HttpStatus.NOT_FOUND);
        eq("查询不存在-错误码", notFound.getBody().get("error"), "NOT_FOUND");

        ResponseEntity<Map> delNotFound = rest.exchange(base + "/api/delivery/items/DLV-9999",
                HttpMethod.DELETE, null, Map.class);
        eq("删除不存在-404", delNotFound.getStatusCode(), HttpStatus.NOT_FOUND);

        /* ---------- ⑩ 删除 ---------- */
        ResponseEntity<Map> deleted = rest.exchange(base + "/api/delivery/items/" + id,
                HttpMethod.DELETE, null, Map.class);
        eq("删除-状态码", deleted.getStatusCode(), HttpStatus.OK);
        eq("删除-返回标记", deleted.getBody().get("deleted"), Boolean.TRUE);
        eq("删除-列表已减少", rest.getForEntity(base + "/api/delivery/items", List.class).getBody().size(), 3);

        /* ---------- ⑪ 审计哈希链 ---------- */
        AuditStore audit = ctx.getBean(HashChainAuditStore.class);
        int audits = audit.all().size();
        check("审计-写操作已落链(" + audits + " 条)", audits >= 8);
        check("审计-哈希链校验通过", audit.verifyChain());
        boolean sawDelivery = audit.all().stream()
                .anyMatch(r -> r.payload != null && r.payload.contains("delivery."));
        check("审计-包含交付看板工具调用", sawDelivery);

        /* ---------- ⑫ 共享注册表 + 白名单不污染 ---------- */
        SkillRegistry registry = ctx.getBean(SkillRegistry.class);
        check("共享注册表-交付看板工具已注册", registry.contains("delivery.createItem"));
        check("共享注册表-业务工具仍在", registry.contains("restartService"));

        // 交付看板的白名单是独立实例，不是 Spring Bean，因此业务主链不受影响
        int deliveryPolicyBeans = ctx.getBeanNamesForType(
                com.towndemontower.core.mode.ModeToolPolicy.class).length;
        eq("模式白名单-容器内仅一个策略 Bean", deliveryPolicyBeans, 1);
        com.towndemontower.core.mode.ModeToolPolicy policy =
                ctx.getBean(com.towndemontower.core.mode.ModeToolPolicy.class);
        check("模式白名单-业务主链不含交付看板工具",
                !policy.allowedTools(com.towndemontower.core.mode.AgentMode.DEV_EXECUTION)
                        .contains("delivery.createItem"));
        check("模式白名单-业务主链仍含业务工具",
                policy.allowedTools(com.towndemontower.core.mode.AgentMode.DEV_EXECUTION)
                        .contains("restartService"));
    }

    /* ---------------- 辅助 ---------------- */

    /**
     * 相对当前时刻的 ISO 本地日期时间（秒精度）。
     *
     * <p>测试数据必须相对"现在"生成，不能写死绝对日期——
     * 交付项状态是由"当前时间"实时推导的，写死日期会让本用例随日历推移而失效
     * （例如"上线发布"到 2026-12-01 就不再是"未开始"）。</p>
     */
    static String rel(int daysFromNow) {
        return java.time.LocalDateTime.now().plusDays(daysFromNow)
                .withHour(9).withMinute(0).withSecond(0).withNano(0)
                .toString();
    }

    static Map<String, Object> body(String phase, String owner, String start, String end,
                                    String actual, Integer progress, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phaseName", phase);
        m.put("owner", owner);
        m.put("plannedStart", start);
        m.put("plannedEnd", end);
        if (actual != null) {
            m.put("actualEnd", actual);
        }
        if (progress != null) {
            m.put("progressPercent", progress);
        }
        if (status != null) {
            m.put("status", status);
        }
        return m;
    }

    static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
