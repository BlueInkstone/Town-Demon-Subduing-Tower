package com.towndemontower.core;

import com.towndemontower.core.auth.ApiCallerRegistry;
import com.towndemontower.core.auth.CallerAgentBindings;
import com.towndemontower.core.auth.CallerContext;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.decorator.AuditDecorator;
import com.towndemontower.core.mode.AgentMode;

import java.util.List;

/**
 * [core/test] V1.9 / I3-b —— 凭据 → Agent 绑定内核。
 *
 * <p>验证五组不变量：</p>
 * <ol>
 *   <li>解析只认 {@code 调用者:AgentID}，非法条目被拒且<b>不整条回显</b>；</li>
 *   <li>空表 = 不启用（{@code strict()==false}），非空表 = 严格（{@code strict()==true}）；</li>
 *   <li>{@link CallerAgentBindings#agentFor} 未绑定返回 {@code null}，不做任何兜底；</li>
 *   <li>两个"孤儿方向"能被查出来（有绑定没令牌 / 有令牌没绑定）；</li>
 *   <li>{@link CallerContext} 的 agentId 与 caller 同生共死，不串号；</li>
 *   <li>审计 {@code SKILL_INVOKED} 带 {@code agent=} / {@code owner=} 归属。</li>
 * </ol>
 */
public class V19CallerBindingTest {

    static int pass = 0, fail = 0;

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

