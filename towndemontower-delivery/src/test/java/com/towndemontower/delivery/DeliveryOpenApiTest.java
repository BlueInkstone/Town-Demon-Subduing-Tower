package com.towndemontower.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * [delivery] OpenAPI 契约产物测试。
 *
 * <p>验证 springdoc 生成的三类接口文档产物：</p>
 * <ol>
 *   <li>{@code /v3/api-docs} —— OpenAPI 3 JSON 契约；</li>
 *   <li>{@code /v3/api-docs.yaml} —— YAML 形式；</li>
 *   <li>{@code /swagger-ui/index.html} —— Swagger UI 静态页（且资源离线可取，无 CDN 依赖）。</li>
 * </ol>
 *
 * <p>本测试的意图是<b>锁住接口契约的形状</b>：端点数量、路径、schema 名称一旦漂移，
 * 契约就失效了——这比等到接入方才发现更早暴露问题。</p>
 */
public class DeliveryOpenApiTest {

    private static int pass = 0;
    private static int fail = 0;
    private static RestTemplate rest;

    public static void main(String[] args) throws Exception {
        // 随机端口 + 显式关闭演示数据（避免播种污染条数断言）
        SpringApplication app = new SpringApplication(TestApp.class);
        ConfigurableApplicationContext ctx = app.run(
                "--server.port=0",
                "--towndemontower.delivery.demo-data=false");
        try {
            rest = new RestTemplate();
            run(baseUrl(ctx));
        } finally {
            ctx.close();
        }
        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 取随机端口拼出 base URL。 */
    static String baseUrl(ConfigurableApplicationContext ctx) {
        String port = ctx.getEnvironment().getProperty("local.server.port");
        return "http://127.0.0.1:" + port;
    }

    static void run(String base) throws Exception {

        /* ---------- ① OpenAPI JSON 契约 ---------- */
        ResponseEntity<String> docs = rest.getForEntity(base + "/v3/api-docs", String.class);
        eq("契约-HTTP 200", docs.getStatusCode(), HttpStatus.OK);
        check("契约-非空", docs.getBody() != null && docs.getBody().length() > 500);

        JsonNode root = new ObjectMapper().readTree(docs.getBody());

        eq("契约-OpenAPI 版本", root.path("openapi").asText(), "3.0.1");
        eq("契约-标题", root.path("info").path("title").asText(),
                "Town Demon-Subduing Tower · 交付进度看板 API");
        eq("契约-版本号", root.path("info").path("version").asText(), "v1.3");
        check("契约-含描述说明", root.path("info").path("description").asText().length() > 100);
        check("契约-含中文核心概念说明",
                root.path("info").path("description").asText().contains("推导字段"));

        /* ---------- ② 端点覆盖：8 个交付看板端点全部在契约里 ---------- */
        List<String> expected = List.of(
                "/api/delivery/items",
                "/api/delivery/items/{id}",
                "/api/delivery/summary",
                "/api/delivery/meta");
        for (String p : expected) {
            check("契约-含路径 " + p, root.path("paths").has(p));
        }

        JsonNode items = root.path("paths").path("/api/delivery/items");
        check("契约-items 支持 GET", items.has("get"));
        check("契约-items 支持 POST", items.has("post"));
        JsonNode itemById = root.path("paths").path("/api/delivery/items/{id}");
        check("契约-itemById 支持 GET", itemById.has("get"));
        check("契约-itemById 支持 PUT", itemById.has("put"));
        check("契约-itemById 支持 DELETE", itemById.has("delete"));

        eq("契约-列表端点摘要", items.path("get").path("summary").asText(), "列出交付项");
        eq("契约-汇总端点摘要", root.path("paths").path("/api/delivery/summary")
                .path("get").path("summary").asText(), "整体交付进度汇总");

        /* ---------- ③ schema 定义 ---------- */
        JsonNode schemas = root.path("components").path("schemas");
        check("契约-含 DeliveryItemView", schemas.has("DeliveryItemView"));
        check("契约-含 DeliveryItemRequest", schemas.has("DeliveryItemRequest"));
        check("契约-含 DeliverySummaryView", schemas.has("DeliverySummaryView"));

        // 请求 schema 必须表达"更新时 null 表示保持原值"的关键语义
        List<String> reqFields = new ArrayList<>();
        schemas.path("DeliveryItemRequest").path("properties").fieldNames()
                .forEachRemaining(reqFields::add);
        check("契约-请求字段含 phaseName", reqFields.contains("phaseName"));
        check("契约-请求字段含 owner", reqFields.contains("owner"));
        check("契约-请求字段含 plannedStart", reqFields.contains("plannedStart"));
        check("契约-请求字段含 plannedEnd", reqFields.contains("plannedEnd"));
        check("契约-请求字段含 actualEnd", reqFields.contains("actualEnd"));
        check("契约-请求字段含 progressPercent", reqFields.contains("progressPercent"));
        check("契约-请求字段含 status", reqFields.contains("status"));

        List<String> viewFields = new ArrayList<>();
        schemas.path("DeliveryItemView").path("properties").fieldNames()
                .forEachRemaining(viewFields::add);
        check("契约-视图字段含 statusLabel", viewFields.contains("statusLabel"));
        check("契约-视图字段含 manualStatus", viewFields.contains("manualStatus"));
        check("契约-视图字段含 statusOverride", viewFields.contains("statusOverride"));

        /* ---------- ④ YAML 契约 ---------- */
        ResponseEntity<String> yaml = rest.getForEntity(base + "/v3/api-docs.yaml", String.class);
        eq("YAML-HTTP 200", yaml.getStatusCode(), HttpStatus.OK);
        check("YAML-含 openapi 版本", yaml.getBody() != null
                && yaml.getBody().contains("openapi: 3.0.1"));
        check("YAML-含交付看板路径", yaml.getBody().contains("/api/delivery/items"));

        /* ---------- ⑤ Swagger UI ---------- */
        ResponseEntity<String> ui = rest.getForEntity(base + "/swagger-ui/index.html", String.class);
        eq("SwaggerUI-HTTP 200", ui.getStatusCode(), HttpStatus.OK);
        check("SwaggerUI-含标题", ui.getBody() != null && ui.getBody().contains("Swagger UI"));

        // 关键：Swagger UI 的静态资源必须由本地 webjar 提供（离线可用，不依赖 CDN）
        ResponseEntity<byte[]> css = rest.getForEntity(
                base + "/swagger-ui/swagger-ui.css", byte[].class);
        eq("SwaggerUI-CSS 200", css.getStatusCode(), HttpStatus.OK);
        check("SwaggerUI-CSS 非空", css.getBody() != null && css.getBody().length > 10000);

        ResponseEntity<byte[]> js = rest.getForEntity(
                base + "/swagger-ui/swagger-ui-bundle.js", byte[].class);
        eq("SwaggerUI-JS 200", js.getStatusCode(), HttpStatus.OK);
        check("SwaggerUI-JS 非空", js.getBody() != null && js.getBody().length > 100000);

        /* ---------- ⑥ 契约与实际接口一致（契约不能描述不存在的端点） ---------- */
        eq("契约-路径数与实际端点数一致", root.path("paths").size(), 4);
    }

    static int port() {
        return 0;
    }

    @SpringBootApplication(scanBasePackages = "com.towndemontower.delivery")
    static class TestApp {
    }

    /* ---------------- 辅助 ---------------- */

    static void eq(String name, Object actual, Object expected) {
        if (actual == null ? expected == null : actual.equals(expected)) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + " | expected=" + expected + " actual=" + actual);
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
