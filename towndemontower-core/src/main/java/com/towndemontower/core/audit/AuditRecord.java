package com.towndemontower.core.audit;

import java.util.Objects;

/**
 * [core] 单条审计记录。追加写、不可篡改，逐条 SHA-256 链式引用前一条哈希。
 *
 * <p><b>关于 {@link #ts}（V2.2 新增）</b>：此前审计只有"序号 + 动作 + 哈希 + 内容"，
 * 时间信息藏在 payload 里、由各写入方自己决定记不记，导致排障时"这条是什么时候发生的"
 * 无法回答，管理接口也没法按时间段筛选。</p>
 *
 * <p>时间戳<b>参与链摘要</b>：带 ts 的记录按 {@code seq|action|payload|ts|prevHash} 计算，
 * 旧记录（ts 为空）沿用原来的四段口径。这样改一行 ts 就会被 {@code verifyChain()} 判成篡改，
 * 同时磁盘上的历史日志仍然逐条自洽，不需要迁移。</p>
 *
 * <p>时间格式固定为 ISO-8601 UTC、毫秒精度（{@code 2026-11-13T01:23:45.678Z}）。
 * 定长 + UTC 的好处是可以直接按字符串比较先后，过滤时不必解析时区。</p>
 *
 * <p>{@link #equals(Object)} 故意<b>不</b>比较 ts：旧记录没有这个字段，
 * 把 ts 纳入相等性会让"同一条记录的新旧视图不相等"，而记录的身份由 seq 与 hash 唯一确定。</p>
 */
public class AuditRecord {
    public final long seq;
    public final String action;
    public final String payload;
    public final String prevHash;
    public final String hash;
    /** 写入时刻（ISO-8601 UTC，毫秒精度）；V2.2 之前的旧记录为 {@code null}。 */
    public final String ts;

    /** 旧口径（无时间戳）——保留给历史数据与既有测试构造。 */
    public AuditRecord(long seq, String action, String payload, String prevHash, String hash) {
        this(seq, action, payload, prevHash, hash, null);
    }

    public AuditRecord(long seq, String action, String payload, String prevHash, String hash, String ts) {
        this.seq = seq;
        this.action = action;
        this.payload = payload;
        this.prevHash = prevHash;
        this.hash = hash;
        this.ts = (ts == null || ts.isEmpty()) ? null : ts;
    }

    /**
     * 链摘要的输入口径（写链与校验必须共用这一处，否则两边口径一漂移就会误报篡改）。
     *
     * @param ts 为 {@code null}/空 时走旧的四段口径
     */
    public static String hashInput(long seq, String action, String payload, String prevHash, String ts) {
        if (ts == null || ts.isEmpty()) {
            return seq + "|" + action + "|" + payload + "|" + prevHash;
        }
        return seq + "|" + action + "|" + payload + "|" + ts + "|" + prevHash;
    }

    /** 是否带时间戳（旧记录为 {@code false}，管理接口据此提示"无时间"）。 */
    public boolean hasTimestamp() {
        return ts != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditRecord)) return false;
        AuditRecord r = (AuditRecord) o;
        return seq == r.seq && Objects.equals(action, r.action)
                && Objects.equals(payload, r.payload)
                && Objects.equals(prevHash, r.prevHash) && Objects.equals(hash, r.hash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seq, action, payload, prevHash, hash);
    }
}
