package com.towndemontower.core.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * [core] 可落盘的哈希链审计存储。
 *
 * <p>继承 {@link HashChainAuditStore}，保留原有内存链语义（因此旧代码里
 * {@code getBean(HashChainAuditStore.class)}、{@code all().size()}、{@code verifyChain()} 全部照旧），
 * 额外把每条记录<b>追加写</b>到文件：</p>
 * <pre>
 * #SEGMENT &lt;uuid&gt; prev=GENESIS
 * &lt;seq&gt;\t&lt;action&gt;\t&lt;hash&gt;\t&lt;payload&gt;
 * </pre>
 *
 * <p><b>V1.9 / I4 新增两层</b>：</p>
 * <ol>
 *   <li><b>HMAC 链</b>：有 {@code TOWNDEMONTOWER_AUDIT_KEY} 时摘要走 HMAC-SHA256，
 *       没有密钥则退化为 SHA-256（旧行为），启动校验沿用同一算法；</li>
 *   <li><b>外锚</b>：同级 {@code .anchor} 文件记录"总条数 + 末条哈希 + MAC"，
 *       挡住"删尾巴"与"整段替换"——这两种情况链本身是自洽的，只有链外的参照物能发现。</li>
 * </ol>
 *
 * <p><b>V1.9.2 / I4-b 锚点追平策略</b>：默认仍然是<b>每写一条就追平锚点</b>
 * （{@code anchorBatch=1}）。改批量是有代价的——锚点一旦落后，落后的那几条被直接删除
 * 就没人能发现（链内依然自洽）。所以批量化做成显式配置，需要时才开：</p>
 * <ul>
 *   <li>{@code anchorBatch = N}：每 N 条追平一次，最多落后 N-1 条；</li>
 *   <li>{@code anchorIntervalMs = T}：距上次追平超过 T 毫秒也追平一次，
 *       让低频场景（几分钟一条审计）不至于一直卡在"未入锚"状态；</li>
 *   <li>{@code flushAnchor()}：立即追平（进程关闭时由容器调用）。</li>
 * </ul>
 * <p>即便落后，锚点位置上的那条记录哈希仍会被校验（见 {@link AuditAnchor#mismatch}），
 * 所以"前缀被整段重写"在落后期间同样能被发现，落后丢掉的只是尾部截断的可见性。</p>
 *
 * <p><b>V1.10 / I4-c 锚点副本</b>：本地锚点与日志同盘，挡不住"整目录回滚到旧快照"
 * ——那份旧日志与旧锚点彼此自洽，本地检查全都会通过。构造时传入 {@link AnchorReplica}
 * 列表后，每次锚定都会把快照推一份出去，启动时再拉回来对账（详见 {@link AuditAnchor}）。
 * 空表即完全退回旧行为。</p>
 *
 * <p><b>升级兼容</b>：旧日志里的记录是用 SHA-256 算的，若一上来就用 HMAC 校验会判成篡改。
 * 因此校验时先按当前密钥算，不匹配再按 SHA-256 算一次——后者通过说明是旧段，
 * 记录一条告警后继续（旧段的安全性仍是 SHA-256 级，新记录才受 HMAC 保护）。</p>
 *
 * <p>文件权限：在支持 POSIX 的平台设为 {@code rw-------}（仅属主可读写），
 * Windows 上忽略（依赖目录 ACL）。</p>
 */
public class FileAuditStore extends HashChainAuditStore {

    private static final String SEGMENT_PREFIX = "#SEGMENT";
    private static final String GENESIS = "GENESIS";

    /** 每写一条就追平锚点：证据最强，代价是每条多一次写盘 + 原子改名。 */
    public static final int ANCHOR_EVERY_WRITE = 1;
    /** 默认不批量（保持 v1.9 的证据强度）。 */
    public static final int DEFAULT_ANCHOR_BATCH = ANCHOR_EVERY_WRITE;
    /** 默认不按时间追平（只在批量模式下才有意义）。 */
    public static final long DEFAULT_ANCHOR_INTERVAL_MS = 0L;

    private final Path file;
    private final byte[] auditKey;
    private final AuditAnchor anchor;
    /** 推送失败的锚点本地暂存区；{@code null} 表示未启用（V1.16）。 */
    private final AuditSyncSpool syncSpool;
    private final ReentrantLock ioLock = new ReentrantLock();
    private final int anchorBatch;
    private final long anchorIntervalMs;
    /** 已写入文件的记录总数（锚点用），跨段累加。 */
    private long written;
    /** 锚点已经覆盖到的条数；{@code written - anchored} 就是当前落后量。 */
    private long anchored;
    private long lastAnchorAtNanos;
    /**
     * 启动核对那一轮的副本状态。
     *
     * <p>保留它的原因：构造末尾会把锚点重写一遍并推送（那正是修复"副本落后"的动作），
     * 于是 {@link #replicaStates()} 里的落后量立刻变成 0。而"启动时副本落后多少"
     * 本身是有用的诊断（副本是不是长期没收到推送？），不该被这次修复动作抹掉。</p>
     */
    private List<AnchorReplicaState> startupReplicaStates = List.of();

    public FileAuditStore(Path file) {
        this(file, AuditCrypto.envKey());
    }

    /** 显式注入密钥（单测 / 内嵌场景）；{@code null} 表示退化到 SHA-256 链。 */
    public FileAuditStore(Path file, byte[] auditKey) {
        this(file, auditKey, DEFAULT_ANCHOR_BATCH, DEFAULT_ANCHOR_INTERVAL_MS);
    }

    /**
     * @param anchorBatch      每 N 条追平一次锚点，{@code <= 1} 表示每条都追平
     * @param anchorIntervalMs 距上次追平超过该毫秒数也追平一次，{@code <= 0} 表示不按时间
     */
    public FileAuditStore(Path file, byte[] auditKey, int anchorBatch, long anchorIntervalMs) {
        this(file, auditKey, anchorBatch, anchorIntervalMs, List.of(), false);
    }

    /**
     * V1.10 / I4-c：带锚点副本。
     *
     * @param replicas        锚点副本（异地证据）；空表即退回"只有本地锚点"的 v1.9.2 行为
     * @param replicaRequired 副本不可读/不可写时是否拒绝（启动与写入两条路径都按它判断）
     */
    public FileAuditStore(Path file, byte[] auditKey, int anchorBatch, long anchorIntervalMs,
                          List<AnchorReplica> replicas, boolean replicaRequired) {
        this(file, auditKey, anchorBatch, anchorIntervalMs, replicas, replicaRequired, null);
    }

    /**
     * V1.16：带本地同步暂存区。推送失败的锚点会写入 {@code spool}，由外部定时补传；
     * {@code spool == null} 时行为与 v1.10 完全一致（只记失败状态，不落暂存）。
     */
    public FileAuditStore(Path file, byte[] auditKey, int anchorBatch, long anchorIntervalMs,
                          List<AnchorReplica> replicas, boolean replicaRequired,
                          AuditSyncSpool spool) {
        super(auditKey);
        this.file = file;
        this.auditKey = (auditKey == null || auditKey.length == 0) ? null : auditKey.clone();
        this.syncSpool = spool;
        this.anchor = new AuditAnchor(
                file.resolveSibling(file.getFileName() + ".anchor"), this.auditKey,
                replicas, replicaRequired, spool);
        this.anchorBatch = Math.max(ANCHOR_EVERY_WRITE, anchorBatch);
        this.anchorIntervalMs = Math.max(0L, anchorIntervalMs);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);

            AuditAnchor.Anchor existing = anchor.read();
            long records = 0;
            String tail = GENESIS;
            String hashAtAnchor = null;
            if (Files.exists(file)) {
                Scan s = scan(file, this.auditKey, existing == null ? -1 : existing.records());
                if (s.problem != null) {
                    throw new IllegalStateException("审计链在磁盘上不连续，拒绝启动：" + s.problem);
                }
                records = s.records;
                tail = s.lastHash;
                hashAtAnchor = s.hashAtProbe;
            }
            // 这里放在 if 外面：日志文件被整体删掉时 records=0，锚点若说以前有 N 条，
            // 同样是"被截断"，不该因为文件不存在就跳过校验然后重新盖上一条 0 条的新锚点
            String mismatch = AuditAnchor.mismatch(existing, records, hashAtAnchor);
            if (mismatch != null) {
                throw new IllegalStateException("审计外锚校验失败，拒绝启动：" + mismatch);
            }
            // 本地锚点只能证明"本地这套文件自洽"——整个目录被回滚到旧快照时它一样自洽。
            // 副本在这里的作用就是把"当时确实写到过 N 条"这句外部证词拿回来比对。
            this.startupReplicaStates = List.copyOf(anchor.verifyReplicas(records, tail));

            Files.writeString(file, "\n" + SEGMENT_PREFIX + " " + UUID.randomUUID()
                            + " prev=" + lastSegmentTail(file) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            restrictPermissions(file);
            // 初始化 / 校正锚点：旧日志没有锚点时，以"当前状态"为基线（首次受保护点）
            anchor.write(records, tail);
            this.written = records;
            this.anchored = records;
            this.lastAnchorAtNanos = System.nanoTime();
        } catch (IOException e) {
            throw new IllegalStateException("审计日志初始化失败：" + file, e);
        }
    }

    public Path file() {
        return file;
    }

    /** 外锚文件路径（排查与运维用）。 */
    public Path anchorFile() {
        return anchor.path();
    }

    /** 外锚记录的条数；无锚点返回 -1。读的是磁盘上的真实值，不会顺手帮你追平。 */
    public long anchorRecords() {
        AuditAnchor.Anchor a = anchor.read();
        return a == null ? -1 : a.records();
    }

    /** 外锚记录的末条哈希；无锚点返回 {@code null}。用于与副本对账。 */
    public String anchorHash() {
        AuditAnchor.Anchor a = anchor.read();
        return a == null ? null : a.hash();
    }

    /** 每 N 条追平一次锚点；{@code 1} 表示每条都追平。 */
    public int anchorBatch() {
        return anchorBatch;
    }

    /** 按时间追平的间隔毫秒数；{@code 0} 表示不按时间。 */
    public long anchorIntervalMs() {
        return anchorIntervalMs;
    }

    /** 当前有多少条记录还没进锚点（批量模式下正常大于 0）。 */
    public synchronized long anchorLag() {
        return written - anchored;
    }

    /** 已装配的锚点副本（空表 = 只有本地锚点）。 */
    public List<AnchorReplica> replicas() {
        return anchor.replicas();
    }

    /** 推送失败的本地暂存区；未启用时为 {@code null}。 */
    public AuditSyncSpool syncSpool() {
        return syncSpool;
    }

    /**
     * 副本的最近一次已知状态（启动核对 + 之后每次推送刷新）。
     *
     * <p>与 {@link #startupReplicaStates()} 的区别：这份是"现在怎样"，
     * 那份是"启动那一刻怎样"。监控该盯这份，排障回溯看那份。</p>
     */
    public List<AnchorReplicaState> replicaStates() {
        return anchor.replicaStates();
    }

    /** 启动核对那一轮的副本状态（构造后固定，不随后续推送变化）。 */
    public List<AnchorReplicaState> startupReplicaStates() {
        return startupReplicaStates;
    }

    /** 仍处于失败状态的副本 → 原因。 */
    public java.util.Map<String, String> replicaErrors() {
        return anchor.replicaErrors();
    }

    /** 累计锚点副本推送失败次数（含已恢复的）。 */
    public long replicaFailures() {
        return anchor.replicaFailures();
    }

    /**
     * 立即把锚点追平到最新一条。
     *
     * <p>进程关闭时由容器调用（{@code destroyMethod}）。不追平的后果是：最后那几条
     * 审计不在锚点覆盖范围内，被删掉时外锚看不出来——正常退出不该留这种尾巴。</p>
     */
    @Override
    public synchronized void flushAnchor() {
        ioLock.lock();
        try {
            if (written == anchored) return;
            anchor.write(written, lastHash());
            anchored = written;
            lastAnchorAtNanos = System.nanoTime();
        } finally {
            ioLock.unlock();
        }
    }

    @Override
    protected void onAppended(AuditRecord r) {
        String line = r.seq + "\t" + safe(r.action) + "\t" + safe(r.hash) + "\t" + safe(r.payload) + "\n";
        ioLock.lock();
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            written++;
            if (anchorDue()) {
                anchor.write(written, r.hash);
                anchored = written;
                lastAnchorAtNanos = System.nanoTime();
            }
        } catch (IOException e) {
            // 落盘失败不改变已做出的裁决，但必须让调用方知道证据链断了
            throw new IllegalStateException("审计写入失败：" + file, e);
        } finally {
            ioLock.unlock();
        }
    }

    /** 是否该追平锚点：条数到阈值，或距上次追平超过时间阈值。 */
    private boolean anchorDue() {
        if (anchorBatch <= ANCHOR_EVERY_WRITE) return true;
        if (written - anchored >= anchorBatch) return true;
        return anchorIntervalMs > 0
                && System.nanoTime() - lastAnchorAtNanos >= anchorIntervalMs * 1_000_000L;
    }

    /* ------------------------------------------------------------------ */

    /** 扫描结果。 */
    private static final class Scan {
        long records;
        String lastHash = GENESIS;
        /** 第 {@code probeAt} 条记录的哈希；条数不足时保持 {@code null}。 */
        String hashAtProbe;
        String problem;
        boolean legacySeen;
    }

    /**
     * 逐段逐条重算：先按当前密钥，不匹配再按 SHA-256 判"旧段"。
     *
     * @param probeAt 需要取回哈希的记录序号（1 起）；{@code <= 0} 表示不需要
     */
    private static Scan scan(Path file, byte[] key, long probeAt) throws IOException {
        Scan out = new Scan();
        String prev = GENESIS;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) continue;
            if (line.startsWith(SEGMENT_PREFIX)) {
                // 段头不影响记录计数；每段内部重新从 GENESIS 起链
                prev = GENESIS;
                continue;
            }
            String[] p = line.split("\t", 4);
            if (p.length < 4) {
                out.problem = "格式损坏：" + line;
                return out;
            }
            long seq;
            try {
                seq = Long.parseLong(p[0]);
            } catch (NumberFormatException e) {
                out.problem = "序号不可解析：" + line;
                return out;
            }
            String input = seq + "|" + p[1] + "|" + p[3] + "|" + prev;
            if (!AuditCrypto.chain(key, input).equals(p[2])) {
                // 回退判定：升级前写入的旧段用 SHA-256
                if (key != null && AuditCrypto.sha256(input).equals(p[2])) {
                    out.legacySeen = true;
                } else {
                    out.problem = "第 " + seq + " 条哈希不匹配（内容被改写）";
                    return out;
                }
            }
            prev = p[2];
            out.records++;
            out.lastHash = p[2];
            if (out.records == probeAt) out.hashAtProbe = p[2];
        }
        return out;
    }

    /** 最后一段的尾哈希（用于段头，跨段可追溯）。文件尚不存在时返回 GENESIS。 */
    private static String lastSegmentTail(Path file) throws IOException {
        if (!Files.exists(file)) return GENESIS;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) continue;
            if (line.startsWith(SEGMENT_PREFIX)) return GENESIS;
            String[] p = line.split("\t", 4);
            if (p.length >= 3) return p[2];
        }
        return GENESIS;
    }

    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> perms = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 不支持 POSIX 权限，交由目录 ACL；此处不阻断
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    /** 便于测试：读取文件里的有效记录行数（不含段头）。 */
    public int fileRecordCount() {
        try {
            int n = 0;
            for (String line : new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))) {
                if (line != null && !line.isBlank() && !line.startsWith(SEGMENT_PREFIX)) n++;
            }
            return n;
        } catch (IOException e) {
            return -1;
        }
    }
}
