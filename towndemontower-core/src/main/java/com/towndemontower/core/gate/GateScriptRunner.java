package com.towndemontower.core.gate;

import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.security.SecretPolicy;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * [core] 门禁脚本运行器：先校验签名，再执行（失败按 maxRetry 自动重试），超限 escalate。
 *
 * <p><b>V1.5</b>：签名算法从 {@code String.hashCode()}（32 位、可碰撞、且与被签内容在同一个
 * 对象里自证）改为 <b>HMAC-SHA256</b>。</p>
 *
 * <p><b>V1.7：密钥来源收敛为"仅环境变量"</b>。此前是
 * {@code 环境变量 > 系统属性 > 内置开发密钥} 三级回退，后两级都是可伪造面：
 * 内置密钥随源码公开等于没有密钥；系统属性可以写进 {@code application.properties}、
 * 可以进启动脚本、可以出现在 {@code ps} / 进程转储里。现在只认
 * {@link #KEY_ENV}，其余一律不读——<b>没有"找不到就用默认值"这条分支</b>。</p>
 *
 * <p><b>密钥缺失时的行为是 fail-closed</b>：{@link #run(GateScript)} 直接返回
 * {@link ErrorCodes#GATE_KEY_UNAVAILABLE}，既不执行脚本、也不把"验不了"当成"验过了"。
 * 需要签名的脚本在构造期就会被 {@link #requireEnvKey()} 拦住，不会带着一个假签名进入运行期。</p>
 *
 * <p><b>测试与内嵌场景</b>：走 {@link #GateScriptRunner(GateExecutor, byte[])} 显式注入密钥。
 * 这是"把密钥交给对象"，不是"代码里写死密钥"——源码中不存在任何可用密钥常量。</p>
 */
public class GateScriptRunner {

    /** 唯一认可的密钥来源：环境变量。 */
    public static final String KEY_ENV = "TOWNDEMONTOWER_GATE_KEY";

    /**
     * @deprecated 自 V1.7 起<b>不再读取</b>系统属性。常量仅为源码兼容保留，
     * 出现即说明调用方仍在依赖旧路径，应当改为设置 {@link #KEY_ENV}。
     */
    @Deprecated
    public static final String KEY_PROP = "towndemontower.gate.key";

    /** 密钥最小长度（字符）。短于此长度的密钥在 HMAC 语境下不构成有效强度。 */
    public static final int MIN_KEY_LENGTH = SecretPolicy.MIN_LENGTH;

    /**
     * 密钥最少字符种类。判定实现在 {@link SecretPolicy} —— 门禁密钥与 REST 调用令牌
     * 共用同一套强度规则（V1.8 起收敛为单一事实来源）。
     */
    public static final int MIN_DISTINCT_CHARS = SecretPolicy.MIN_DISTINCT_CHARS;

    private static final String ALG = "HmacSHA256";

    /** 密钥可用性。 */
    public enum KeyStatus {
        /** 环境变量存在且通过强度校验。 */
        OK,
        /** 环境变量未设置（或为空白）。 */
        MISSING,
        /** 环境变量已设置但不合格（过短 / 占位值 / 字符种类过少）。 */
        WEAK
    }

    private final GateExecutor executor;
    /** {@code null} 表示密钥不可用——此时任何脚本都不执行。 */
    private final byte[] key;
    private final KeyStatus status;

    /** 生产路径：密钥只从环境变量取，取不到就是不可用。 */
    public GateScriptRunner(GateExecutor executor) {
        this(executor, envKey(), envStatus());
    }

    /** 显式注入（单测 / 内嵌场景）。空数组等价于不可用，不做静默回退。 */
    public GateScriptRunner(GateExecutor executor, byte[] key) {
        this(executor, (key == null || key.length == 0) ? null : key.clone(), KeyStatus.OK);
    }

    private GateScriptRunner(GateExecutor executor, byte[] key, KeyStatus status) {
        this.executor = executor;
        this.key = key;
        this.status = status;
    }

    /** 签名校验失败 → 不执行；密钥缺失 → 不执行（fail-closed）。 */
    public GateResult run(GateScript script) {
        if (key == null) {
            return GateResult.failed(ErrorCodes.GATE_KEY_UNAVAILABLE, 0);
        }
        if (!sign(key, script).equals(script.signedHash())) {
            return GateResult.failed("SIGNATURE_MISMATCH:" + ErrorCodes.GATE_SCRIPT_FAILED, 0);
        }
        int attempts = 0;
        for (int i = 0; i <= script.maxRetry(); i++) {
            attempts++;
            if (executor.run(script)) {
                return GateResult.passed(attempts);
            }
        }
        return GateResult.failed(ErrorCodes.GATE_SCRIPT_FAILED + ":exhausted", attempts);
    }

    /** 本实例的密钥状态。 */
    public KeyStatus keyStatus() {
        return status;
    }

    /** 密钥是否可用（可用才允许执行门禁脚本）。 */
    public boolean keyAvailable() {
        return key != null;
    }

    /**
     * 解析环境变量里的密钥；不合格一律返回 {@code null}，<b>不回退到任何默认值</b>。
     */
    public static byte[] envKey() {
        String raw = System.getenv(KEY_ENV);
        return isValid(raw) ? raw.trim().getBytes(StandardCharsets.UTF_8) : null;
    }

    /** 只做判定、不返回密钥，方便自检与端点报告。 */
    public static KeyStatus envStatus() {
        return classify(System.getenv(KEY_ENV));
    }

    /**
     * 判定任意候选密钥是否合格（不读取环境变量），供自检、诊断与测试使用。
     * 判定规则：非空、去空白后长度 ≥ {@link #MIN_KEY_LENGTH}、不在占位值清单、字符种类 ≥ 8。
     */
    public static KeyStatus classify(String raw) {
        if (raw == null || raw.isBlank()) return KeyStatus.MISSING;
        return isValid(raw) ? KeyStatus.OK : KeyStatus.WEAK;
    }

    private static boolean isValid(String raw) {
        return SecretPolicy.isAcceptable(raw);
    }

    /** 环境变量是否提供了合格密钥。 */
    public static boolean keyAvailableInEnv() {
        return envStatus() == KeyStatus.OK;
    }

    /**
     * @deprecated 语义已变：V1.7 起不存在"内置开发密钥"，此方法等价于
     * {@code !keyAvailableInEnv()}，即"当前没有可用密钥"。保留仅为源码兼容，
     * 请改用 {@link #envStatus()}。
     */
    @Deprecated
    public static boolean usingDevKey() {
        return !keyAvailableInEnv();
    }

    /**
     * 取环境变量密钥，取不到直接抛——用于"没有密钥就不该存在"的场景（如构造已签名脚本）。
     * 异常信息只描述"缺什么"，不回显密钥内容。
     */
    public static byte[] requireEnvKey() {
        byte[] k = envKey();
        if (k != null) return k;
        KeyStatus st = envStatus();
        throw new IllegalStateException("门禁密钥不可用（" + st + "）：必须通过环境变量 "
                + KEY_ENV + " 提供至少 " + MIN_KEY_LENGTH + " 个字符的高熵密钥；"
                + "不接受系统属性、配置文件或源码内置默认值");
    }

    /** 用环境变量密钥计算签名（旧 API 的等价实现）。密钥不可用时抛异常，不返回假签名。 */
    public static String expectedHash(GateScript s) {
        return sign(requireEnvKey(), s);
    }

    /** HMAC-SHA256(name|command)，输出小写十六进制。 */
    public static String sign(byte[] key, GateScript s) {
        try {
            Mac mac = Mac.getInstance(ALG);
            mac.init(new SecretKeySpec(key, ALG));
            byte[] raw = mac.doFinal((s.name() + "|" + s.command()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("gate signing failed", e);
        }
    }
}
