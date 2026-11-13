package com.towndemontower.core.audit;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [core] 内存版哈希链审计存储。仅追加写。
 *
 * <p>每条记录摘要 = {@code 算法(seq|action|payload|prevHash)}，算法由密钥决定：</p>
 * <ul>
 *   <li>配置了 {@code TOWNDEMONTOWER_AUDIT_KEY} → <b>HMAC-SHA256</b>，可防"整链重算"；</li>
 *   <li>未配置 → SHA-256（旧行为），只能发现单点篡改，自检会报 {@code AUDIT_KEY_MISSING}。</li>
 * </ul>
 *
 * <p>{@link #verifyChain()} 用<b>同一种模式</b>重算并与存下来的摘要比对，
 * 因此"用 SHA-256 重算整链"的伪造在 HMAC 模式下必然失败。</p>
 */
public class HashChainAuditStore implements AuditStore {

    /**
     * 时间戳格式：ISO-8601 UTC、毫秒精度、定长。
     *
     * <p>定长 + UTC 是有意为之：字符串比较即时间比较，过滤时不必解析时区，
     * 也不会因为 {@code Instant.toString()} 自动省略毫秒而导致长短不一。</p>
     */
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final List<AuditRecord> records = new CopyOnWriteArrayList<>();
    private final AtomicLong seqGen = new AtomicLong(0);
    private volatile String lastHash = "GENESIS";
    /** 可空：为空表示退化到 SHA-256 模式。 */
    private final byte[] auditKey;

    /** 生产路径：密钥只从环境变量取。 */
    public HashChainAuditStore() {
        this(AuditCrypto.envKey());
    }

    /** 显式注入（单测 / 内嵌场景）：传 {@code null} 即退化模式，不做静默补默认密钥。 */
    public HashChainAuditStore(byte[] auditKey) {
        this.auditKey = (auditKey == null || auditKey.length == 0) ? null : auditKey.clone();
    }

    /**
     * 包内写入口：只给落盘实现与同包测试使用。
     *
     * <p>V1.9.2 之前这是 {@code public} 且挂在 {@link AuditStore} 接口上，任何调用方都能
     * 自带 seq/hash 造记录进链。收权后对外只剩 {@link #appendAuto}，序号与哈希由本类计算。</p>
     */
    synchronized void appendRecord(AuditRecord record) {
        records.add(record);
        if (record.hash != null) lastHash = record.hash;
    }

    /** 便捷方法：调用方只给 action/payload，哈希与前驱由本存储计算。 */
    public synchronized void appendAuto(String action, String payload) {
        long seq = seqGen.incrementAndGet();
        String ts = now();
        String h = AuditCrypto.chain(auditKey,
                AuditRecord.hashInput(seq, action, payload, lastHash, ts));
        AuditRecord r = new AuditRecord(seq, action, payload, lastHash, h, ts);
        records.add(r);
        lastHash = h;
        onAppended(r);
    }

    /** 当前时刻的审计时间戳（定长 ISO-8601 UTC）。 */
    static String now() {
        return TS_FORMAT.format(Instant.now());
    }

    /**
     * 落盘扩展点：内存实现什么都不做。
     *
     * <p>有了它，{@link FileAuditStore} 不必再为"刚写的是哪条"去调 {@code all()}
     * 拷贝整条链再取末元素——那条路径每写一条审计就 O(n) 复制一次列表，
     * 记录越多越慢，且慢在治理主链上。</p>
     */
    protected void onAppended(AuditRecord record) {
    }

    /** 末条记录的哈希；空链返回 {@code GENESIS}。落盘实现追平锚点时要用。 */
    protected String lastHash() {
        return lastHash;
    }

    /**
     * 把待落盘的审计刷出去。
     *
     * <p>内存实现没有待落盘的东西，因此是空实现——存在这个方法的唯一理由是让
     * {@code destroyMethod="flushAnchor"} 在两种实现上都成立，不必在装配处分支。</p>
     */
    public void flushAnchor() {
    }

    @Override
    public List<AuditRecord> all() {
        return new ArrayList<>(records);
    }

    @Override
    public synchronized boolean verifyChain() {
        String prev = "GENESIS";
        for (AuditRecord r : records) {
            // 口径与 appendAuto 共用 hashInput：带 ts 的按五段重算，旧记录按四段重算
            String expected = AuditCrypto.chain(auditKey,
                    AuditRecord.hashInput(r.seq, r.action, r.payload, prev, r.ts));
            if (!expected.equals(r.hash)) return false;
            prev = r.hash;
        }
        return true;
    }

    /** 当前链摘要模式（供自检与端点报告）。 */
    public AuditCrypto.Mode mode() {
        return AuditCrypto.modeOf(auditKey);
    }

    /** 是否有可用审计密钥（无密钥时只能查篡改、挡不住重算）。 */
    public boolean keyAvailable() {
        return auditKey != null;
    }

    /** 保留给外部工具与旧数据的纯 SHA-256。 */
    public static String sha256(String input) {
        return AuditCrypto.sha256(input);
    }
}
