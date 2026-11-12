package com.towndemontower.core;

import com.towndemontower.core.approval.ApprovalDecision;
import com.towndemontower.core.approval.ApprovalPolicy;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.decorator.RiskGateDecorator;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.policy.CommandTemplate;
import com.towndemontower.core.policy.DefaultRiskGate;
import com.towndemontower.core.policy.SimpleShellAstAnalyzer;

import java.util.HashMap;
import java.util.Map;

/**
 * [V1.12] I5 命令模板化验证。
 *
 * <p>两组用例：<b>模板本身</b>（语法、通配、注入防护、边界）与<b>闸门集成</b>
 * （形状不符的拒绝码、未声明模板时行为不变、模板之后 AST 仍生效）。</p>
 */
public class V112TemplateTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) {
        matchBasics();
        injectionGuards();
        restPlaceholder();
        parseErrors();
        gateIntegration();

        System.out.println();
        System.out.println("RESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // 模板语法
    // ------------------------------------------------------------------

    static void matchBasics() {
        CommandTemplate t = CommandTemplate.of("systemctl restart {service}");

        CommandTemplate.Match ok = t.match("systemctl restart nginx");
        check("模板-正常命中", ok.matched && "nginx".equals(ok.params.get("service")));

        check("模板-多余词被拒", !t.match("systemctl restart nginx extra").matched);
        check("模板-追加命令被拒", !t.match("systemctl restart nginx ; rm -rf /").matched);
        check("模板-管道被拒", !t.match("systemctl restart nginx | sh").matched);
        check("模板-缺参数被拒", !t.match("systemctl restart").matched);
        check("模板-字面量不符被拒", !t.match("systemctl stop nginx").matched);
        check("模板-前缀相同但词不同被拒", !t.match("systemctl restartnginx").matched);
        check("模板-空命令被拒", !t.match("   ").matched);
        check("模板-null 命令被拒", !t.match(null).matched);
        check("模板-缓存同实例",
                CommandTemplate.of("systemctl restart {service}") == t);
        check("模板-参数名暴露", t.paramNames().size() == 1 && "service".equals(t.paramNames().get(0)));

        // 多个占位符，且顺序固定
        CommandTemplate two = CommandTemplate.of("cp {src} {dst}");
        CommandTemplate.Match m2 = two.match("cp a.txt b.txt");
        check("模板-多占位符", m2.matched && "a.txt".equals(m2.params.get("src"))
                && "b.txt".equals(m2.params.get("dst")));
        check("模板-多占位符少一个被拒", !two.match("cp a.txt").matched);
    }

    static void injectionGuards() {
        CommandTemplate t = CommandTemplate.of("systemctl restart {service}");

        check("注入-分号值被拒", !t.match("systemctl restart nginx;rm").matched);
        check("注入-命令替换被拒", !t.match("systemctl restart $(whoami)").matched);
        check("注入-反引号被拒", !t.match("systemctl restart `id`").matched);
        check("注入-重定向被拒", !t.match("systemctl restart x>y").matched);
        check("注入-引号值被拒", !t.match("systemctl restart \"a b\"").matched);
        check("注入-选项当值被拒", !t.match("systemctl restart -rf").matched);

        // 单值占位符不接受"看起来像选项"的值
        CommandTemplate unit = CommandTemplate.of("journalctl -u {unit}");
        check("注入-选项槽位正常", unit.match("journalctl -u nginx").matched);
        check("注入-选项槽位塞标志被拒", !unit.match("journalctl -u -f").matched);

        check("边界-超长命令被拒", !t.match("systemctl restart " + new String(new char[4000]).replace('\0', 'a')).matched);
        check("边界-超长参数被拒", !t.match("systemctl restart " + new String(new char[300]).replace('\0', 'a')).matched);
    }

    static void restPlaceholder() {
        CommandTemplate t = CommandTemplate.of("docker logs {container} {args*}");

        CommandTemplate.Match one = t.match("docker logs web --tail 5");
        check("通配-尾部参数", one.matched && "web".equals(one.params.get("container"))
                && "--tail 5".equals(one.params.get("args")));

        CommandTemplate.Match noRest = t.match("docker logs web");
        check("通配-至少一个词", !noRest.matched);

        // 最短匹配优先：后面还有字面量时不能把字面量吞掉
        CommandTemplate tail = CommandTemplate.of("run {x*} --done");
        CommandTemplate.Match m = tail.match("run a b --done");
        check("通配-后面还有字面量", m.matched && "a b".equals(m.params.get("x")));

        check("通配-控制操作符仍被拒", !t.match("docker logs web --tail 5 ; rm -rf /").matched);
        check("通配-命令替换仍被拒", !t.match("docker logs web $(id)").matched);
    }

    static void parseErrors() {
        check("解析-空模板抛错", throwsIae(() -> CommandTemplate.of("")));
        check("解析-null 模板抛错", throwsIae(() -> CommandTemplate.of(null)));
        check("解析-占位符名非法抛错", throwsIae(() -> CommandTemplate.of("a {1bad}")));
        check("解析-占位符重复抛错", throwsIae(() -> CommandTemplate.of("a {x} {x}")));
        check("解析-字面量含分号抛错", throwsIae(() -> CommandTemplate.of("a;b {x}")));
        // 花括号是模板语法的一部分，字面量里出现就是写错了：宁可启动期报错，也不要运行时才猜
        check("解析-未闭合花括号抛错", throwsIae(() -> CommandTemplate.of("a {b")));
    }

    // ------------------------------------------------------------------
    // 闸门集成
    // ------------------------------------------------------------------

    static void gateIntegration() {
        SkillRegistry reg = new SkillRegistry();
        reg.register(new SkillDescriptor("restartSvc", "R1",
                new String[0], new String[0], new String[]{"command"}, true, "ephemeral",
                "systemctl restart {service}"));
        // 故意写坏一个模板，验证"开发期错误不被静默吞掉"
        reg.register(new SkillDescriptor("badTmpl", "R1",
                new String[0], new String[0], new String[]{"command"}, true, "ephemeral",
                "a {1bad}"));
        // 未声明模板的存量工具
        reg.register(new SkillDescriptor("legacyShell", "R1",
                new String[0], new String[0], new String[]{"command"}, true, "ephemeral"));

        boolean[] ran = {false};
        Executor terminal = (intent, ctx) -> {
            ran[0] = true;
            return SkillResult.success("ran:" + intent.tool);
        };
        ApprovalPolicy approve = (ticket, ctx) -> ApprovalDecision.APPROVE;
        RiskGateDecorator gate = new RiskGateDecorator(terminal,
                new DefaultRiskGate(new SimpleShellAstAnalyzer()), approve, reg);

        // ① 形状正确 → 放行
        SkillResult ok = gate.execute(intent("restartSvc", "systemctl restart nginx"), ctx());
        check("闸门-形状正确放行", ok.success && ran[0]);

        // ② 形状不符 → 专用拒绝码，且没有执行
        ran[0] = false;
        SkillResult extra = gate.execute(intent("restartSvc", "systemctl restart nginx ; rm -rf /"), ctx());
        check("闸门-形状不符拒绝", !extra.success
                && ErrorCodes.COMMAND_TEMPLATE_MISMATCH.equals(extra.code));
        check("闸门-拒绝时不执行", !ran[0]);

        // ③ 缺 command 参数
        SkillResult missing = gate.execute(intent("restartSvc", null), ctx());
        check("闸门-缺命令参数拒绝", !missing.success
                && ErrorCodes.COMMAND_TEMPLATE_MISMATCH.equals(missing.code));

        // ④ 模板写坏 → 显式拒绝（不静默放行）
        SkillResult bad = gate.execute(intent("badTmpl", "a b"), ctx());
        check("闸门-非法模板拒绝", !bad.success
                && ErrorCodes.COMMAND_TEMPLATE_INVALID.equals(bad.code));

        // ⑤ 未声明模板 → 行为不变：AST 仍然拦 rm -rf
        SkillResult legacy = gate.execute(intent("legacyShell", "rm -rf /"), ctx());
        check("闸门-存量工具AST仍拦", !legacy.success && ErrorCodes.R3_DENIED.equals(legacy.code));
        SkillResult legacyOk = gate.execute(intent("legacyShell", "echo hi"), ctx());
        check("闸门-存量工具正常命令放行", legacyOk.success);

        // ⑥ 未注册工具 → 显式拒绝而不是 NPE
        SkillResult unknown = gate.execute(intent("notRegistered", "echo hi"), ctx());
        check("闸门-未注册工具拒绝", !unknown.success
                && ErrorCodes.INTENT_FORMAT_INVALID.equals(unknown.code));

        // ⑦ 模板只是第一道：宽模板 + 危险命令仍被 AST 升级为 R3
        reg.register(new SkillDescriptor("wide", "R1",
                new String[0], new String[0], new String[]{"command"}, true, "ephemeral",
                "{cmd*}"));
        SkillResult wide = gate.execute(intent("wide", "rm -rf /"), ctx());
        check("闸门-宽模板不放行危险命令", !wide.success && ErrorCodes.R3_DENIED.equals(wide.code));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    static IntentObject intent(String tool, String command) {
        Map<String, Object> args = new HashMap<>();
        if (command != null) args.put("command", command);
        return new IntentObject(tool, args, 0.9, null, null, null);
    }

    static ExecutionContext ctx() {
        return new ExecutionContext("s", "t", "tenant", "agent", "owner", false, AgentMode.DEV_EXECUTION);
    }

    interface Call {
        void run();
    }

    static boolean throwsIae(Call c) {
        try {
            c.run();
            return false;
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }
}
