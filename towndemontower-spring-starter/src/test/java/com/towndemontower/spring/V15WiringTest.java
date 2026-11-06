package com.towndemontower.spring;

import com.towndemontower.core.approval.ApprovalCenter;
import com.towndemontower.core.approval.ApprovalStatus;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.context.ContextBuilder;
import com.towndemontower.core.context.PipelineState;
import com.towndemontower.core.context.PipelineStateStore;
import com.towndemontower.core.context.PromptAssembly;
import com.towndemontower.core.contract.RiskLevel;
import com.towndemontower.core.decorator.GovernancePass;
import com.towndemontower.core.metrics.GovernanceMetrics;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.mode.ModeEvent;
import com.towndemontower.core.mode.ModeTransitionInvalidException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.Set;

/**
 * [starter/test] V1.5 接线验证：模式会话、指标、流水线状态、审批中心、上下文构建是否真的
 * 被自动配置接上，以及"模式不再由调用方指定"是否成立。
 *
 * <p>为什么单独一套：这些能力都在 core 里有单测（{@code V15HardeningTest}），但
 * {@code docs/19} 指出的问题恰恰是"类写了、没接线"。**只测 core 等于没测接线**，
 * 这里全部通过自动配置拿 Bean，走真实 AOP。</p>
 */
public class V15WiringTest {

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

