package com.towndemontower.examples.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

/**
 * 可直接运行的分步示例。默认起一个本地假服务（{@link DemoServer}）演示全部中间件；
 * 也可以接真实服务：
 *
 * <pre>
 *   java ... Demo http://127.0.0.1:8080 &lt;你的 metrics-token&gt;
 * </pre>
 *
 * 接真实服务时只跑只读场景（列表 / 过滤 / 审计 / 链校验），避免在别人环境里做审批动作。
 */
public final class Demo {

    public static void main(String[] args) throws Exception {
        String realBaseUrl = args.length > 0 ? args[0] : null;
        String token = args.length > 1 ? args[1] : DemoServer.TOKEN;

        DemoServer server = realBaseUrl == null ? DemoServer.start() : null;
        String baseUrl = realBaseUrl != null ? realBaseUrl : server.baseUrl();

        try {
            System.out.println("目标服务：" + baseUrl + (server != null ? "（本地假服务）" : "（真实服务）"));
            System.out.println();

            // ---- 步骤 2：构造客户端并注册中间件 -------------------------------------
            // order 由小到大 = 由外到内；这里的顺序值得记：onError 在最外层才能兜住所有异常，
            // retry 包住"会失败的那一层"，logging 放在 retry 内层才能看到"每一次尝试"，
            // unifiedEnvelope 在最内层才能把 HTTP/业务码统一成 ApiException 交给 retry 判断。
            ApiClient client = new ApiClient(baseUrl, new ObjectMapper(),
                    Duration.ofSeconds(5))
                    .add(0, Middlewares.onError(e -> System.out.println("  [onError] 统一捕获 -> " + e.describe())))
                    .add(1, Middlewares.retry(3, Duration.ofMillis(150)))
                    .add(2, Middlewares.logging(System.out))
                    .add(3, Middlewares.token(token))
                    .add(4, Middlewares.unifiedEnvelope());

            section("0. 中间件注册结果（数字是 order，越小越外层）");
            System.out.println("  " + client.chainNames());

            // ---- 步骤 4：常见场景 ------------------------------------------------
            section("1. GET 列表：一次标准调用长什么样");
            JsonNode plans = client.execute(ApiRequest.get("/towndemontower/admin/plans")).data();
            System.out.println("  total = " + plans.get("total").asInt()
                    + "，第一条 id = " + plans.get("items").get(0).get("id").asText());

            section("2. 查询参数：参数会做 UTF-8 编码，null 值自动丢弃");
            JsonNode pending = client.execute(
                    ApiRequest.builder(ApiRequest.Method.GET, "/towndemontower/admin/approvals")
                            .query("status", "PENDING")
                            .query("keyword", null)   // 自动丢弃
                            .build()).data();
            System.out.println("  待审批 " + pending.get("total").asInt() + " 条");

            section("3. POST 决策：成功走同一套取数方式");
            JsonNode approved = client.execute(ApiRequest.post(
                    "/towndemontower/admin/plans/plan-1/approve", "{\"approver\":\"alice\"}")).data();
            System.out.println("  plan-1 状态 = " + approved.get("status").asText());

            section("4. 统一异常：HTTP 409 + 业务码 PLAN_REJECTED -> ApiException（不重试）");
            try {
                client.execute(ApiRequest.post(
                        "/towndemontower/admin/plans/plan-2/approve", "{\"approver\":\"alice\"}"));
                System.out.println("  [意外] 没有抛异常");
            } catch (ApiException e) {
                System.out.println("  捕获到 status=" + e.status() + " error=" + e.error()
                        + " message=" + e.getMessage() + " retryable=" + e.retryable());
            }

            section("5. 请求头自动添加的反例：不挂 token 中间件 -> 401");
            ApiClient noToken = new ApiClient(baseUrl, new ObjectMapper(),
                    Duration.ofSeconds(5))
                    .add(0, Middlewares.onError(e -> System.out.println("  [onError] -> " + e.describe())))
                    .add(1, Middlewares.retry(3, Duration.ofMillis(150)))
                    .add(2, Middlewares.unifiedEnvelope());
            try {
                noToken.execute(ApiRequest.get("/towndemontower/admin/plans"));
            } catch (ApiException e) {
                System.out.println("  结果：status=" + e.status() + "，retryable=" + e.retryable()
                        + "（4xx 不重试，所以只尝试了 1 次）");
            }

            if (server != null) {
                section("6. 自动重试：503 -> 503 -> 200（指数退避 + 抖动）");
                JsonNode flaky = client.execute(ApiRequest.get("/towndemontower/admin/flaky")).data();
                System.out.println("  最终成功，服务端记录的第 " + flaky.get("attempt").asInt() + " 次调用");

                section("7. 读超时：客户端 300ms，服务端睡 700ms -> TIMEOUT");
                try {
                    client.execute(ApiRequest.builder(ApiRequest.Method.GET, "/towndemontower/admin/slow")
                            .timeout(Duration.ofMillis(300))
                            .build());
                } catch (ApiException e) {
                    System.out.println("  结果：" + e.error() + "（超时属于网络错误，会被重试）");
                }
            } else {
                section("6. 接真实服务时跳过了重试/超时演示（需要 /flaky、/slow 两个演示端点）");
                System.out.println("  审计链校验：valid="
                        + client.execute(ApiRequest.get("/towndemontower/admin/audit/verify")).data().get("valid").asBoolean());
            }

            section("8. 组合中间件：任意请求头注入（只补不覆盖）");
            ApiClient withExtra = new ApiClient(baseUrl)
                    .add(0, Middlewares.headers(Map.of("X-Request-Id", "req-001", "X-Client", "demo")))
                    .add(1, Middlewares.token(token))
                    .add(2, Middlewares.unifiedEnvelope());
            System.out.println("  头注入成功，调用返回 status="
                    + withExtra.execute(ApiRequest.get("/towndemontower/admin/plans")).status());

            System.out.println();
            System.out.println("全部场景执行完毕。");
        } finally {
            if (server != null) server.close();
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }
}

