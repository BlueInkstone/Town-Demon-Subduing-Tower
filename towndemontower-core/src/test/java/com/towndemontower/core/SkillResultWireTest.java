package com.towndemontower.core;

import com.towndemontower.core.contract.ErrorCodeCatalog;
import com.towndemontower.core.contract.SkillResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [core] 方向 D 第③步：SkillResult wire 结构统一自运行验收（零 token、零 Spring、零外部依赖）。
 *
 * <pre>
 *   java -cp out com.towndemontower.core.SkillResultWireTest
 * </pre>
 *
 * 权威 golden：contract/skill-result.golden.json；字段集由
 * contract/towndemontower-wire.schema.json 的 $defs/SkillResult.required 定义。
 */
public class SkillResultWireTest {

    private static final List<String> WIRE_FIELDS =
            List.of("success", "code", "message", "output", "approvalTicketId");

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== 方向 D SkillResult wire 结构验收（纯 Java） ===");

        testOk();
        testOkWithObjectOutput();
        testDenied();
        testPendingApproval();
        testAliasNormalization();
        testRoundTrip();
        testExistingFactoriesUnchanged();

        System.out.println("--- " + passed + "/" + (passed + failed) + " passed ---");
        if (failed > 0) System.exit(1);
    }

    /** golden: ok */
    private static void testOk() {
        Map<String, Object> w = SkillResult.success("done").toWire();
        check("wire 字段集恰好 5 个",
                w.size() == WIRE_FIELDS.size() && w.keySet().containsAll(WIRE_FIELDS));
        check("success=true", Boolean.TRUE.equals(w.get("success")));
        check("成功码 OK", ErrorCodeCatalog.OK.equals(w.get("code")));
        check("message 显式 null", w.containsKey("message") && w.get("message") == null);
        check("output=done", "done".equals(w.get("output")));
        check("approvalTicketId 显式 null",
                w.containsKey("approvalTicketId") && w.get("approvalTicketId") == null);
    }

    /** golden: ok-object-output */
    private static void testOkWithObjectOutput() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("items", List.of(1, 2));
        nested.put("label", "batch");
        Map<String, Object> w = SkillResult.success(nested).toWire();
        check("复杂 output 原样透传", nested.equals(w.get("output")));
        check("不含本地扩展字段", !w.containsKey("auditId") && !w.containsKey("ticketId"));
    }

    /** golden: denied-permission */
    private static void testDenied() {
        Map<String, Object> w = SkillResult.denied("PERMISSION_DENIED", "No permission").toWire();
        check("success=false", Boolean.FALSE.equals(w.get("success")));
        check("code=PERMISSION_DENIED", "PERMISSION_DENIED".equals(w.get("code")));
        check("message=No permission", "No permission".equals(w.get("message")));
        check("失败 output 为 null", w.containsKey("output") && w.get("output") == null);
        check("失败码是规范码", ErrorCodeCatalog.isCanonical((String) w.get("code")));
    }

    /** golden: pending-approval-alias */
    private static void testPendingApproval() {
        Map<String, Object> w = SkillResult.pendingApproval("TKT-1").toWire();
        check("code=PENDING_APPROVAL", "PENDING_APPROVAL".equals(w.get("code")));
        check("approvalTicketId=TKT-1", "TKT-1".equals(w.get("approvalTicketId")));
        check("message=awaiting human approval",
                "awaiting human approval".equals(w.get("message")));
    }

    /** golden: r3-denied-alias —— 输入别名，wire 输出必须归一为规范码 */
    private static void testAliasNormalization() {
        Map<String, Object> w = SkillResult.denied("RISK_REJECTED", "irreversible action blocked").toWire();
        check("RISK_REJECTED → R3_DENIED", "R3_DENIED".equals(w.get("code")));
        check("APPROVAL_REQUIRED → PENDING_APPROVAL",
                "PENDING_APPROVAL".equals(
                        SkillResult.denied("APPROVAL_REQUIRED", "x").toWire().get("code")));
        check("未登记码保留原值（不擅自归一）",
                "SOME_FUTURE_CODE".equals(
                        SkillResult.denied("SOME_FUTURE_CODE", "x").toWire().get("code")));
    }

    private static void testRoundTrip() {
        SkillResult original = SkillResult.pendingApproval("TKT-9");
        SkillResult restored = SkillResult.fromWire(original.toWire());
        check("fromWire 往返 success", restored.success == original.success);
        check("fromWire 往返 code", original.code.equals(restored.code));
        check("fromWire 往返 message", original.message.equals(restored.message));
        check("fromWire 往返 ticket", "TKT-9".equals(restored.approvalTicketId));
        check("fromWire 往返 wire 一致", original.toWire().equals(restored.toWire()));
    }

    /** 既有工厂的语义不能因结构统一而改变（零回归）。 */
    private static void testExistingFactoriesUnchanged() {
        check("success() 仍返回 OK", "OK".equals(SkillResult.success("x").code));
        check("needMoreInfo 仍返回 NEED_MORE_INFO",
                "NEED_MORE_INFO".equals(SkillResult.needMoreInfo("x").code));
        check("denied 不改写传入 code",
                "R3_DENIED".equals(SkillResult.denied("R3_DENIED", "x").code));
        check("原字段仍可直接读取",
                SkillResult.success("x").output.equals("x")
                        && SkillResult.success("x").message == null);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }
}
