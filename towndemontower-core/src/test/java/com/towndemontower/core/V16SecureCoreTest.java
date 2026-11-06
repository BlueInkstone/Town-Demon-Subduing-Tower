package com.towndemontower.core;

import com.towndemontower.core.audit.AuditRecord;
import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.approval.HumanApprovalPolicy;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.executor.ExecutorFactory;
import com.towndemontower.core.executor.IntentArgsValidator;
import com.towndemontower.core.executor.IntentFormatException;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;
import com.towndemontower.core.executor.RejectAllExecutor;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.executor.SnapshotService;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.mode.DefaultModeToolPolicy;
import com.towndemontower.core.policy.DefaultRiskGate;
import com.towndemontower.core.policy.SimpleShellAstAnalyzer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V1.6 安全默认值（core 侧）：可落盘审计、fail-closed 熔断、输入校验、错误信息脱敏。
 */
public class V16SecureCoreTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        fileAudit();
        fileAuditTamper();
        failClosed();
        inputValidation();
        errorSanitized();

        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- ① 审计落盘 + 启动校验 + 分段 ---------- */
    static void fileAudit() throws Exception {
        Path f = Files.createTempFile("audit", ".log");
        Files.delete(f); // 从零开始
        FileAuditStore store = new FileAuditStore(f);
        store.appendAuto("SKILL_INVOKED", "writeFile|OK");
        store.appendAuto("SKILL_INVOKED", "deleteData|R3_DENIED");
        store.appendAuto("MODE_TRANSITION", "DEV_EXECUTION->AUDIT_REVIEWING");

        check("审计-内存链有效", store.verifyChain());
        check("审计-文件落盘条数", store.fileRecordCount() == 3);

        // 重启：同一文件再开一个实例，旧记录不变、新段从 GENESIS 起
        FileAuditStore reopened = new FileAuditStore(f);
        check("审计-重开不丢旧记录", reopened.fileRecordCount() == 3);
        check("审计-重开链从零起", reopened.all().isEmpty());
        reopened.appendAuto("SKILL_INVOKED", "readLog|OK");
        check("审计-追加后可校验", reopened.verifyChain() && reopened.fileRecordCount() == 4);
    }

    static void fileAuditTamper() throws Exception {
        Path f = Files.createTempFile("audit-tamper", ".log");
        FileAuditStore store = new FileAuditStore(f);
        store.appendAuto("SKILL_INVOKED", "writeFile|OK");
        store.appendAuto("SKILL_INVOKED", "readLog|OK");

        // 篡改第 2 条的动作名（模拟"事后改日志"）
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("2\t")) {
                lines.set(i, lines.get(i).replace("readLog", "deployService"));
            }
        }
        Files.write(f, lines, StandardCharsets.UTF_8);

        boolean refused = false;
        try {
            new FileAuditStore(f);
        } catch (IllegalStateException e) {
            refused = true;
            check("审计-篡改提示可读", e.getMessage().contains("不连续") || e.getMessage().contains("哈希"));
        }
        check("审计-篡改则拒绝启动", refused);
    }

    /* ---------- ② fail-closed：治理关闭 = 全部拒绝 ---------- */
    static void failClosed() {
        HashChainAuditStore audit = new HashChainAuditStore();
        Executor e = new RejectAllExecutor(audit);
        SkillResult r = e.execute(new IntentObject("writeFile", Map.of("path", "/tmp/x"), 1.0, "r", "e", "rb"),
                new ExecutionContext("s", "t1", "t", "agent-a", "o", false, AgentMode.DEV_EXECUTION));

        check("熔断-拒绝执行", !r.success && "GOVERNANCE_DISABLED".equals(r.code));
        check("熔断-提示含工具名", r.message != null && r.message.contains("writeFile"));
        check("熔断-留痕", audit.all().stream()
                .anyMatch(a -> "GOVERNANCE_DISABLED_CALL".equals(a.action)));
    }

    /* ---------- ③ 输入校验（此处显式传 8192，测的是校验器契约，不是 starter 默认值） ---------- */
    static void inputValidation() {
        IntentArgsValidator.validate(Map.of("path", "/tmp/a.txt", "content", "hello"),
                8192, 64, true, true);
        check("校验-正常参数放行", true);

        check("校验-超长拒绝", rejects(Map.of("content", "x".repeat(9000))));
        check("校验-控制字符拒绝", rejects(Map.of("content", "abc\u0000def")));
        check("校验-换行允许", !rejects(Map.of("content", "line1\nline2")));
        check("校验-路径穿越拒绝", rejects(Map.of("path", "../../etc/passwd")));
        check("校验-Windows穿越拒绝", rejects(Map.of("path", "..\\..\\windows\\system32")));
        check("校验-普通相对路径放行", !rejects(Map.of("path", "./a/b.txt")));

        Map<String, Object> many = new HashMap<>();
        for (int i = 0; i < 65; i++) many.put("k" + i, "v");
        check("校验-参数过多拒绝", rejects(many));
    }

    /* ---------- ④ 内部错误不泄露细节 ---------- */
    static void errorSanitized() {
        SkillRegistry reg = new SkillRegistry();
        PermissionEngine pe = new PermissionEngine();
        PermissionProvisioner.of(pe).grant("a", "fs:write");
        Executor e = ExecutorFactory.buildDefault(reg, new DefaultModeToolPolicy(),
                new DefaultRiskGate(new SimpleShellAstAnalyzer()), pe,
                new SnapshotService(), (d, i) -> {
                    throw new IllegalStateException("secret=/etc/shadow|password=123456");
                },
                new HashChainAuditStore(), new HumanApprovalPolicy());

        SkillResult r = e.execute(new IntentObject("writeFile",
                        Map.of("path", "/tmp/x", "content", "c"), 0.9, "r", "e", "rb"),
                new ExecutionContext("s", "t2", "t", "a", "o", false, AgentMode.DEV_EXECUTION));

        check("错误-返回内部错误码", !r.success && "INTERNAL_ERROR".equals(r.code));
        check("错误-不回显敏感细节", r.message != null
                && !r.message.contains("secret") && !r.message.contains("password")
                && !r.message.contains("/etc/shadow"));
    }

    static boolean rejects(Map<String, Object> args) {
        try {
            IntentArgsValidator.validate(args, 8192, 64, true, true);
            return false;
        } catch (IntentFormatException e) {
            return true;
        }
    }

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
