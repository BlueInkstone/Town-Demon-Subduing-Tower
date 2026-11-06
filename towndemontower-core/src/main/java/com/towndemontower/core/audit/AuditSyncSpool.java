package com.towndemontower.core.audit;

import com.towndemontower.core.contract.TimeProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * [core] 审计同步暂存区（V1.16）：只把"没送出去的"锚点快照留在本地。
 *
 * <p><b>为什么需要它</b>：{@link HttpAnchorReplica#push} 是同步推、失败即抛。远端一抖，
 * 这条锚点就没送到——虽然下次锚定会推更新的快照追平，但"当时那一刻"的第三方证据已经缺了；
 * 如果进程随后崩溃、或副本长期不可用，缺口会一直留在链上。</p>
 *
 * <p><b>分流规则</b>：</p>
 * <ul>
 *   <li>推送<b>成功</b> → 远端已落盘，本地<b>不</b>留副本（避免把"完整数据"重复堆积在本地）；</li>
 *   <li>推送<b>失败</b> → 写一条 {@code .rec} 到暂存目录，重启后仍在，由重试补传；</li>
 *   <li>补传成功 → 删除该文件（远端已有，本地不再保留）；</li>
 *   <li>超过 {@code maxAttempts} → 移入 {@code dead/} 子目录保留，等人工处理，<b>绝不删除</b>。</li>
 * </ul>
 *
 * <p><b>崩溃安全</b>：每条先写 {@code .tmp} 再原子改名；半截文件不会进入待重试集合。
 * 目录里读不出来的记录会被移到 {@code dead/}（而不是静默跳过），避免"看起来没有待办、实际有坏数据"。</p>
 *
 * <p><b>清理</b>：时间与容量双阈值，先删最旧；{@code dead/} 下的记录同样受限，
 * 否则"永不删除"会变成另一种形式的磁盘耗尽。</p>
 */
public final class AuditSyncSpool {

    private static final String HEADER = "#SYNC-SPOOL v1";
    private static final String DEAD_DIR = "dead";
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final System.Logger LOG = System.getLogger(AuditSyncSpool.class.getName());

    /** 默认保留 7 天。 */
    public static final long DEFAULT_RETENTION_MS = 7L * 24 * 3600 * 1000;
    /** 默认容量上限 64 MiB（锚点行很小，这已经能容纳数十万条）。 */
    public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;
    /** 默认最多自动重试 5 次，之后进 dead/。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final Path dir;
    private final List<AnchorReplica> replicas;
    private final long retentionMs;
    private final long maxBytes;
    private final int maxAttempts;
    private final TimeProvider time;

    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong captured = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();

    public AuditSyncSpool(Path dir, List<AnchorReplica> replicas) {
        this(dir, replicas, DEFAULT_RETENTION_MS, DEFAULT_MAX_BYTES, DEFAULT_MAX_ATTEMPTS,
                TimeProvider.system());
    }

    public AuditSyncSpool(Path dir, List<AnchorReplica> replicas,
                          long retentionMs, long maxBytes, int maxAttempts, TimeProvider time) {
        if (dir == null) throw new IllegalArgumentException("暂存目录不能为空");
        if (retentionMs < 0) throw new IllegalArgumentException("保留时长不能为负");
        if (maxBytes < 0) throw new IllegalArgumentException("容量上限不能为负");
        if (maxAttempts < 1) throw new IllegalArgumentException("重试次数至少为 1");
        this.dir = dir;
        this.replicas = replicas == null ? List.of() : List.copyOf(replicas);
        this.retentionMs = retentionMs;
        this.maxBytes = maxBytes;
        this.maxAttempts = maxAttempts;
        this.time = time == null ? TimeProvider.system() : time;
        try {
            Files.createDirectories(dir);
            Files.createDirectories(deadDir());
        } catch (IOException e) {
            throw new IllegalStateException("审计同步暂存目录初始化失败：" + dir, e);
        }
    }

    public Path dir() {
        return dir;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public long retentionMs() {
        return retentionMs;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /**
     * 记录一条推送失败。
     *
     * <p>幂等性不保证：同一条快照失败两次会生成两个文件。宁可重复补传（远端 2xx 落盘语义下
     * 重复写入是覆盖同一锚点，不会制造两条链），也不要因为去重逻辑漏掉证据。</p>
     *
     * @param replicaName  失败的副本名（重试按名字找回实例）
     * @param snapshotLine {@link AnchorSnapshot#line()} 原文
     * @param error        失败原因（只用于诊断）
     */
    public synchronized void capture(String replicaName, String snapshotLine, String error) {
        long now = time.now().toEpochMilli();
        long n = seq.incrementAndGet();
        String name = "spool-" + now + "-" + n + ".rec";
        Path target = dir.resolve(name);
        Path tmp = dir.resolve(name + ".tmp");
        String body = HEADER + "\n"
                + "replica=" + ENC.encodeToString(replicaName.getBytes(StandardCharsets.UTF_8)) + "\n"
                + "attempts=0\n"
                + "createdAt=" + now + "\n"
                + "lastError=" + ENC.encodeToString((error == null ? "" : error)
                        .getBytes(StandardCharsets.UTF_8)) + "\n"
                + "snapshot=" + snapshotLine + "\n";
        try {
            Files.writeString(tmp, body, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            move(tmp, target);
            captured.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING,
                    "审计同步失败，已写入本地暂存：replica=" + replicaName + " file=" + name);
        } catch (IOException e) {
            // 暂存写不进去也不能把治理主链一起拖垮；计数留给 /integrity 与日志。
            failed.incrementAndGet();
            LOG.log(System.Logger.Level.ERROR,
                    "审计同步暂存写入失败（该锚点只能用后续快照追平）：" + name, e);
            throw new IllegalStateException("审计同步暂存写入失败：" + target, e);
        }
    }

    /** 当前待补传记录（含已进 dead 的不算）。损坏文件会被移到 dead/ 并继续扫描其余记录。 */
    public synchronized List<PendingSync> pending() {
        List<PendingSync> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> recs = files.filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().endsWith(".rec"))
                    .sorted()
                    .toList();
            for (Path p : recs) {
                PendingSync one = read(p);
                if (one == null) {
                    quarantine(p, "记录无法解析");
                    continue;
                }
                out.add(one);
            }
        } catch (IOException e) {
            throw new IllegalStateException("审计同步暂存读取失败：" + dir, e);
        }
        return List.copyOf(out);
    }

    /**
     * {@code dead/} 下保留的记录（补传次数达上限，等待人工补传）。
     *
     * <p>只读：不重试、不清理、不改写。运维界面需要先看到"哪些没送出去"，
     * 才谈得上人工处理；把 dead 也当成 pending 自动重试会失去上限的意义。</p>
     */
    public synchronized List<PendingSync> dead() {
        List<PendingSync> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(deadDir())) {
            List<Path> recs = files.filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().endsWith(".rec"))
                    .sorted()
                    .toList();
            for (Path p : recs) {
                PendingSync one = read(p);
                if (one != null) out.add(one);
            }
        } catch (IOException e) {
            throw new IllegalStateException("审计同步 dead 读取失败：" + deadDir(), e);
        }
        return List.copyOf(out);
    }

    /**
     * 补传一轮：逐条推给对应副本。
     *
     * <p>副本已从配置里移除时不重试（{@code skipped}），记录继续留着——把数据删掉等于
     * "配置改了就丢证据"。</p>
     */
    public synchronized RetryResult retryOnce() {
        List<AnchorReplica> targets = replicas;
        int ok = 0, retry = 0, dead = 0, skip = 0;
        for (PendingSync p : pending()) {
            AnchorReplica replica = find(p.replica());
            if (replica == null) {
                skip++;
                skipped.incrementAndGet();
                continue;
            }
            retried.incrementAndGet();
            try {
                replica.push(AnchorSnapshot.parse(p.snapshotLine(), "spool:" + p.file().getFileName()));
                delete(p.file());
                ok++;
                delivered.incrementAndGet();
                LOG.log(System.Logger.Level.INFO,
                        "审计同步补传成功：replica=" + p.replica() + " file=" + p.file().getFileName());
            } catch (RuntimeException e) {
                int attempts = p.attempts() + 1;
                if (attempts >= maxAttempts) {
                    moveToDead(p.file());
                    dead++;
                    deadLettered.incrementAndGet();
                    LOG.log(System.Logger.Level.ERROR,
                            "审计同步补传超过 " + maxAttempts + " 次，转入 dead/："
                                    + p.file().getFileName() + "（" + describe(e) + "）");
                } else {
                    rewrite(p, attempts, describe(e));
                    retry++;
                    failed.incrementAndGet();
                    LOG.log(System.Logger.Level.WARNING,
                            "审计同步补传失败（第 " + attempts + " 次）："
                                    + p.file().getFileName() + "（" + describe(e) + "）");
                }
            }
        }
        return new RetryResult(ok, retry, dead, skip);
    }

    /** 到期清理：先按保留时长，再按容量上限（都从最旧的开始删）。 */
    public synchronized CleanupResult cleanup() {
        long now = time.now().toEpochMilli();
        long cutoff = now - retentionMs;
        int byAge = 0, bySize = 0;
        List<PendingSync> all = pendingIncludingDead();
        all.sort(Comparator.comparingLong(PendingSync::createdAt));

        List<PendingSync> remain = new ArrayList<>();
        for (PendingSync p : all) {
            if (p.createdAt() < cutoff) {
                if (delete(p.file())) byAge++;
            } else {
                remain.add(p);
            }
        }
        long total = remain.stream().mapToLong(p -> sizeOf(p.file())).sum();
        while (total > maxBytes && !remain.isEmpty()) {
            PendingSync oldest = remain.remove(0);
            long size = sizeOf(oldest.file());
            if (delete(oldest.file())) {
                total -= size;
                bySize++;
            }
        }
        if (byAge + bySize > 0) {
            dropped.addAndGet(byAge + bySize);
            LOG.log(System.Logger.Level.INFO, "审计同步暂存清理：按时间 " + byAge
                    + " 条、按容量 " + bySize + " 条，剩余 " + remain.size() + " 条 / " + total + " 字节");
        }
        return new CleanupResult(byAge, bySize, remain.size(), total);
    }

    public Stats stats() {
        List<PendingSync> live = pending();
        int dead = 0;
        long deadBytes = 0;
        try (Stream<Path> files = Files.list(deadDir())) {
            for (Path p : files.filter(p -> p.getFileName().toString().endsWith(".rec")).toList()) {
                dead++;
                deadBytes += sizeOf(p);
            }
        } catch (IOException ignored) {
            // 目录不存在或不可读：dead 计数按 0 报，不影响主链
        }
        long bytes = live.stream().mapToLong(p -> sizeOf(p.file())).sum();
        return new Stats(live.size(), bytes, dead, deadBytes,
                captured.get(), retried.get(), delivered.get(), failed.get(),
                deadLettered.get(), dropped.get(), skipped.get());
    }

    // ------------------------------------------------------------------

    private List<PendingSync> pendingIncludingDead() {
        List<PendingSync> out = new ArrayList<>(pending());
        try (Stream<Path> files = Files.list(deadDir())) {
            for (Path p : files.filter(p -> p.getFileName().toString().endsWith(".rec")).toList()) {
                PendingSync one = read(p);
                if (one != null) out.add(one);
            }
        } catch (IOException ignored) {
            // 没有 dead 目录时按空处理
        }
        return out;
    }

    private PendingSync read(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !HEADER.equals(lines.get(0).trim())) return null;
            String replica = null, snapshot = null, error = "";
            int attempts = 0;
            long createdAt = 0;
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq);
                String v = line.substring(eq + 1);
                switch (k) {
                    case "replica" -> replica = new String(DEC.decode(v), StandardCharsets.UTF_8);
                    case "snapshot" -> snapshot = v;
                    case "attempts" -> attempts = Integer.parseInt(v);
                    case "createdAt" -> createdAt = Long.parseLong(v);
                    case "lastError" -> error = new String(DEC.decode(v), StandardCharsets.UTF_8);
                    default -> { }
                }
            }
            if (replica == null || snapshot == null || createdAt <= 0) return null;
            return new PendingSync(file, replica, snapshot, attempts, createdAt, error);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void rewrite(PendingSync p, int attempts, String error) {
        String name = p.file().getFileName().toString();
        Path tmp = p.file().resolveSibling(name + ".tmp");
        String body = HEADER + "\n"
                + "replica=" + ENC.encodeToString(p.replica().getBytes(StandardCharsets.UTF_8)) + "\n"
                + "attempts=" + attempts + "\n"
                + "createdAt=" + p.createdAt() + "\n"
                + "lastError=" + ENC.encodeToString((error == null ? "" : error)
                        .getBytes(StandardCharsets.UTF_8)) + "\n"
                + "snapshot=" + p.snapshotLine() + "\n";
        try {
            Files.writeString(tmp, body, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            move(tmp, p.file());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "审计同步暂存重写失败（将按原次数重试）：" + name, e);
        }
    }

    /** 移入 dead/：原文件内容一字不改地保留，供人工排查与手动补传。 */
    private void moveToDead(Path file) {
        String name = file.getFileName().toString();
        try {
            move(file, deadDir().resolve(name));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR,
                    "审计同步暂存移入 dead/ 失败（保留原文件）：" + name, e);
        }
    }

    private void quarantine(Path file, String reason) {
        String name = file.getFileName().toString();
        try {
            move(file, deadDir().resolve(name));
            LOG.log(System.Logger.Level.WARNING,
                    "审计同步暂存记录损坏，已移入 dead/：" + name + "（" + reason + "）");
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR,
                    "审计同步暂存损坏记录无法移动（保留原文件）：" + name, e);
        }
    }

    private AnchorReplica find(String name) {
        for (AnchorReplica r : replicas) {
            if (r.name().equals(name)) return r;
        }
        return null;
    }

    private Path deadDir() {
        return dir.resolve(DEAD_DIR);
    }

    private boolean delete(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "审计同步暂存删除失败：" + file, e);
            return false;
        }
    }

    private long sizeOf(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String describe(RuntimeException e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }

    /** 一条待补传记录。 */
    public record PendingSync(Path file, String replica, String snapshotLine,
                              int attempts, long createdAt, String lastError) {
    }

    /** 一轮补传结果。 */
    public record RetryResult(int delivered, int retrying, int deadLettered, int skipped) {
    }

    /** 一轮清理结果。 */
    public record CleanupResult(int droppedByAge, int droppedBySize, int remaining, long remainingBytes) {
    }

    /** 可观测计数（只增不减，用于日志与端点）。 */
    public record Stats(int pending, long pendingBytes, int dead, long deadBytes,
                        long captured, long retried, long delivered, long failed,
                        long deadLettered, long dropped, long skipped) {
    }
}
