package com.towndemontower.core;
import java.util.Arrays;

import com.towndemontower.core.audit.AuditRecord;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.decorator.GovernancePass;
import com.towndemontower.core.decorator.SandboxedToolExecutor;
import com.towndemontower.core.decorator.UngovernedInvocationException;
import com.towndemontower.core.executor.CancellationToken;
import com.towndemontower.core.executor.SkillInvoker;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.metrics.GovernanceMetrics;
import com.towndemontower.core.mode.AgentMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * [V1.11] D2 / CP-1 能力令牌验证：发放、一次性消费、名字不符、兜底清理、线程隔离、栈深上限。
 *
 * <p>全部用例都走真实链尾 {@link SandboxedToolExecutor}，不直接调包内方法——令牌的价值在于
 * "只有链尾能发牌"，测法也必须守住这条边界。</p>
 */
public class V111PassTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    /** 记录 action/payload 的极简审计桩（只关心"有没有记、记了什么"）。 */
    static final class RecordingAudit implements AuditStore {
        final List<String> actions = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();

        @Override
        public void appendAuto(String action, String payload) {
            actions.add(action);
            payloads.add(payload);
        }

        @Override
        public List<AuditRecord> all() {
            return Arrays.asList();
        }

        @Override
        public boolean verifyChain() {
            return true;
        }
    }

    static SkillRegistry registry(String tool) {
        SkillRegistry reg = new SkillRegistry();
        reg.register(new SkillDescriptor(tool, "R1",
                new String[0], new String[0], new String[0], false, "none"));
        return reg;
    }

    static IntentObject intent(String tool) {
        return new IntentObject(tool, new java.util.HashMap<>(), 0.9, null, null, null);
    }

    static ExecutionContext ctx() {
        return new ExecutionContext("s", "t", "tenant", "agent", "owner", false, AgentMode.DEV_EXECUTION);
    }

    public static void main(String[] args) {
        consumeOnce();
        oneShot();
        nameMismatch();
        clearedAfterExecution();
        cancelledNeverIssues();
        nestedLifo();
        threadIsolation();
        depthLimit();
        bypassScope();

        System.out.println();
        System.out.println("RESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** 正常路径：链尾发牌 → 首行 require 命中 → 消费 → 收牌。 */
    static void consumeOnce() {
        GovernancePass.resetThread();
        RecordingAudit audit = new RecordingAudit();
        GovernanceMetrics metrics = new GovernanceMetrics();
        SkillInvoker inv = (d, i) -> {
            GovernancePass.require("tool1");
            return "ok";
        };
        SandboxedToolExecutor exec = new SandboxedToolExecutor(inv, registry("tool1"), audit, metrics);
        SkillResult r = exec.execute(intent("tool1"), ctx());

        check("pass.consumed", r.success && "ok".equals(r.output));
        check("pass.stack-empty", !GovernancePass.hasActivePass());
        check("pass.no-violation", metrics.get(GovernanceMetrics.UNGOVERNED_INVOCATION) == 0
                && audit.actions.isEmpty());
    }

    /** 一次性：同一令牌不能消费两次。 */
    static void oneShot() {
        GovernancePass.resetThread();
        GovernanceMetrics metrics = new GovernanceMetrics();
        RecordingAudit audit = new RecordingAudit();
        SkillInvoker inv = (d, i) -> {
            GovernancePass.require("tool1");
            GovernancePass.require("tool1");   // 第二次必然失败
            return "ok";
        };
        SandboxedToolExecutor exec = new SandboxedToolExecutor(inv, registry("tool1"), audit, metrics);
        UngovernedInvocationException ex = expectUngoverned(() -> exec.execute(intent("tool1"), ctx()));

        check("pass.reuse-blocked", ex != null);
        check("pass.reuse-counted", metrics.get(GovernanceMetrics.UNGOVERNED_INVOCATION) == 1);
        check("pass.stack-empty-after-violation", !GovernancePass.hasActivePass());
    }

    /** 名字不符：令牌是给 tool1 的，却声称执行 tool2 —— 属于冒用，必须拒。 */
    static void nameMismatch() {
        GovernancePass.resetThread();
        GovernanceMetrics metrics = new GovernanceMetrics();
        RecordingAudit audit = new RecordingAudit();
        SkillInvoker inv = (d, i) -> {
            GovernancePass.require("tool2");
            return "ok";
        };
        SandboxedToolExecutor exec = new SandboxedToolExecutor(inv, registry("tool1"), audit, metrics);
        UngovernedInvocationException ex = expectUngoverned(() -> exec.execute(intent("tool1"), ctx()));

        check("pass.mismatch-blocked", ex != null && "tool2".equals(ex.getTool()));
        check("pass.mismatch-code", ex != null && ErrorCodes.UNGOVERNED_INVOCATION.equals(ex.getCode()));
        check("pass.mismatch-audited", audit.actions.contains(ErrorCodes.UNGOVERNED_INVOCATION)
                && audit.payloads.get(0).contains("tool2"));
        check("pass.mismatch-counted", metrics.get(GovernanceMetrics.UNGOVERNED_INVOCATION) == 1);
    }

    /** 关键不变量：目标方法没写 require 时，令牌也必须在 finally 里收回，否则会污染下一次调用。 */
    static void clearedAfterExecution() {
        GovernancePass.resetThread();
        SkillInvoker inv = (d, i) -> "no-require-inside";
        SandboxedToolExecutor exec = new SandboxedToolExecutor(inv, registry("tool1"),
                new RecordingAudit(), new GovernanceMetrics());

        exec.execute(intent("tool1"), ctx());
        check("pass.cleared-without-require", !GovernancePass.hasActivePass() && GovernancePass.depth() == 0);

        // 残留令牌若未被清理，下面这句会"误通过"（该线程此时确实没有任何令牌可消费）
        UngovernedInvocationException ex = expectUngoverned(() -> GovernancePass.require("tool1"));
        check("pass.no-leak", ex != null);
    }

    /** 取消的调用到不了链尾，因此既不发牌也不计违规。 */
    static void cancelledNeverIssues() {
        GovernancePass.resetThread();
        GovernanceMetrics metrics = new GovernanceMetrics();
        SkillInvoker inv = (d, i) -> "should-not-run";
        SandboxedToolExecutor exec = new SandboxedToolExecutor(inv, registry("tool1"), new RecordingAudit(), metrics);

        CancellationToken tok = new CancellationToken("c1");
        tok.cancel();
        ExecutionContext ctx = new ExecutionContext("s", "t", "tenant", "agent", "owner", false,
                AgentMode.DEV_EXECUTION, tok);
        SkillResult r = exec.execute(intent("tool1"), ctx);

        check("pass.cancel-denied", !r.success && ErrorCodes.CANCELLED.equals(r.code));
        check("pass.cancel-no-token", !GovernancePass.hasActivePass());
        check("pass.cancel-no-violation", metrics.get(GovernanceMetrics.UNGOVERNED_INVOCATION) == 0);
    }

    /** 嵌套调用：内层先收内层的牌，外层再收外层的牌（LIFO，不串味）。 */
    static void nestedLifo() {
        GovernancePass.resetThread();
        GovernanceMetrics metrics = new GovernanceMetrics();
        RecordingAudit audit = new RecordingAudit();

        SkillInvoker innerInv = (d, i) -> {
            GovernancePass.require("tool2");
            return "inner";
        };
        SandboxedToolExecutor inner = new SandboxedToolExecutor(innerInv, registry("tool2"), audit, metrics);

        SkillInvoker outerInv = (d, i) -> {
            Object innerOut = inner.execute(intent("tool2"), ctx()).output;
            GovernancePass.require("tool1");
            return innerOut + "+outer";
        };
        SandboxedToolExecutor outer = new SandboxedToolExecutor(outerInv, registry("tool1"), audit, metrics);

        SkillResult r = outer.execute(intent("tool1"), ctx());
        check("pass.nested-ok", r.success && "inner+outer".equals(r.output));
        check("pass.nested-empty", !GovernancePass.hasActivePass());
        check("pass.nested-no-violation", metrics.get(GovernanceMetrics.UNGOVERNED_INVOCATION) == 0);
    }

    /** 线程隔离：令牌绑在发起调用的线程上，换线程即失效。 */
    static void threadIsolation() {
        GovernancePass.resetThread();
        GovernanceMetrics metrics = new GovernanceMetrics();
        AtomicReference<Throwable> seen = new AtomicReference<>();

        SkillInvoker inv = (d, i) -> {
            Thread t = new Thread(() -> {
                try {
                    GovernancePass.require("tool1");
                } catch (Throwable ex) {
                    seen.set(ex);
                }
            }, "other-thread");
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            GovernancePass.require("tool1");   // 本线程仍然有效
            return "ok";
        };
        SandboxedToolExecutor exec = new SandboxedToolExecutor(inv, registry("tool1"), new RecordingAudit(), metrics);
        SkillResult r = exec.execute(intent("tool1"), ctx());

        check("pass.thread-isolated", seen.get() instanceof UngovernedInvocationException);
        check("pass.owner-thread-ok", r.success && "ok".equals(r.output));
        // 跨线程违规无法归属到某条链（令牌帧在发起线程上），因此不计入本链指标——
        // 这是有意的：宁可少记一条，也不把别人的账记到这条链头上。
        check("pass.thread-violation-unattributed", metrics.get(GovernanceMetrics.UNGOVERNED_INVOCATION) == 0);
    }

    /** 栈深上限：递归失控时按违规处理并清空，不允许无限增长。 */
    static void depthLimit() {
        GovernancePass.resetThread();
        GovernanceMetrics metrics = new GovernanceMetrics();
        SandboxedToolExecutor[] holder = new SandboxedToolExecutor[1];
        SkillInvoker inv = (d, i) -> {
            holder[0].execute(intent("tool1"), ctx());   // 每层都发牌但不收（模拟递归）
            return "ok";
        };
        holder[0] = new SandboxedToolExecutor(inv, registry("tool1"), new RecordingAudit(), metrics);

        UngovernedInvocationException ex = expectUngoverned(() -> holder[0].execute(intent("tool1"), ctx()));
        check("pass.depth-limited", ex != null);
        check("pass.depth-empty", !GovernancePass.hasActivePass());
    }

    /**
     * 显式弃权作用域：{@code enabled=false} + allow/宽限时没有链、没有发牌点，
     * 方法首行的 require 不应该炸；但弃权必须留痕（审计 + 独立指标），且不能压制违规计数。
     */
    static void bypassScope() {
        GovernancePass.resetThread();
        RecordingAudit audit = new RecordingAudit();
        GovernanceMetrics metrics = new GovernanceMetrics();

        try (AutoCloseable ignored = GovernancePass.bypassScope("DISABLED_ALLOW", audit, metrics)) {
            GovernancePass.require("tool1");
            GovernancePass.require("tool2");
            check("pass.bypass-active", GovernancePass.isBypassActive());
        } catch (Exception ex) {
            check("pass.bypass-no-throw", false);
        }
        check("pass.bypass-restored", !GovernancePass.isBypassActive());
        check("pass.bypass-audited", audit.actions.stream()
                .filter(GovernancePass.BYPASS_AUDIT_CODE::equals).count() == 1);
        check("pass.bypass-counted", metrics.get(GovernanceMetrics.EXPLICIT_BYPASS) == 1);
        check("pass.bypass-not-ungoverned", metrics.get(GovernanceMetrics.UNGOVERNED_INVOCATION) == 0);

        UngovernedInvocationException ex = expectUngoverned(() -> GovernancePass.require("tool1"));
        check("pass.bypass-scope-closed", ex != null);

        try (AutoCloseable outer = GovernancePass.bypassScope("A", audit, metrics)) {
            try (AutoCloseable inner = GovernancePass.bypassScope("B", audit, metrics)) {
                check("pass.bypass-nested-active", GovernancePass.isBypassActive());
            }
            check("pass.bypass-nested-outer-kept", GovernancePass.isBypassActive());
        } catch (Exception e) {
            check("pass.bypass-nested-no-throw", false);
        }
        check("pass.bypass-nested-audited-once", audit.actions.stream()
                .filter(GovernancePass.BYPASS_AUDIT_CODE::equals).count() == 2);
    }

    interface Call {
        void run() throws Exception;
    }

    /** 期望抛 UngovernedInvocationException；抛了就返回它，否则返回 null。 */
    static UngovernedInvocationException expectUngoverned(Call call) {
        try {
            call.run();
            return null;
        } catch (UngovernedInvocationException ex) {
            return ex;
        } catch (Exception ex) {
            return null;
        }
    }
}