    public static void main(String[] args) {
        parseBasics();
        rejectRules();
        emptyMeansDisabled();
        orphans();
        contextCarriesAgent();
        auditAttribution();
        errorCodes();
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- ① 正常解析 ---------- */

    static void parseBasics() {
        CallerAgentBindings b = CallerAgentBindings.parse("ops:AGT-0001,ci:AGT-0003");
        eq("解析-条目数", b.size(), 2);
        eq("解析-取绑定", b.agentFor("ops"), "AGT-0001");
        eq("解析-取第二条", b.agentFor("ci"), "AGT-0003");
        check("解析-已绑定判定", b.isBound("ci"));
        check("解析-未绑定判定", !b.isBound("nobody"));
        eq("解析-未绑定返回null", b.agentFor("nobody"), null);
        eq("解析-name为null返回null", b.agentFor(null), null);
        eq("解析-状态OK", b.status(), CallerAgentBindings.Status.OK);
        check("解析-非空即严格", b.strict());
        eq("解析-keySet", b.callers(), List.of("ops", "ci"));

        // 分隔符宽容：逗号 / 分号 / 空白都当分隔
        eq("解析-分号分隔", CallerAgentBindings.parse("a:AGT-1;b:AGT-2").size(), 2);
        eq("解析-空白分隔", CallerAgentBindings.parse("a:AGT-1 b:AGT-2").size(), 2);
        // ID 与调用者两侧空白被裁剪
        eq("解析-裁剪空白", CallerAgentBindings.parse(" ops : AGT-0001 ").agentFor("ops"), "AGT-0001");
        // of() 便捷装配
        eq("便捷-单条", CallerAgentBindings.of("ops", "AGT-0009").agentFor("ops"), "AGT-0009");
    }

    /* ---------- ② 非法条目 ---------- */

    static void rejectRules() {
        CallerAgentBindings b = CallerAgentBindings.parse(
                "ops:AGT-1,bad-entry,noagent:,:AGT-9,bad name:AGT-2,weird$:AGT-3,ops:AGT-99,ok id:AGT-4");
        eq("非法-合法条目保留", b.agentFor("ops"), "AGT-1");
        eq("非法-AgentID非法被拒", b.agentFor("bad name"), null);
        eq("非法-调用者非法被拒", b.agentFor("weird$"), null);
        eq("非法-空AgentID被拒", b.agentFor("noagent"), null);
        eq("非法-重复只留首条", b.agentFor("ops"), "AGT-1");
        check("非法-有被拒条目", !b.rejected().isEmpty());
        eq("非法-状态PARTIAL", b.status(), CallerAgentBindings.Status.PARTIAL);
        check("非法-拒绝摘要不含原因以外的原文", b.rejected().stream()
                .noneMatch(r -> r == null || r.isBlank()));

        // 全不合格 → 空表 + WEAK（有配置错误的嫌疑）
        CallerAgentBindings weak = CallerAgentBindings.parse("x,y,z");
        check("非法-全不合格为空表", weak.isEmpty());
        check("非法-全不合格不严格", !weak.strict());
        eq("非法-全不合格状态WEAK", weak.status(), CallerAgentBindings.Status.WEAK);
    }

    /* ---------- ③ 空表语义 ---------- */

    static void emptyMeansDisabled() {
        CallerAgentBindings none = CallerAgentBindings.none();
        check("空表-为空", none.isEmpty());
        check("空表-不严格（回落v1.8行为）", !none.strict());
        eq("空表-状态MISSING", none.status(), CallerAgentBindings.Status.MISSING);
        eq("空表-查询返回null", none.agentFor("ops"), null);

        eq("空串-为空表", CallerAgentBindings.parse("").size(), 0);
        eq("空白串-为空表", CallerAgentBindings.parse("   ").size(), 0);
        eq("null-为空表", CallerAgentBindings.parse(null).size(), 0);
        eq("null-状态MISSING", CallerAgentBindings.parse(null).status(),
                CallerAgentBindings.Status.MISSING);

        // 单条绑定即为严格模式：一条也算"启用了绑定"
        check("单条-即严格", CallerAgentBindings.of("solo", "AGT-0001").strict());
    }

    /* ---------- ④ 两个孤儿方向 ---------- */

    static void orphans() {
        ApiCallerRegistry registry = ApiCallerRegistry.parse(
                "ops:8f3c9e1b5d2048a6c3a9e7d1b5f0c8a2,ci:1a9d7b5f3c1e04862b8d6a4f2e0c9b7d");
        CallerAgentBindings b = CallerAgentBindings.parse("ops:AGT-0001,ghost:AGT-0002");

        eq("孤儿-有令牌无绑定", b.callersWithoutBinding(registry), List.of("ci"));
        eq("孤儿-有绑定无令牌", b.callersWithoutToken(registry), List.of("ghost"));
        eq("孤儿-都无时不报", CallerAgentBindings.parse("ops:AGT-1,ci:AGT-2")
                .callersWithoutBinding(registry).size(), 0);
        eq("孤儿-空表时不报有绑定无令牌", CallerAgentBindings.none()
                .callersWithoutToken(registry).size(), 0);
        eq("孤儿-注册表为null不炸", b.callersWithoutBinding(null).size(), 0);
    }

    /* ---------- ⑤ 上下文承载 ---------- */

    static void contextCarriesAgent() {
        CallerContext.clear();
        check("上下文-初始未绑定", !CallerContext.bound());
        eq("上下文-初始agentId", CallerContext.agentId(), null);
        eq("上下文-agentId兜底", CallerContext.agentIdOr("agents-api-agent"), "agents-api-agent");

        CallerContext.set("ops", "AGT-0001");
        eq("上下文-caller", CallerContext.current(), "ops");
        eq("上下文-agentId", CallerContext.agentId(), "AGT-0001");
        eq("上下文-agentId不取兜底", CallerContext.agentIdOr("x"), "AGT-0001");
        check("上下文-已绑定", CallerContext.bound());

        // 只写 caller 时 agentId 必须被清掉，不能残留上一次的绑定
        CallerContext.set("ci");
        eq("上下文-只写caller后agent为空", CallerContext.agentId(), null);
        check("上下文-只写caller不算绑定", !CallerContext.bound());

        CallerContext.set("ops", "AGT-0001");
        CallerContext.clear();
        eq("上下文-清理后caller", CallerContext.current(), null);
        eq("上下文-清理后agent", CallerContext.agentId(), null);
        check("上下文-清理后未绑定", !CallerContext.bound());

        // caller 写 null 等价于清理（两个值一起清）
        CallerContext.set("ops", "AGT-0001");
        CallerContext.set(null);
        eq("上下文-caller写null清agent", CallerContext.agentId(), null);
    }

    /* ---------- ⑥ 审计归属 ---------- */

    static void auditAttribution() {
        HashChainAuditStore store = new HashChainAuditStore();
        AuditDecorator decorator = new AuditDecorator((intent, ctx) -> SkillResult.success("done"), store);
        IntentObject intent = new IntentObject("agents.registerAgent", java.util.Map.of(),
                1.0, "注册", "改注册表", "回滚");

        ExecutionContext bound = new ExecutionContext("s1", "t1", "tenant-default",
                "AGT-0001", "ops", false, AgentMode.DEV_EXECUTION);
        decorator.execute(intent, bound);

        ExecutionContext unbound = new ExecutionContext("s1", "t2", "tenant-default",
                "agents-api-agent", "agents-api-owner", false, AgentMode.DEV_EXECUTION);
        decorator.execute(intent, unbound);

        List<String> payloads = store.all().stream()
                .filter(r -> "SKILL_INVOKED".equals(r.action))
                .map(r -> r.payload).toList();
        eq("审计-记录数", payloads.size(), 2);
        check("审计-含绑定Agent", payloads.get(0).contains("agent=AGT-0001"));
        check("审计-含调用者", payloads.get(0).contains("owner=ops"));
        check("审计-未绑定时字段仍在", payloads.get(1).contains("agent=agents-api-agent"));
        check("审计-仍保留task字段", payloads.get(0).contains("task=t1"));
        check("审计-仍保留mode字段", payloads.get(0).contains("mode=DEV_EXECUTION"));
        check("审计-哈希链仍成立", store.verifyChain());

        // agentId 为 null 时不出现 "null" 字样（结构化字段保持可解析）
        HashChainAuditStore store2 = new HashChainAuditStore();
        new AuditDecorator((i2, c2) -> SkillResult.success("done"), store2).execute(intent,
                new ExecutionContext("s", "t", "tenant", null, null, false, AgentMode.DEV_EXECUTION));
        String p = store2.all().get(0).payload;
        check("审计-空值不写成null", !p.contains("agent=null") && !p.contains("owner=null"));
        check("审计-空值字段仍在位", p.contains("agent=|owner="));
    }

    /* ---------- ⑦ 错误码 ---------- */

    static void errorCodes() {
        eq("错误码-未绑定", ErrorCodes.CALLER_NOT_PROVISIONED, "CALLER_NOT_PROVISIONED");
        eq("错误码-Agent不可用", ErrorCodes.CALLER_AGENT_UNAVAILABLE, "CALLER_AGENT_UNAVAILABLE");
        check("错误码-两者不同",
                !ErrorCodes.CALLER_NOT_PROVISIONED.equals(ErrorCodes.CALLER_AGENT_UNAVAILABLE));
    }
}