        @AgentSkill(name = "deployService", risk = "R2", requiredPerms = {"ops:deploy"},
                requiredArgs = {"target", "version"}, confidence = 1.0)
        public String deploy(String target, String version) {
            GovernancePass.require("deployService");
            return "deployed:" + target;
        }
    }

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties("towndemontower.permissions-bootstrap=fs:write,ops:deploy")
                .run();

        try {
            wiring(ctx);
            modeStateMachine(ctx);
            modeNotFromConfig(ctx);
            metricsAndState(ctx);
            contextBuilder(ctx);
            approvalCenter(ctx);
            integrityView(ctx);
        } finally {
            ctx.close();
        }

        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- ① 自动配置真的把 V1.5 组件装上了 ---------- */
    static void wiring(ConfigurableApplicationContext ctx) {
        check("装配-模式会话", ctx.getBean(GovernedModeSession.class) != null);
        check("装配-指标", ctx.getBean(GovernanceMetrics.class) != null);
        check("装配-流水线状态", ctx.getBean(PipelineStateStore.class) != null);
        check("装配-审批中心", ctx.getBean(ApprovalCenter.class) != null);
        check("装配-上下文构建", ctx.getBean(ContextBuilder.class) != null);
        check("装配-默认模式为执行态",
                ctx.getBean(GovernedModeSession.class).current() == AgentMode.DEV_EXECUTION);
    }

    /* ---------- ② 模式只能由状态机推进，非法转换被拒且留痕 ---------- */
    static void modeStateMachine(ConfigurableApplicationContext ctx) {
        GovernedModeSession session = ctx.getBean(GovernedModeSession.class);
        AuditStore audit = ctx.getBean(AuditStore.class);
        GovernanceMetrics metrics = ctx.getBean(GovernanceMetrics.class);
        int before = audit.all().size();

        boolean rejected = false;
        try {
            session.transition(ModeEvent.PLAN_APPROVED); // 执行态 + PLAN_APPROVED 不合法
        } catch (ModeTransitionInvalidException e) {
            rejected = true;
        }
        check("模式-非法转换被拒", rejected);
        check("模式-非法转换留痕", audit.all().size() > before
                && audit.all().get(audit.all().size() - 1).action.equals("MODE_TRANSITION_DENIED"));
        check("模式-非法转换计数", metrics.get("MODE_TRANSITION_INVALID") == 1);

        session.transition(ModeEvent.ENTER_AUDIT);
        check("模式-执行态进审计态", session.current() == AgentMode.AUDIT_REVIEWING);
        session.transition(ModeEvent.REPLAN);
        check("模式-审计态回规划态", session.current() == AgentMode.ARCHITECT_PLANNING);
        session.transition(ModeEvent.PLAN_APPROVED);
        check("模式-规划态获批进执行态", session.current() == AgentMode.DEV_EXECUTION);

        // 状态机没有 AUDIT_REVIEWING -> DEV_EXECUTION 直连，必须 REPLAN
        session.transition(ModeEvent.ENTER_AUDIT);
        boolean directRejected = false;
        try {
            session.transition(ModeEvent.PLAN_APPROVED);
        } catch (ModeTransitionInvalidException e) {
            directRejected = true;
        }
        check("模式-审计态不可直连执行态", directRejected);
        session.transition(ModeEvent.REPLAN);
        session.transition(ModeEvent.PLAN_APPROVED);
        check("模式-回到执行态", session.current() == AgentMode.DEV_EXECUTION);
    }

    /* ---------- ③ 改配置不再能改模式（只留痕） ---------- */
    static void modeNotFromConfig(ConfigurableApplicationContext ctx) {
        TownDemontowerProperties props = ctx.getBean(TownDemontowerProperties.class);
        GovernanceMetrics metrics = ctx.getBean(GovernanceMetrics.class);
        Tools tools = ctx.getBean(Tools.class);

        props.setMode("ARCHITECT_PLANNING"); // 运维的"切模式"尝试
        String out = tools.write("/tmp/a.txt", "x");
        check("模式-配置不能改行为", out.startsWith("wrote:"));
        check("模式-覆盖尝试被记录", metrics.get(GovernanceMetrics.MODE_OVERRIDE_IGNORED) >= 1);

        props.setMode("DEV_EXECUTION"); // 复原，避免影响后续用例
    }

    /* ---------- ④ 指标与流水线状态被真实写入 ---------- */
    static void metricsAndState(ConfigurableApplicationContext ctx) {
        Tools tools = ctx.getBean(Tools.class);
        GovernanceMetrics metrics = ctx.getBean(GovernanceMetrics.class);
        PipelineStateStore store = ctx.getBean(PipelineStateStore.class);
        TownDemontowerProperties props = ctx.getBean(TownDemontowerProperties.class);

        long before = metrics.get(GovernanceMetrics.TOTAL);
        tools.write("/tmp/b.txt", "y");
        check("指标-总调用数增长", metrics.get(GovernanceMetrics.TOTAL) > before);
        check("指标-成功不计入拒绝", metrics.get(GovernanceMetrics.DENIED) == 0);

        PipelineState st = store.load(props.getTaskId());
        check("状态-已落盘", st != null);
        check("状态-记录模式", st != null && st.mode == AgentMode.DEV_EXECUTION);
        check("状态-记录裁决", st != null && "OK".equals(st.lastDecision));
        check("状态-记录更新时间", st != null && st.updatedAt != null);
    }

    /* ---------- ⑤ 上下文构建：白名单落进提示词 + 前缀可缓存 ---------- */
    static void contextBuilder(ConfigurableApplicationContext ctx) {
        ContextBuilder cb = ctx.getBean(ContextBuilder.class);
        GovernanceMetrics metrics = ctx.getBean(GovernanceMetrics.class);
        Set<String> devTools = Set.of("writeFile", "deployService");

        PromptAssembly first = cb.build(AgentMode.DEV_EXECUTION, devTools, "任务A", null, RiskLevel.R1);
        PromptAssembly second = cb.build(AgentMode.DEV_EXECUTION, devTools, "任务B", null, RiskLevel.R0);

        check("上下文-工具清单即白名单", first.tools.contains("writeFile") && first.tools.size() == 2);
        check("上下文-前缀稳定", first.prefix.equals(second.prefix));
        check("上下文-第二次命中缓存", second.cacheHit && !first.cacheHit);
        check("上下文-命中率可计算", metrics.cacheHitRate() > 0);
        check("上下文-前缀不含任务内容", !first.prefix.contains("任务A"));
        check("上下文-路由已装配", first.model != null && first.routeRecord() != null);
    }

    /* ---------- ⑥ R2 进审批中心，可查、禁自批、可批 ---------- */
    static void approvalCenter(ConfigurableApplicationContext ctx) {
        Tools tools = ctx.getBean(Tools.class);
        ApprovalCenter center = ctx.getBean(ApprovalCenter.class);
        TownDemontowerProperties props = ctx.getBean(TownDemontowerProperties.class);

        String ticketId = null;
        try {
            tools.deploy("svc", "1.0");
            check("审批-R2应被挂起", false);
        } catch (GovernanceDeniedException e) {
            ticketId = e.getApprovalTicketId();
            check("审批-返回待审批", "PENDING_APPROVAL".equals(e.getCode()));
            check("审批-带单号", ticketId != null && !ticketId.isBlank());
        }

        check("审批-单子可查", center.find(ticketId).isPresent()
                && center.statusOf(ticketId) == ApprovalStatus.PENDING);
        check("审批-进入待办列表", center.list(ApprovalStatus.PENDING).size() >= 1);
        check("审批-禁自批", !center.approve(ticketId, props.getAgentId(), "自批", null));
        check("审批-他人可批", center.approve(ticketId, "human-1", "同意", null)
                && center.statusOf(ticketId) == ApprovalStatus.APPROVED);
    }

    /* ---------- ⑦ 观测端点内容（端点默认关闭，但视图可直测） ---------- */
    static void integrityView(ConfigurableApplicationContext ctx) {
        TownDemontowerProperties props = ctx.getBean(TownDemontowerProperties.class);
        props.setMetricsToken("test-token"); // 观测端点必须带令牌（V1.6）
        GovernanceMetricsController c = new GovernanceMetricsController(
                ctx.getBean(GovernanceMetrics.class),
                ctx.getBean(ApprovalCenter.class),
                ctx.getBean(AuditStore.class),
                props,
                ctx.getBean(GovernanceIntegrityChecker.class));

        boolean unauthorized = false;
        try {
            c.metrics("wrong-token");
        } catch (org.springframework.web.server.ResponseStatusException e) {
            unauthorized = e.getStatusCode().value() == 401;
        }
        check("端点-令牌错误返回401", unauthorized);

        Map<String, Object> metrics = c.metrics("test-token");
        check("端点-含计数器", metrics.containsKey("counters"));
        check("端点-含拒绝率", metrics.containsKey("denialRate"));

        Map<String, Object> integrity = c.integrity("test-token");
        check("端点-审计链有效", Boolean.TRUE.equals(integrity.get("auditChainValid")));
        @SuppressWarnings("unchecked")
        java.util.List<String> findings = (java.util.List<String>) integrity.get("findings");
        // V1.6 起默认关闭注解自授权；V1.7 起门禁密钥只认环境变量，
        // 未配置时报 GATE_KEY_MISSING（配了但不合格报 GATE_KEY_WEAK）
        boolean gateKeyReported = findings.contains("GATE_KEY_MISSING")
                || findings.contains("GATE_KEY_WEAK")
                || com.towndemontower.core.gate.GateScriptRunner.keyAvailableInEnv();
        check("端点-自检能识别默认风险", findings.contains("AGENT_ID_DEFAULT") && gateKeyReported);
        check("端点-无 CRITICAL 时视为合规", Boolean.TRUE.equals(integrity.get("compliant")));
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
