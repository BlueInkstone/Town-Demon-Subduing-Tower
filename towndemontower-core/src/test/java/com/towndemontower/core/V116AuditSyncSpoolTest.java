package com.towndemontower.core;

import com.towndemontower.core.audit.AnchorReplica;
import com.towndemontower.core.audit.AnchorSnapshot;
import com.towndemontower.core.audit.AuditSyncSpool;
import com.towndemontower.core.contract.TimeProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * [V1.16] 审计同步暂存区：失败落盘、重启回放、补传、dead/、双阈值清理、可观测计数。
 */
public class V116AuditSyncSpoolTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        defaults();
        invalidArgsRejected();
        capturePersistsAndReplays();
        retryDeliversAndDeletes();
        retryFailureIncrementsAttempts();
        deadLetterAfterMaxAttempts();
        unknownReplicaIsSkipped();
        cleanupByAge();
        cleanupBySize();
        cleanupKeepsFreshData();
        corruptRecordQuarantined();
        deadParticipatesInCleanup();
        captureFailureSurfaces();
        concurrentCapture();
        statsReflectActivity();

        System.out.println();
        System.out.println("RESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void defaults() {
        AuditSyncSpool spool = new AuditSyncSpool(tmp("defaults"), List.of());
        check("默认-保留 7 天", spool.retentionMs() == 7L * 24 * 3600 * 1000);
        check("默认-容量 64 MiB", spool.maxBytes() == 64L * 1024 * 1024);
        check("默认-重试 5 次", spool.maxAttempts() == 5);
    }

    static void invalidArgsRejected() throws Exception {
        check("参数-保留时长为负被拒", throwsIae(() ->
                new AuditSyncSpool(tmp("neg-ret"), List.of(), -1, 100, 5, TimeProvider.system())));
        check("参数-容量为负被拒", throwsIae(() ->
                new AuditSyncSpool(tmp("neg-size"), List.of(), 1, -1, 5, TimeProvider.system())));
        check("参数-重试次数为 0 被拒", throwsIae(() ->
                new AuditSyncSpool(tmp("zero-attempt"), List.of(), 1, 100, 0, TimeProvider.system())));
    }

    static void capturePersistsAndReplays() throws Exception {
        Path dir = tmp("capture");
        Clock clock = new Clock(1000);
        AuditSyncSpool spool = new AuditSyncSpool(dir, List.of(fake("http://a")), 100000,
                1_000_000, 5, clock);
        spool.capture("http://a", snapshot(7), "HTTP 503");

        check("落盘-生成 .rec 文件", Files.list(dir).anyMatch(p ->
                p.getFileName().toString().endsWith(".rec")));
        check("落盘-无半截 .tmp", Files.list(dir).noneMatch(p ->
                p.getFileName().toString().endsWith(".tmp")));
        List<AuditSyncSpool.PendingSync> pending = spool.pending();
        check("落盘-待补传 1 条", pending.size() == 1);
        check("落盘-副本名保留", "http://a".equals(pending.get(0).replica()));
        check("落盘-快照行可解析",
                AnchorSnapshot.parse(pending.get(0).snapshotLine(), "t").records() == 7);
        check("落盘-失败原因保留", "HTTP 503".equals(pending.get(0).lastError()));
        check("落盘-初始 attempts 为 0", pending.get(0).attempts() == 0);

        AuditSyncSpool restarted = new AuditSyncSpool(dir, List.of(fake("http://a")), 100000,
                1_000_000, 5, clock);
        check("重启-待补传记录不丢", restarted.pending().size() == 1);
    }

    static void retryDeliversAndDeletes() throws Exception {
        Path dir = tmp("deliver");
        FlakyReplica replica = new FlakyReplica("http://a", 0);
        AuditSyncSpool spool = new AuditSyncSpool(dir, List.of(replica), 100000,
                1_000_000, 5, TimeProvider.system());
        spool.capture("http://a", snapshot(3), "boom");

        AuditSyncSpool.RetryResult r = spool.retryOnce();
        check("补传-成功 1 条", r.delivered() == 1);
        check("补传-无剩余", spool.pending().isEmpty());
        check("补传-副本收到快照", replica.pushed.size() == 1);
        check("补传-文件已删除", recordCount(dir) == 0);
        check("补传-stats 记录已送达", spool.stats().delivered() == 1);
    }

    static void retryFailureIncrementsAttempts() throws Exception {
        Path dir = tmp("retry-fail");
        FlakyReplica replica = new FlakyReplica("http://a", 100);
        AuditSyncSpool spool = new AuditSyncSpool(dir, List.of(replica), 100000,
                1_000_000, 5, TimeProvider.system());
        spool.capture("http://a", snapshot(3), "boom");

        AuditSyncSpool.RetryResult r = spool.retryOnce();
        check("失败-计入 retrying", r.retrying() == 1);
        check("失败-文件保留", spool.pending().size() == 1);
        check("失败-attempts 递增", spool.pending().get(0).attempts() == 1);
        check("失败-原因更新", spool.pending().get(0).lastError().contains("模拟失败"));
        check("失败-stats 累计", spool.stats().failed() == 1);
    }

    static void deadLetterAfterMaxAttempts() throws Exception {
        Path dir = tmp("dead");
        FlakyReplica replica = new FlakyReplica("http://a", 100);
        AuditSyncSpool spool = new AuditSyncSpool(dir, List.of(replica), 100000,
                1_000_000, 3, TimeProvider.system());
        spool.capture("http://a", snapshot(9), "boom");

        AuditSyncSpool.RetryResult r1 = spool.retryOnce();
        check("重试-第 1 次失败", r1.retrying() == 1);
        AuditSyncSpool.RetryResult r2 = spool.retryOnce();
        check("重试-第 2 次失败", r2.retrying() == 1);
        AuditSyncSpool.RetryResult r3 = spool.retryOnce();
        check("重试-第 3 次进 dead", r3.deadLettered() == 1);
        check("dead-待补传清空", spool.pending().isEmpty());
        check("dead-文件保留", deadCount(dir) == 1);
        check("dead-stats 计数", spool.stats().dead() == 1);

        String body = Files.readString(deadFile(dir), StandardCharsets.UTF_8);
        check("dead-原始快照未被改写", body.contains("snapshot=" + snapshot(9)));
    }

    static void unknownReplicaIsSkipped() throws Exception {
        Path dir = tmp("unknown");
        AuditSyncSpool spool = new AuditSyncSpool(dir, List.of(fake("http://other")),
                100000, 1_000_000, 5, TimeProvider.system());
        spool.capture("http://a", snapshot(1), "boom");

        AuditSyncSpool.RetryResult r = spool.retryOnce();
        check("未知副本-跳过", r.skipped() == 1);
        check("未知副本-数据保留", spool.pending().size() == 1);
        check("未知副本-stats 计数", spool.stats().skipped() == 1);
    }

    static void cleanupByAge() throws Exception {
        Path dir = tmp("age");
        Clock clock = new Clock(10_000);
        AuditSyncSpool spool = new AuditSyncSpool(dir, List.of(), 1000, 1_000_000, 5, clock);
        spool.capture("http://a", snapshot(1), "x");
        clock.set(12_000);
        spool.capture("http://a", snapshot(2), "x");

        AuditSyncSpool.CleanupResult r = spool.cleanup();
        check("按时间-删 1 条", r.droppedByAge() == 1);
        check("按时间-留 1 条", r.remaining() == 1);
    }

    static void cleanupBySize() throws Exception {
        Path dir = tmp("size");
        Clock clock = new Clock(10_000);
        AuditSyncSpool spool = new AuditSyncSpool(dir, List.of(), 100000, 600, 5, clock);
        for (int i = 0; i < 6; i++) {
            clock.advance(1);
            spool.capture("http://a", snapshot(i), "x");
        }
        long before = totalBytes(dir);
        check("按容量-初始超限", before > 600);

        AuditSyncSpool.CleanupResult r = spool.cleanup();
        check("按容量-发生删除", r.droppedBySize() > 0);
        check("按容量-降到上限内", totalBytes(dir) <= 600);
        check("按容量-保留的仍可读", !spool.pending().isEmpty());
    }

    static void cleanupKeepsFreshData() throws Exception {
        Path dir = tmp("fresh");
        Clock clock = new Clock(10_000);
        AuditSyncSpool spool = new AuditSyncSpool(dir, List.of(), 100000, 1_000_000, 5, clock);
        spool.capture("http://a", snapshot(1), "x");
        AuditSyncSpool.CleanupResult r = spool.cleanup();
        check("未超期-不删", r.droppedByAge() == 0 && r.droppedBySize() == 0);
        check("未超期-记录仍在", spool.pending().size() == 1);
    }

    static void corruptRecordQuarantined() throws Exception {
        Path dir = tmp("corrupt");
        AuditSyncSpool spool = new AuditSyncSpool(dir, List.of(), 100000, 1_000_000, 5,
                TimeProvider.system());
        spool.capture("http://a", snapshot(1), "x");
        Files.writeString(dir.resolve("spool-999-bad.rec"), "garbage\n", StandardCharsets.UTF_8);

        List<AuditSyncSpool.PendingSync> pending = spool.pending();
        check("损坏-不进待补传", pending.size() == 1);
        check("损坏-移入 dead/", deadCount(dir) == 1);
        check("损坏-好记录仍可读", pending.get(0).attempts() == 0);
    }

    static void deadParticipatesInCleanup() throws Exception {
        Path dir = tmp("dead-age");
        Clock clock = new Clock(10_000);
        FlakyReplica replica = new FlakyReplica("http://a", 100);
        AuditSyncSpool spool = new AuditSyncSpool(dir, List.of(replica), 1000,
                1_000_000, 1, clock);
        spool.capture("http://a", snapshot(1), "x");
        spool.retryOnce();
        check("dead-已生成", deadCount(dir) == 1);

        clock.set(20_000);
        AuditSyncSpool.CleanupResult r = spool.cleanup();
        check("dead-超期被清理", r.droppedByAge() == 1);
        check("dead-目录为空", deadCount(dir) == 0);
    }

    static void captureFailureSurfaces() throws Exception {
        // 用一个"目录被占"的路径制造写入失败：把目标目录名当成普通文件占位
        Path base = tmp("capture-fail");
        Path fakeDir = base.resolve("blocked");
        Files.writeString(fakeDir, "x", StandardCharsets.UTF_8);
        boolean threw = false;
        try {
            new AuditSyncSpool(fakeDir, List.of(), 1000, 1000, 5, TimeProvider.system());
        } catch (IllegalStateException e) {
            threw = true;
        }
        check("初始化失败-明确抛错", threw);
    }

    static void concurrentCapture() throws Exception {
        Path dir = tmp("concurrent");
        AuditSyncSpool spool = new AuditSyncSpool(dir, List.of(fake("http://a")), 100000,
                10_000_000, 5, TimeProvider.system());
        int threads = 4, each = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < each; i++) {
                        spool.capture("http://a", snapshot(id * 100 + i), "x");
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();

        check("并发-无异常", error.get() == null);
        check("并发-记录不丢", spool.pending().size() == threads * each);
        check("并发-无半截文件", Files.list(dir).noneMatch(p ->
                p.getFileName().toString().endsWith(".tmp")));
    }

    static void statsReflectActivity() throws Exception {
        Path dir = tmp("stats");
        AuditSyncSpool spool = new AuditSyncSpool(dir, List.of(fake("http://a")), 100000,
                1_000_000, 5, TimeProvider.system());
        spool.capture("http://a", snapshot(1), "x");
        spool.capture("http://a", snapshot(2), "x");
        AuditSyncSpool.Stats s = spool.stats();
        check("stats-待补传 2", s.pending() == 2);
        check("stats-captured 2", s.captured() == 2);
        check("stats-字节数为正", s.pendingBytes() > 0);
    }

    // ------------------------------------------------------------------

    static class FlakyReplica implements AnchorReplica {
        final String name;
        int failsLeft;
        final List<String> pushed = new ArrayList<>();
        volatile String lastError;

        FlakyReplica(String name, int failsLeft) {
            this.name = name;
            this.failsLeft = failsLeft;
        }

        @Override public String name() { return name; }
        @Override public boolean offHost() { return true; }
        @Override public String lastError() { return lastError; }

        @Override
        public void push(AnchorSnapshot snapshot) {
            if (failsLeft > 0) {
                failsLeft--;
                lastError = "模拟失败";
                throw new IllegalStateException("模拟失败：" + name);
            }
            pushed.add(snapshot.line());
            lastError = null;
        }

        @Override
        public AnchorSnapshot pull() {
            return null;
        }
    }

    static AnchorReplica fake(String name) {
        return new FlakyReplica(name, 0);
    }

    /** 可推进的时钟（暂存区的时间判断都走它）。 */
    static class Clock implements TimeProvider {
        final AtomicLong millis;

        Clock(long start) { this.millis = new AtomicLong(start); }

        void set(long v) { millis.set(v); }
        void advance(long d) { millis.addAndGet(d); }

        @Override public java.time.Instant now() {
            return java.time.Instant.ofEpochMilli(millis.get());
        }
    }

    static String snapshot(int records) {
        return new AnchorSnapshot(records, "H" + records, "MAC", 1000).line();
    }

    static Path tmp(String name) {
        try {
            Path base = Path.of("target", "v116-spool");
            Files.createDirectories(base);
            return Files.createTempDirectory(base, name + "-");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static long recordCount(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".rec")).count();
        }
    }

    static long deadCount(Path dir) throws Exception {
        Path dead = dir.resolve("dead");
        if (!Files.exists(dead)) return 0;
        try (Stream<Path> s = Files.list(dead)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".rec")).count();
        }
    }

    static Path deadFile(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir.resolve("dead"))) {
            return s.findFirst().orElseThrow();
        }
    }

    static long totalBytes(Path dir) throws Exception {
        long total = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                if (!Files.isRegularFile(p)) continue;
                total += Files.size(p);
            }
        }
        Path dead = dir.resolve("dead");
        if (Files.exists(dead)) {
            try (Stream<Path> s = Files.list(dead)) {
                for (Path p : s.toList()) {
                    if (Files.isRegularFile(p)) total += Files.size(p);
                }
            }
        }
        return total;
    }

    static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
