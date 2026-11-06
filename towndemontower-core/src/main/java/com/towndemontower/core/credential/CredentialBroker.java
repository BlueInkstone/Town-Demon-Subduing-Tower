package com.towndemontower.core.credential;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [V1.2] 凭据代理运行时（CredentialBroker）。
 * 职责：凭据注册/轮换/吊销、使用期注入、日志脱敏、审计仅留存哈希（绝不存明文）。
 * 设计约束：禁止硬编码密钥；明文只在 inject() 调用瞬间存在，其余时刻以 ${name} 占位。
 */
public class CredentialBroker {
    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    public void register(String name, String secret) {
        if (name == null || secret == null) throw new IllegalArgumentException("name/secret required");
        secrets.put(name, secret);
    }

    public void rotate(String name, String newSecret) {
        if (!secrets.containsKey(name)) throw new IllegalStateException("unknown credential: " + name);
        secrets.put(name, newSecret);
    }

    public void revoke(String name) {
        secrets.remove(name);
    }

    public boolean exists(String name) {
        return secrets.containsKey(name);
    }

    /** 把文本中的明文密钥替换为 ${name} 占位符，用于日志/审计脱敏。 */
    public String redact(String text) {
        String out = text;
        for (Map.Entry<String, String> e : secrets.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            out = out.replace(e.getValue(), "${" + e.getKey() + "}");
        }
        return out;
    }

    /** 把 ${name} 占位符还原为密钥，仅在使用时调用（最小暴露窗口）。 */
    public String inject(String template) {
        String out = template;
        for (Map.Entry<String, String> e : secrets.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    /** 仅返回密钥 SHA-256 前缀，供审计留存，绝不返回明文。 */
    public Map<String, String> secretHashes() {
        Map<String, String> h = new ConcurrentHashMap<>();
        for (String name : secrets.keySet()) h.put(name, hash(secrets.get(name)));
        return h;
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.substring(0, 12);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
