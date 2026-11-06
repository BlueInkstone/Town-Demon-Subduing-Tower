package com.towndemontower.core;

import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.gate.GateResult;
import com.towndemontower.core.gate.GateScript;
import com.towndemontower.core.gate.GateScriptRunner;
import com.towndemontower.core.gate.SimpleGateScript;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * [V1.7] 门禁密钥来源收敛：只认环境变量，缺失即 fail-closed，且不存在内置默认密钥。
 *
 * <p>本测试特意<b>不设置</b> {@code TOWNDEMONTOWER_GATE_KEY}——要证明的正是"没配就拒绝"。
 * 若运行环境恰好设置了该变量，涉及"缺失"的断言会自动跳过并在输出里标注，
 * 避免在开发机上产生假阳性。</p>
 */
public class V17GateKeyEnvTest {

    static int pass = 0, fail = 0, skipped = 0;

    /** 显式注入用的测试密钥（源码里没有任何"可用"的生产密钥）。 */
    static final byte[] EXPLICIT_KEY =
            "v17-explicit-key-0123456789abcdefgh".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) {
        boolean envAbsent = System.getenv(GateScriptRunner.KEY_ENV) == null;

        classifyRules();
        explicitKeyStillWorks();
        noFallbackWhenEnvMissing(envAbsent);
        signingRequiresEnv(envAbsent);
        legacyApiSemantics();

        System.out.println();
        System.out.println("RESULT pass=" + pass + " fail=" + fail + " skipped=" + skipped);
        if (fail > 0) System.exit(1);
    }

    /* ---------- ① 强度判定规则 ---------- */
    static void classifyRules() {
        check("密钥-未设置判为MISSING", GateScriptRunner.classify(null) == GateScriptRunner.KeyStatus.MISSING);
        check("密钥-空白判为MISSING", GateScriptRunner.classify("   ") == GateScriptRunner.KeyStatus.MISSING);
        check("密钥-过短判为WEAK", GateScriptRunner.classify("short-key") == GateScriptRunner.KeyStatus.WEAK);
        check("密钥-恰好31字符判为WEAK",
                GateScriptRunner.classify("0123456789abcdef0123456789abcde")
                        == GateScriptRunner.KeyStatus.WEAK);
        check("密钥-32字符合格",
                GateScriptRunner.classify("0123456789abcdef0123456789abcdef")
                        == GateScriptRunner.KeyStatus.OK);
        check("密钥-占位值判为WEAK",
                GateScriptRunner.classify("changeme-changeme-changeme-changeme")
                        == GateScriptRunner.KeyStatus.WEAK);
        check("密钥-单字符重复判为WEAK",
                GateScriptRunner.classify("a".repeat(64)) == GateScriptRunner.KeyStatus.WEAK);
        check("密钥-前后空白被裁剪后仍合格",
                GateScriptRunner.classify("  0123456789abcdef0123456789abcdef  ")
                        == GateScriptRunner.KeyStatus.OK);
    }

    /* ---------- ② 显式注入路径照常可用 ---------- */
    static void explicitKeyStillWorks() {
        GateScriptRunner runner = new GateScriptRunner(s -> true, EXPLICIT_KEY);
        GateScript ok = new SimpleGateScript("compile", "mvn -q compile", Duration.ofSeconds(5), 2, EXPLICIT_KEY);
        GateResult r = runner.run(ok);
        check("显式密钥-签名通过", r.passed && r.attempts == 1);
        check("显式密钥-状态为OK", runner.keyStatus() == GateScriptRunner.KeyStatus.OK);

        GateScriptRunner other = new GateScriptRunner(s -> true, "another-explicit-key-0123456789abc".getBytes(StandardCharsets.UTF_8));
        GateResult r2 = other.run(ok);
        check("显式密钥-异钥不通过", !r2.passed && r2.output.contains("SIGNATURE_MISMATCH"));

        // 空数组不做静默回退：直接视为不可用
        GateScriptRunner empty = new GateScriptRunner(s -> true, new byte[0]);
        check("显式密钥-空数组视为不可用", !empty.keyAvailable());
    }

    /* ---------- ③ 环境变量缺失时：不执行、不猜 ---------- */
    static void noFallbackWhenEnvMissing(boolean envAbsent) {
        if (!envAbsent) {
            skip("环境变量已设置，跳过「缺失即拒绝」断言");
            return;
        }
        check("环境缺失-状态为MISSING", GateScriptRunner.envStatus() == GateScriptRunner.KeyStatus.MISSING);
        check("环境缺失-无可用密钥", !GateScriptRunner.keyAvailableInEnv());
        check("环境缺失-envKey为null", GateScriptRunner.envKey() == null);

        GateScriptRunner runner = new GateScriptRunner(s -> true);
        GateScript unsigned = new GateScript() {
            public String name() { return "compile"; }
            public String command() { return "mvn -q compile"; }
            public String signedHash() { return "WHATEVER"; }
            public Duration timeout() { return Duration.ofSeconds(1); }
            public int maxRetry() { return 0; }
        };
        GateResult r = runner.run(unsigned);
        check("环境缺失-门禁拒绝执行", !r.passed && ErrorCodes.GATE_KEY_UNAVAILABLE.equals(r.output));
        check("环境缺失-不产生执行次数", r.attempts == 0);
        check("环境缺失-实例状态非OK", runner.keyStatus() == GateScriptRunner.KeyStatus.MISSING);
    }

    /* ---------- ④ 需要签名的入口在缺失时快速失败 ---------- */
    static void signingRequiresEnv(boolean envAbsent) {
        if (!envAbsent) {
            // 环境变量已提供：走真实读取路径验证一次签-验闭环（密钥来自 env，源码里没有）
            GateScriptRunner runner = new GateScriptRunner(s -> true);
            GateScript signed = new SimpleGateScript("compile", "mvn compile", Duration.ofSeconds(1), 0);
            check("环境已配-密钥判定为OK", GateScriptRunner.envStatus() == GateScriptRunner.KeyStatus.OK);
            check("环境已配-签验闭环通过", runner.run(signed).passed);
            return;
        }
        String msg = null;
        try {
            GateScriptRunner.requireEnvKey();
        } catch (IllegalStateException e) {
            msg = e.getMessage();
        }
        check("环境缺失-requireEnvKey抛异常", msg != null);
        check("环境缺失-异常指明变量名", msg != null && msg.contains(GateScriptRunner.KEY_ENV));
        check("环境缺失-异常不回显任何密钥", msg != null && !msg.contains("dev-gate-key"));

        boolean threw = false;
        try {
            new SimpleGateScript("compile", "mvn compile", Duration.ofSeconds(1), 0);
        } catch (IllegalStateException e) {
            threw = true;
        }
        check("环境缺失-已签名脚本构造即失败", threw);
    }

    /* ---------- ⑤ 旧 API 的语义变化 ---------- */
    static void legacyApiSemantics() {
        check("旧API-usingDevKey等价于无可用密钥",
                GateScriptRunner.usingDevKey() == !GateScriptRunner.keyAvailableInEnv());
        // V1.7 之后不再存在任何"内置开发密钥"常量：这里用反射确认字段已删除
        boolean devKeyFieldGone = true;
        try {
            GateScriptRunner.class.getDeclaredField("DEV_KEY");
            devKeyFieldGone = false;
        } catch (NoSuchFieldException ignored) {
            // 期望走到这里
        }
        check("旧API-内置开发密钥字段已移除", devKeyFieldGone);
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

    static void skip(String why) {
        skipped++;
        System.out.println("[SKIP] " + why);
    }
}
