package com.towndemontower.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.towndemontower.core.approval.ApprovalCenter;
import com.towndemontower.core.approval.PlanApprovalService;
import com.towndemontower.core.contract.ApprovalTicket;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.spring.admin.ApprovalAdminController;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [starter/test] V1.17 治理管理接口。
 *
 * <p>真实起 Servlet 容器 + 真实 HTTP 调用（不用 mock），验证路由、参数校验、
 * 统一响应格式、错误映射、鉴权、审批状态流转、审计查询、暂存区运维。
 * 远端用本地假收集端注入 503 → 200 故障。</p>
 */
public class V117AdminApiTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
    }

    static int pass = 0, fail = 0;
    static final String TOKEN = "admin-test-token";
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger anchorStatus = new AtomicInteger(503);
        collector.createContext("/anchor", ex -> {
            ex.sendResponseHeaders(anchorStatus.get(), -1);
            ex.close();
        });
        collector.start();

        String tag = Long.toString(System.nanoTime());
        String anchorUrl = "http://127.0.0.1:" + collector.getAddress().getPort() + "/anchor";

        try {
            // ---------- A. 默认不装配 ----------
            try (ConfigurableApplicationContext ctx = boot(WebApplicationType.NONE)) {
                check("默认-管理接口不装配",
                        ctx.getBeansOfType(ApprovalAdminController.class).isEmpty());
            }

            // ---------- B. 打开接口但未配令牌 → 403 ----------
            try (ConfigurableApplicationContext ctx = boot(WebApplicationType.SERVLET,
                    "towndemontower.admin-api-enabled=true",
                    "towndemontower.metrics-token=",
                    "server.port=0",
                    "towndemontower.tx.enabled=false")) {
                int port = port(ctx);
                Reply r = call(port, "GET", "/towndemontower/admin/plans", null, null);
                check("未配令牌-403", r.status == 403);
                check("未配令牌-错误码", "ADMIN_API_DISABLED".equals(r.text("error")));
            }

            // ---------- C. 完整功能 ----------
            try (ConfigurableApplicationContext ctx = boot(WebApplicationType.SERVLET,
                    "towndemontower.admin-api-enabled=true",
                    "towndemontower.metrics-token=" + TOKEN,
                    "server.port=0",
                    "towndemontower.tx.enabled=false",
                    "towndemontower.audit-storage=file",
                    "towndemontower.audit-file=target/v117-audit-" + tag + ".log",
                    "towndemontower.state-dir=target/v117-state-" + tag,
                    "towndemontower.approval-dir=target/v117-approvals-" + tag,
                    "towndemontower.audit-anchor-replicas=" + anchorUrl,
                    "towndemontower.audit-anchor-replica-required=false",
                    "towndemontower.audit-sync-max-attempts=1",
                    "towndemontower.audit-sync-retry-interval-ms=60000")) {
                int port = port(ctx);
                PlanApprovalService plans = ctx.getBean(PlanApprovalService.class);
                ApprovalCenter approvals = ctx.getBean(ApprovalCenter.class);
                AuditStore audit = ctx.getBean(AuditStore.class);

                // 鉴权
                check("无令牌-401", call(port, "GET", "/towndemontower/admin/plans", null, null).status == 401);
                check("错令牌-401", call(port, "GET", "/towndemontower/admin/plans", null, "wrong").status == 401);

                Reply list0 = call(port, "GET", "/towndemontower/admin/plans", null, TOKEN);
                check("鉴权通过-200", list0.status == 200);
                check("统一响应-成功 code=0", list0.json.get("code").asInt() == 0);
                check("统一响应-成功 error 为空", list0.json.get("error").isNull());
                check("计划列表-初始为空", list0.dataText("total").equals("0"));

                // 计划审批
                String planId = plans.request("agent-a", "task-1", "重构登录模块");
                check("计划列表-提交后可见", count(call(port, "GET", "/towndemontower/admin/plans", null, TOKEN)) == 1);
                check("计划列表-状态过滤 PENDING",
                        count(call(port, "GET", "/towndemontower/admin/plans?status=PENDING", null, TOKEN)) == 1);
                check("计划列表-状态过滤 APPROVED 为空",
                        count(call(port, "GET", "/towndemontower/admin/plans?status=APPROVED", null, TOKEN)) == 0);
                check("计划列表-非法状态 400",
                        call(port, "GET", "/towndemontower/admin/plans?status=WHAT", null, TOKEN).status == 400);

                Reply detail = call(port, "GET", "/towndemontower/admin/plans/" + planId, null, TOKEN);
                check("计划详情-id 一致", planId.equals(detail.dataText("id")));
                check("计划详情-含结构化计划", detail.json.get("data").get("plan").get("summary").asText()
                        .contains("重构登录模块"));
                check("计划详情-未知单 404",
                        call(port, "GET", "/towndemontower/admin/plans/P999-1", null, TOKEN).status == 404);

                Reply self = post(port, "/towndemontower/admin/plans/" + planId + "/approve",
                        "{\"approver\":\"agent-a\"}", TOKEN);
                check("计划-自批 400", self.status == 400);
                check("计划-自批错误码", "SELF_APPROVAL".equals(self.text("error")));
                Reply noApprover = post(port, "/towndemontower/admin/plans/" + planId + "/approve",
                        "{\"note\":\"ok\"}", TOKEN);
                check("计划-缺审批人 400", noApprover.status == 400);
                check("计划-缺审批人错误码", "VALIDATION_FAILED".equals(noApprover.text("error")));

                Reply ok = post(port, "/towndemontower/admin/plans/" + planId + "/approve",
                        "{\"approver\":\"reviewer\",\"note\":\"同意\"}", TOKEN);
                check("计划-批准成功", ok.status == 200 && "APPROVED".equals(ok.dataText("status")));
                Reply again = post(port, "/towndemontower/admin/plans/" + planId + "/approve",
                        "{\"approver\":\"reviewer\"}", TOKEN);
                check("计划-重复批准幂等", again.status == 200 && "APPROVED".equals(again.dataText("status")));

                String plan2 = plans.request("agent-b", "task-2", "扩容数据库");
                Reply rejected = post(port, "/towndemontower/admin/plans/" + plan2 + "/reject",
                        "{\"approver\":\"reviewer\",\"note\":\"风险过高\"}", TOKEN);
                check("计划-否决成功", rejected.status == 200 && "REJECTED".equals(rejected.dataText("status")));
                Reply late = post(port, "/towndemontower/admin/plans/" + plan2 + "/approve",
                        "{\"approver\":\"reviewer\"}", TOKEN);
                check("计划-已否决不可补批 409", late.status == 409);
                check("计划-已否决错误码", "PLAN_REJECTED".equals(late.text("error")));

                // R3 审批单
                String ticket = approvals.create(new ApprovalTicket("T-" + tag, "task-9",
                        "deployService", "R3", "影响线上", "可回滚"), "requester", Instant.now());
                Reply ticketList = call(port, "GET", "/towndemontower/admin/approvals?status=PENDING",
                        null, TOKEN);
                check("审批单-列表可见", count(ticketList) == 1);
                Reply ticketDetail = call(port, "GET", "/towndemontower/admin/approvals/" + ticket,
                        null, TOKEN);
                check("审批单-详情字段完整", "deployService".equals(ticketDetail.dataText("tool"))
                        && "R3".equals(ticketDetail.dataText("riskLevel")));
                check("审批单-自批 400",
                        post(port, "/towndemontower/admin/approvals/" + ticket + "/approve",
                                "{\"approver\":\"requester\"}", TOKEN).status == 400);
                Reply approved = post(port, "/towndemontower/admin/approvals/" + ticket + "/approve",
                        "{\"approver\":\"reviewer\",\"comment\":\"同意\"}", TOKEN);
                check("审批单-批准成功", approved.status == 200
                        && "APPROVED".equals(approved.dataText("status")));
                check("审批单-重复决策 409",
                        post(port, "/towndemontower/admin/approvals/" + ticket + "/approve",
                                "{\"approver\":\"reviewer\"}", TOKEN).status == 409);
                String ticket2 = approvals.create(new ApprovalTicket("T2-" + tag, "task-10",
                        "deleteService", "R3", "影响线上", "可回滚"), "requester", Instant.now());
                Reply denied = post(port, "/towndemontower/admin/approvals/" + ticket2 + "/deny",
                        "{\"approver\":\"reviewer\",\"comment\":\"不允许\"}", TOKEN);
                check("审批单-驳回成功", denied.status == 200
                        && "DENIED".equals(denied.dataText("status")));
                check("审批单-未知单 404",
                        call(port, "GET", "/towndemontower/admin/approvals/NOPE", null, TOKEN).status == 404);

                // 审计查询
                audit.appendAuto("ADMIN_API_TEST", "probe-" + tag);
                Reply auditList = call(port,
                        "GET", "/towndemontower/admin/audit?action=ADMIN_API_TEST", null, TOKEN);
                check("审计-动作过滤命中", count(auditList) == 1);
                check("审计-返回 seq 与 hash",
                        auditList.json.get("data").get("items").get(0).get("seq").asLong() > 0
                                && !auditList.json.get("data").get("items").get(0).get("hash").isNull());
                Reply verify = call(port, "GET", "/towndemontower/admin/audit/verify", null, TOKEN);
                check("审计-链校验通过", verify.json.get("data").get("valid").asBoolean());
                check("审计-记录数大于 0", verify.json.get("data").get("records").asInt() > 0);
                check("审计-limit 非法 400",
                        call(port, "GET", "/towndemontower/admin/audit?limit=0", null, TOKEN).status == 400);
                Reply beyond = call(port, "GET", "/towndemontower/admin/audit?offset=99999", null, TOKEN);
                check("审计-offset 越界返回空列表",
                        beyond.json.get("data").get("items").size() == 0);

                // 暂存区运维：远端 503 → 待补传
                Reply spool = call(port, "GET", "/towndemontower/admin/sync-spool", null, TOKEN);
                check("暂存-统计可见", spool.status == 200
                        && spool.json.get("data").get("stats").get("pending").asInt() >= 1);
                check("暂存-待补传列表非空",
                        spool.json.get("data").get("pendingTotal").asInt() >= 1);
                check("暂存-条目含副本名", !spool.json.get("data").get("items").get(0)
                        .get("replica").isNull());
                check("暂存-清理 200",
                        post(port, "/towndemontower/admin/sync-spool/cleanup", "{}", TOKEN).status == 200);

                // 远端恢复 → 补传成功并删除本地记录
                anchorStatus.set(200);
                Reply retried = post(port, "/towndemontower/admin/sync-spool/retry", "{}", TOKEN);
                check("暂存-远端恢复后补传送达",
                        retried.json.get("data").get("delivered").asInt() >= 1);
                Reply after = call(port, "GET", "/towndemontower/admin/sync-spool", null, TOKEN);
                check("暂存-补传后待补传清空",
                        after.json.get("data").get("pendingTotal").asInt() == 0);

                // 远端再故障 + maxAttempts=1 → 进死信
                anchorStatus.set(503);
                audit.appendAuto("ADMIN_API_TEST", "probe2-" + tag);
                Reply deadLetter = post(port, "/towndemontower/admin/sync-spool/retry", "{}", TOKEN);
                check("暂存-达上限进死信",
                        deadLetter.json.get("data").get("deadLettered").asInt() >= 1);
                Reply dead = call(port, "GET", "/towndemontower/admin/sync-spool/dead", null, TOKEN);
                check("暂存-死信列表可见",
                        dead.json.get("data").get("deadTotal").asInt() >= 1);
                check("暂存-死信保留原始快照",
                        dead.json.get("data").get("items").get(0).get("snapshot").asText()
                                .contains("records="));

                // 统一错误结构（未知路由由框架处理，只校验状态码）
                Reply missing = call(port, "GET", "/towndemontower/admin/nope", null, TOKEN);
                check("未知路由-404", missing.status == 404);
                check("已知接口-统一错误体", noApprover.json.get("data").isNull()
                        && noApprover.json.has("message")
                        && noApprover.json.has("error"));
                Reply badPlan = post(port, "/towndemontower/admin/plans/P999-1/approve",
                        "{\"approver\":\"reviewer\"}", TOKEN);
                check("错误响应 code 与 HTTP 状态一致", badPlan.status == 404
                        && badPlan.json.get("code").asInt() == 404);
            }
        } finally {
            collector.stop(0);
        }

        System.out.println("\nRESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- HTTP 小工具 ---------- */

    record Reply(int status, JsonNode json) {
        /** 顶层字段（如 error / message / code）。 */
        String text(String field) {
            JsonNode n = json == null ? null : json.get(field);
            return n == null || n.isNull() ? null : n.asText();
        }

        /** 业务数据字段（统一放在 data 里）。 */
        String dataText(String field) {
            JsonNode n = json == null ? null : json.get("data");
            n = n == null ? null : n.get(field);
            return n == null || n.isNull() ? null : n.asText();
        }
    }

    static int count(Reply r) {
        return r.json.get("data").get("total").asInt();
    }

    static Reply post(int port, String path, String body, String token) throws Exception {
        return call(port, "POST", path, body, token);
    }

    static Reply call(int port, String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) b.header("X-Governance-Token", token);
        b.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> res = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node = res.body() == null || res.body().isBlank()
                ? null : JSON.readTree(res.body());
        return new Reply(res.statusCode(), node);
    }

    static int port(ConfigurableApplicationContext ctx) {
        return ((ServletWebServerApplicationContext) ctx).getWebServer().getPort();
    }

    /**
     * 用命令行参数而不是 {@code properties()}：测试资源里的
     * {@code application.properties} 写着 {@code spring.main.web-application-type=none}，
     * 默认属性压不过它；命令行参数优先级最高，才能真的起 Servlet 容器。
     */
    static ConfigurableApplicationContext boot(WebApplicationType web, String... props) {
        String[] args = new String[props.length + 1];
        args[0] = "--spring.main.web-application-type="
                + (web == WebApplicationType.SERVLET ? "servlet" : "none");
        for (int i = 0; i < props.length; i++) {
            args[i + 1] = "--" + props[i];
        }
        return new SpringApplicationBuilder(App.class).run(args);
    }
}
