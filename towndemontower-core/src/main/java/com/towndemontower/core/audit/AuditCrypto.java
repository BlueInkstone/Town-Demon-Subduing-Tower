package com.towndemontower.core.audit;
import com.towndemontower.core.util.Hex;

import com.towndemontower.core.security.SecretPolicy;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
/**
 * [core] 审计链的哈希算法与密钥解析（V1.9 / I4）。
 *
 * <p><b>为什么从 SHA-256 换成 HMAC-SHA256</b>：SHA-256 链只能回答"有没有被改"，
 * 回答不了"是不是有人把整条链重新算了一遍"。攻击者拿到日志后可以逐条重写 payload、
 * 重算 hash、把 prevHash 也一并修正——{@code verifyChain()} 依然返回 true。
 * 换成 HMAC 后，没有密钥就算不出正确摘要，重算攻击当场失效。</p>
 *
 * <p><b>密钥来源</b>：只读环境变量 {@value #KEY_ENV}（与门禁密钥同一套强度规则——
 * {@link SecretPolicy}）。未配置时<b>不阻断运行</b>，退化为原 SHA-256 链，
 * 由自检报 {@code AUDIT_KEY_MISSING}(WARN) 说明"可验篡改、不可防重算"。
 * 这样既有系统的日志不会因为升级而突然打不开。</p>
 */
public final class AuditCrypto {

    /** 审计链密钥的环境变量名。 */
    public static final String KEY_ENV = "TOWNDEMONTOWER_AUDIT_KEY";

    private static final String HMAC_ALG = "HmacSHA256";

    /** 链摘要模式。 */
    public enum Mode {
        /** 有密钥：HMAC-SHA256，可防重算。 */
        HMAC_SHA256,
        /** 无密钥：SHA-256，只能发现单点篡改。 */
        SHA256
    }

    private AuditCrypto() {
    }

    /** 从环境变量取密钥；不合格返回 {@code null}（不回退任何默认值）。 */
    public static byte[] envKey() {
        String raw = System.getenv(KEY_ENV);
        if (raw == null || !SecretPolicy.isAcceptable(raw)) return null;
        return raw.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** 是否存在可用密钥。 */
    public static boolean keyAvailableInEnv() {
        return envKey() != null;
    }

    /** 模式判定。{@code key == null} 即退化模式。 */
    public static Mode modeOf(byte[] key) {
        return (key == null || key.length == 0) ? Mode.SHA256 : Mode.HMAC_SHA256;
    }

    /**
     * 计算链摘要：有密钥用 HMAC-SHA256，无密钥用 SHA-256。
     *
     * <p>两者输出都是小写十六进制 64 字符，长度一致，因此"同一份日志在不同模式下
     * 长度不同"这种可嗅探差异不存在。</p>
     */
    public static String chain(byte[] key, String input) {
        if (key == null || key.length == 0) return sha256(input);
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return Hex.formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("audit chaining failed", e);
        }
    }

    /** 对外部锚点做 MAC（防止"日志和锚点一起改"）。 */
    public static String mac(byte[] key, String input) {
        if (key == null || key.length == 0) return "-";
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return Hex.formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("anchor mac failed", e);
        }
    }

    /** 纯 SHA-256（保留给旧日志校验与外部工具）。 */
    public static String sha256(String input) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return Hex.formatHex(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
