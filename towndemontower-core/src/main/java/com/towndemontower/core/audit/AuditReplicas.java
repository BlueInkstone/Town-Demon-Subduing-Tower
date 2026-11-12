package com.towndemontower.core.audit;


import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * [core] 锚点副本配置解析（V1.10 / I4-c）。
 *
 * <p>配置写成一段字符串（Spring 里就是一个 property），每段一个副本，逗号或分号分隔：</p>
 *
 * <pre>
 * file:/var/lib/towndemontower/anchor.ledger            本机另一个路径（权限域隔离）
 * file+offhost:/mnt/audit-vault/anchor.ledger           运维声明"这块盘在别的失败域"
 * https://collector.example.com:8443/anchor             真正跨主机
 * </pre>
 *
 * <p><b>写错就报错，不静默跳过</b>：副本是证据链的一部分，拼错的 URL 被当成"没配"
 * 就会让运维以为"已经有异地副本了"。宁可启动失败。</p>
 */
public final class AuditReplicas {

    private AuditReplicas() {
    }

    /** 用环境变量里的令牌解析（HTTP 副本用）。 */
    public static List<AnchorReplica> parse(String spec, byte[] key) {
        return parse(spec, key, HttpAnchorReplica.envToken());
    }

    public static List<AnchorReplica> parse(String spec, byte[] key, String token) {
        List<AnchorReplica> out = new ArrayList<>();
        if (spec == null || spec.trim().isEmpty()) return new ArrayList<>(out);
        for (String raw : spec.split("[,;]")) {
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            out.add(one(entry, key, token));
        }
        return new ArrayList<>(out);
    }

    private static AnchorReplica one(String entry, byte[] key, String token) {
        String lower = entry.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return new HttpAnchorReplica(entry, token);
        }
        if (lower.startsWith("file+offhost:")) {
            return new FileAnchorReplica(path(entry, "file+offhost:"), key, true);
        }
        if (lower.startsWith("file:")) {
            return new FileAnchorReplica(path(entry, "file:"), key, false);
        }
        throw new IllegalArgumentException("锚点副本配置无法识别：[" + entry
                + "]；可用形式：file:<路径> / file+offhost:<路径> / http(s)://<主机>/<路径>");
    }

    private static Path path(String entry, String prefix) {
        String p = entry.substring(prefix.length()).trim();
        if (p.isEmpty()) {
            throw new IllegalArgumentException("锚点副本路径为空：[" + entry + "]");
        }
        return Paths.get(p);
    }
}
