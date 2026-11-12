package com.towndemontower.core.util;


import com.towndemontower.core.util.Hex;
/**
 * Java 8 兼容的十六进制工具（替代 JDK 17 的 {@code HexFormat}）。
 *
 * <p>仅提供审计/签名链路需要的 {@link #formatHex(byte[])}（小写，无分隔符、无前缀）。
 * 与原 {@code Hex.formatHex(...)} 输出完全一致，保证哈希链/签名结果不变。</p>
 */
public final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    /** 将字节数组编码为小写十六进制字符串（长度 = 2 * len）。null 返回空串。 */
    public static String formatHex(byte[] data) {
        if (data == null) {
            return "";
        }
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = DIGITS[v >>> 4];
            out[i * 2 + 1] = DIGITS[v & 0x0F];
        }
        return new String(out);
    }
}
