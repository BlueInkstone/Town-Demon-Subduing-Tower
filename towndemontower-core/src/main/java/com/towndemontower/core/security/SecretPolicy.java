package com.towndemontower.core.security;
import java.util.Arrays;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * [core] 密钥强度策略（单一事实来源）。
 *
 * <p>门禁签名密钥与 REST 调用令牌共用同一套判定，避免出现"门禁要求 32 位高熵、
 * 接口令牌却接受 {@code test}"这种两套标准。</p>
 *
 * <p>判定只看"能不能当密钥用"，<b>不回退到任何默认值</b>：不合格就是不合格，
 * 不存在"配错了就用内置值凑合"的中间态。</p>
 */
public final class SecretPolicy {

    /** 密钥最小长度（字符）。短于此长度的密钥在 HMAC 语境下不构成有效强度。 */
    public static final int MIN_LENGTH = 32;

    /**
     * 最少字符种类。挡住 {@code new String(new char[64]).replace('\0', 'a')} 与 {@code "changeme" × 4}
     * 这类"长度够但等于没配"的值。
     */
    public static final int MIN_DISTINCT_CHARS = 12;

    /** 常见占位值：出现在源码、示例、CI 模板里的高频字符串，一律视为"未配置"。 */
    private static final List<String> PLACEHOLDERS = Arrays.asList(
            "changeme", "change-me", "changeit", "test", "test-key", "testing",
            "dev", "development", "secret", "password", "passwd", "key", "gatekey",
            "towndemontower", "towndemontower-gate-key",
            "towndemontower-dev-gate-key-do-not-use-in-prod");

    /** 判定结果。{@link #OK} 之外的任何值都意味着"不可用"，调用方不应降级处理。 */
    public enum Problem {
        /** 合格。 */
        OK,
        /** 空或全空白。 */
        BLANK,
        /** 去空白后长度不足。 */
        TOO_SHORT,
        /** 命中占位值清单。 */
        PLACEHOLDER,
        /** 字符种类过少（熵不足）。 */
        LOW_ENTROPY
    }

    private SecretPolicy() {
    }

    /** 判定候选密钥。 */
    public static Problem check(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Problem.BLANK;
        String v = raw.trim();
        // 先判占位值：像 "changeme" 这种既是占位又过短的，报"占位"比报"太短"更能指向真正的问题
        if (PLACEHOLDERS.contains(v.toLowerCase(Locale.ROOT))) return Problem.PLACEHOLDER;
        if (v.length() < MIN_LENGTH) return Problem.TOO_SHORT;
        if (distinctChars(v) < MIN_DISTINCT_CHARS) return Problem.LOW_ENTROPY;
        return Problem.OK;
    }

    /** 是否可用。 */
    public static boolean isAcceptable(String raw) {
        return check(raw) == Problem.OK;
    }

    /** 人类可读的原因，用于日志与自检——只描述"哪里不合格"，不回显密钥内容。 */
    public static String describe(Problem p) {
        switch (p) {
            case OK:
                return "合格";
            case BLANK:
                return "未设置";
            case TOO_SHORT:
                return "长度不足 " + MIN_LENGTH + " 字符";
            case PLACEHOLDER:
                return "命中占位值清单（示例/测试用字符串不能当密钥）";
            case LOW_ENTROPY:
                return "字符种类少于 " + MIN_DISTINCT_CHARS + " 种";
            default:
                return p.name();
        }
    }

    /** 不同字符个数。 */
    public static int distinctChars(String s) {
        Set<Character> set = new HashSet<>();
        for (int i = 0; i < s.length(); i++) set.add(s.charAt(i));
        return set.size();
    }
}
