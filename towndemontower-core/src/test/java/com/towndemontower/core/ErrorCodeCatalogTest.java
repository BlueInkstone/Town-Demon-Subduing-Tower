package com.towndemontower.core;

import com.towndemontower.core.contract.ErrorCodeCatalog;
import com.towndemontower.core.contract.ErrorCodes;

/**
 * [core] 方向 D 规范码目录自运行验收（零 token、零 Spring、零外部依赖）。
 *
 * <pre>
 *   java -cp out com.towndemontower.core.ErrorCodeCatalogTest
 * </pre>
 *
 * 权威数据：contract/error-codes.json；跨语言一致性由 contract/verify_codes.py 校验。
 */
public class ErrorCodeCatalogTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== 方向 D 规范码目录验收（纯 Java） ===");

        testCanonicalSet();
        testAliases();
        testUnmapped();
        testStatistics();

        System.out.println("--- " + passed + "/" + (passed + failed) + " passed ---");
        if (failed > 0) System.exit(1);
    }

    private static void testCanonicalSet() {
        check("规范码 30 项", ErrorCodeCatalog.canonicalCodes().size() == 30);
        check("别名 12 项", ErrorCodeCatalog.aliases().size() == 12);
        check("OK 是规范码", ErrorCodeCatalog.isCanonical(ErrorCodeCatalog.OK));
        check("PENDING_APPROVAL 是规范码",
                ErrorCodeCatalog.isCanonical(ErrorCodes.PENDING_APPROVAL));
        check("SDK 别名不是规范码", !ErrorCodeCatalog.isCanonical("APPROVAL_REQUIRED"));
        check("所有别名目标均为规范码",
                ErrorCodeCatalog.aliases().values().stream()
                        .allMatch(ErrorCodeCatalog::isCanonical));
    }

    /** 与 contract/error-codes.json 的 golden 逐项对应。 */
    private static void testAliases() {
        check("APPROVAL_REQUIRED → PENDING_APPROVAL",
                ErrorCodes.PENDING_APPROVAL.equals(ErrorCodeCatalog.canonicalize("APPROVAL_REQUIRED")));
        check("RISK_REJECTED → R3_DENIED",
                ErrorCodes.R3_DENIED.equals(ErrorCodeCatalog.canonicalize("RISK_REJECTED")));
        check("INTENT_FORMAT_ERROR → INTENT_FORMAT_INVALID",
                ErrorCodes.INTENT_FORMAT_INVALID.equals(
                        ErrorCodeCatalog.canonicalize("INTENT_FORMAT_ERROR")));
        check("INTENT_INVALID → INTENT_FORMAT_INVALID",
                ErrorCodes.INTENT_FORMAT_INVALID.equals(ErrorCodeCatalog.canonicalize("INTENT_INVALID")));
        check("SCHEMA_VIOLATION → INTENT_FORMAT_INVALID",
                ErrorCodes.INTENT_FORMAT_INVALID.equals(ErrorCodeCatalog.canonicalize("SCHEMA_VIOLATION")));
        check("SCHEMA_MISMATCH → INTENT_FORMAT_INVALID",
                ErrorCodes.INTENT_FORMAT_INVALID.equals(ErrorCodeCatalog.canonicalize("SCHEMA_MISMATCH")));
        check("CAPABILITY_EXCEEDED → CAPABILITY_NOT_ALLOWED",
                ErrorCodeCatalog.CAPABILITY_NOT_ALLOWED.equals(
                        ErrorCodeCatalog.canonicalize("CAPABILITY_EXCEEDED")));
        check("TAINT_BLOCKED → TAINTED_WRITE_BLOCKED",
                ErrorCodes.TAINTED_WRITE_BLOCKED.equals(ErrorCodeCatalog.canonicalize("TAINT_BLOCKED")));
        check("MODE_NOT_ALLOWED → MODE_TOOL_NOT_ALLOWED",
                ErrorCodes.MODE_TOOL_NOT_ALLOWED.equals(ErrorCodeCatalog.canonicalize("MODE_NOT_ALLOWED")));
        check("MODE_DENIED → MODE_TOOL_NOT_ALLOWED",
                ErrorCodes.MODE_TOOL_NOT_ALLOWED.equals(ErrorCodeCatalog.canonicalize("MODE_DENIED")));
        check("SANDBOX_VIOLATION → SANDBOX_UNAVAILABLE",
                ErrorCodes.SANDBOX_UNAVAILABLE.equals(ErrorCodeCatalog.canonicalize("SANDBOX_VIOLATION")));
        check("SANDBOX_DENIED → SANDBOX_UNAVAILABLE",
                ErrorCodes.SANDBOX_UNAVAILABLE.equals(ErrorCodeCatalog.canonicalize("SANDBOX_DENIED")));
        check("规范码原样返回",
                ErrorCodes.COMMAND_TEMPLATE_MISMATCH.equals(
                        ErrorCodeCatalog.canonicalize(ErrorCodes.COMMAND_TEMPLATE_MISMATCH)));
        check("空白被 trim",
                ErrorCodes.R3_DENIED.equals(ErrorCodeCatalog.canonicalize("  RISK_REJECTED  ")));
    }

    /** docs/35 §6.3：未评审/未澄清的码不得擅自归一。 */
    private static void testUnmapped() {
        check("EXECUTION_ERROR 保留原值",
                "EXECUTION_ERROR".equals(ErrorCodeCatalog.canonicalize("EXECUTION_ERROR")));
        check("AUDIT_FAILED 保留原值",
                "AUDIT_FAILED".equals(ErrorCodeCatalog.canonicalize("AUDIT_FAILED")));
        check("APPROVAL_EXPIRED 保留原值",
                "APPROVAL_EXPIRED".equals(ErrorCodeCatalog.canonicalize("APPROVAL_EXPIRED")));
        check("未知码保留原值",
                "SOME_FUTURE_CODE".equals(ErrorCodeCatalog.canonicalize("SOME_FUTURE_CODE")));
        check("null 原样返回", ErrorCodeCatalog.canonicalize(null) == null);
        check("空白原样返回", "".equals(ErrorCodeCatalog.canonicalize("")));
    }

    /** 与 AuditDecorator 口径一致：PENDING_APPROVAL 单独计，不算拒绝。 */
    private static void testStatistics() {
        check("OK 不算拒绝", !ErrorCodeCatalog.countsAsDenied(ErrorCodeCatalog.OK));
        check("PENDING_APPROVAL 不算拒绝",
                !ErrorCodeCatalog.countsAsDenied(ErrorCodes.PENDING_APPROVAL));
        check("R3_DENIED 算拒绝", ErrorCodeCatalog.countsAsDenied(ErrorCodes.R3_DENIED));
        check("PERMISSION_DENIED 算拒绝",
                ErrorCodeCatalog.countsAsDenied(ErrorCodeCatalog.PERMISSION_DENIED));
        check("INTERNAL_ERROR 算拒绝",
                ErrorCodeCatalog.countsAsDenied(ErrorCodeCatalog.INTERNAL_ERROR));
        check("归一后 APPROVAL_REQUIRED 不算拒绝",
                !ErrorCodeCatalog.countsAsDenied(ErrorCodeCatalog.canonicalize("APPROVAL_REQUIRED")));
        check("归一后 RISK_REJECTED 算拒绝",
                ErrorCodeCatalog.countsAsDenied(ErrorCodeCatalog.canonicalize("RISK_REJECTED")));
        check("null 不算拒绝", !ErrorCodeCatalog.countsAsDenied(null));
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
