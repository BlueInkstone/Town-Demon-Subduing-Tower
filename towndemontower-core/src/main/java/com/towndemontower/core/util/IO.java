package com.towndemontower.core.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Java 8 兼容的流读取工具（替代 JDK 9 的 {@code InputStream.readAllBytes()}）。
 */
public final class IO {
    private static final int BUFFER_SIZE = 8192;

    private IO() {
    }

    /** 读取输入流全部字节（等价于 JDK 9 的 {@code InputStream.readAllBytes()}）。 */
    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
