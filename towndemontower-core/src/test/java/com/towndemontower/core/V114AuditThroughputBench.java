package com.towndemontower.core;


import java.nio.file.Paths;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.FileAnchorReplica;
import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * [V1.14] 审计写入吞吐压测（v1.14 收尾时列出的技术债之一）。
 *
 * <p><b>为什么要压</b>：v1.13 / v1.14 把计划审批接进治理链后，一次计划生命周期会写
 * 三条审计（{@code PLAN_REQUESTED} / {@code PLAN_APPROVED} / {@code PLAN_REVISED}）。
 * 审计写入在主链上是同步的，因此"一条审计多少钱"直接决定计划审批能扛多少量。</p>
 *
 * <p><b>测什么</b>：只测 {@link AuditStore#appendAuto} 这一个真实写入口，不测
 * {@code all()} / {@code verifyChain()}——后两者是 O(n) 的读取路径，混进写入计时会失真。
 * 五个场景覆盖"内存 / 落盘 / 批量锚定 / 副本扇出 / HMAC 与 SHA-256"的组合：</p>
 *
 * <ol>
 *   <li>内存·SHA-256：无密钥退化模式，纯链计算成本；</li>
 *   <li>内存·HMAC：有密钥，多一次 HMAC 初始化；</li>
 *   <li>文件·每条锚定：{@code anchorBatch=1}，默认生产配置，每条都写日志 + 重写锚点；</li>
 *   <li>文件·批量锚定：{@code anchorBatch=100}，用"最多落后 99 条"换吞吐；</li>
 *   <li>文件·每条锚定 + 文件副本：再叠一次追加写，看副本扇出的代价。</li>
 * </ol>
 *
 * <p><b>断言口径</b>：吞吐数字只打印、不做绝对值断言（机器与磁盘差异会让它变成随机失败）。
 * 稳定断言只覆盖"结果正确"：条数、链完整、文件行数、锚点位置、副本末条。
 * 另设一条极宽的灾难护栏（默认配置 p99 &gt; 200ms/条 即失败），用来发现
 * "某次改动把单条写入变成秒级"这类回退，而不是评价快慢。</p>
 *
 * <p>用法：{@code java -cp ... com.towndemontower.core.V114AuditThroughputBench [记录数]}，
 * 默认 2000 条。输出目录在 {@code target/} 下，属构建产物，不参与清理。</p>
 */
public class V114AuditThroughputBench {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    /** 压测密钥：直接注入，绕过环境变量（SecretPolicy 只在 envKey 路径校验强度）。 */
    private static final byte[] KEY =
            "bench-audit-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    /** 灾难护栏：默认配置下单条写入的 p99 上限（毫秒）。只为发现数量级回退。 */
    private static final double CATASTROPHIC_P99_MS = 200.0;

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        String tag = Long.toString(System.nanoTime());
        Path base = Paths.get("target", "bench-audit-" + tag);

        System.out.println("审计写入吞吐压测：n=" + n + " 条/场景，目录 " + base);
        System.out.println();

        List<Result> results = new ArrayList<>();
        results.add(memory(n, null, "内存·SHA-256"));
        results.add(memory(n, KEY, "内存·HMAC"));

        Result fileDefault = file(base.resolve("default"), n, KEY, 1, false, "文件·每条锚定（默认）");
        results.add(fileDefault);
        results.add(file(base.resolve("batch100"), n, KEY, 100, false, "文件·批量锚定(100)"));
        results.add(file(base.resolve("replica"), n, KEY, 1, true, "文件·每条锚定+副本"));

        System.out.println();
        printTable(results);

        System.out.println();
        printWriteAmplification(fileDefault, results.get(3), results.get(4));

        // 稳定断言：结果正确
        check("每条锚定-日志行数一致", fileDefault.logRecords == n);
        check("每条锚定-链完整", fileDefault.chainOk);
        check("每条锚定-锚点已追平", fileDefault.anchorRecords == n);
        check("批量锚定-日志行数一致", results.get(3).logRecords == n);
        check("批量锚定-链完整", results.get(3).chainOk);
        check("批量锚定-flush 后锚点追平", results.get(3).anchorRecords == n);
        check("副本-账本末条与本地一致", results.get(4).replicaRecords == n);

        // 灾难护栏：只挡数量级回退
        check("每条锚定-p99 未出现数量级回退", fileDefault.p99Nanos() < CATASTROPHIC_P99_MS * 1_000_000L);

        // 计划审批视角：一次计划生命周期约 3 条审计
        double perAuditMs = fileDefault.avgNanos() / 1_000_000.0;
        System.out.println();
        System.out.printf(Locale.ROOT,
                "计划审批换算（默认配置）：单次生命周期约 3 条审计，同步写入约 %.3f ms；"
                        + "1000 次计划审批约 %.2f s%n",
                perAuditMs * 3, perAuditMs * 3 * 1000 / 1000.0);

        System.out.println();
        System.out.println("RESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // 场景
    // ------------------------------------------------------------------

    static Result memory(int n, byte[] key, String name) {
        HashChainAuditStore store = new HashChainAuditStore(key);
        long[] lat = new long[n];
        long total = run(store, n, lat);
        Result r = new Result(name, n, total, lat);
        r.chainOk = store.verifyChain();
        r.logRecords = store.all().size();
        return r;
    }

    static Result file(Path dir, int n, byte[] key, int batch, boolean withReplica, String name)
            throws Exception {
        Files.createDirectories(dir);
        Path log = dir.resolve("audit.log");
        List<com.towndemontower.core.audit.AnchorReplica> replicas = withReplica
                ? Arrays.asList(new FileAnchorReplica(dir.resolve("anchor-ledger.log"), key))
                : Arrays.asList();
        FileAuditStore store = new FileAuditStore(log, key, batch, 0L, replicas, false);

        long[] lat = new long[n];
        long total = run(store, n, lat);
        // 批量模式下最后一段未进锚点，关闭前要 flush（与容器 destroyMethod 行为一致）
        store.flushAnchor();

        Result r = new Result(name, n, total, lat);
        r.chainOk = store.verifyChain();
        r.logRecords = store.fileRecordCount();
        r.anchorRecords = store.anchorRecords();
        r.logBytes = size(log);
        r.anchorBytes = size(store.anchorFile());
        r.anchorWrites = (n + batch - 1) / batch;
        if (withReplica) {
            FileAnchorReplica rep = (FileAnchorReplica) replicas.get(0);
            com.towndemontower.core.audit.AnchorSnapshot snap = rep.pull();
            r.replicaRecords = snap == null ? -1 : snap.records();
            r.replicaBytes = size(rep.ledgerFile());
        }
        return r;
    }

    /** 统一计时：只包 appendAuto，避免把读取路径混进来。 */
    static long run(AuditStore store, int n, long[] lat) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            String payload = payload(i);
            long a = System.nanoTime();
            store.appendAuto("SKILL_INVOKED", payload);
            lat[i] = System.nanoTime() - a;
        }
        return System.nanoTime() - t0;
    }

    /** 贴近真实的一条业务审计，约 110 字节。 */
    static String payload(int i) {
        return "agent=agent-" + (i % 7)
                + "|tool=applyPatch"
                + "|task=task-" + (i % 13)
                + "|decision=ALLOW"
                + "|mode=DEV_EXECUTION"
                + "|code=OK"
                + "|detail=benchmark-record-" + i;
    }

    // ------------------------------------------------------------------
    // 输出
    // ------------------------------------------------------------------

    static void printTable(List<Result> results) {
        System.out.println("场景                          条数   总耗时ms    吞吐条/秒   p50us   p95us   p99us   日志字节/条");
        System.out.println("--------------------------------------------------------------------------------------------");
        for (Result r : results) {
            System.out.printf(Locale.ROOT, "%-28s %6d %10.1f %11.1f %7.1f %7.1f %7.1f %12.1f%n",
                    r.name, r.n, r.totalNanos / 1_000_000.0, r.throughput(),
                    r.p50Nanos() / 1000.0, r.p95Nanos() / 1000.0, r.p99Nanos() / 1000.0,
                    r.logBytes == 0 ? 0.0 : (double) r.logBytes / r.n);
        }
    }

    static void printWriteAmplification(Result everyWrite, Result batch100, Result replica) {
        System.out.println("写放大（每条审计触发的落盘动作）：");
        System.out.printf(Locale.ROOT,
                "  默认(batch=1)   : 日志追加 1 次/条 + 锚点重写 %.0f 次/条（日志 %.1f B/条）%n",
                (double) everyWrite.anchorWrites / everyWrite.n,
                everyWrite.n == 0 ? 0.0 : (double) everyWrite.logBytes / everyWrite.n);
        System.out.printf(Locale.ROOT,
                "  批量(batch=100) : 日志追加 1 次/条 + 锚点重写 %.3f 次/条，吞吐为默认的 %.1f 倍%n",
                (double) batch100.anchorWrites / batch100.n,
                batch100.throughput() / everyWrite.throughput());
        System.out.printf(Locale.ROOT,
                "  叠加文件副本    : 锚点每次再追加 1 行（账本 %.1f B/条），吞吐为默认的 %.1f 倍%n",
                replica.n == 0 ? 0.0 : (double) replica.replicaBytes / replica.n,
                replica.throughput() / everyWrite.throughput());
        System.out.println("  注：锚点重写是 tmp 写入 + 原子改名，默认配置下是主要成本项。");
    }

    static long size(Path p) {
        try {
            return Files.exists(p) ? Files.size(p) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------

    static final class Result {
        final String name;
        final int n;
        final long totalNanos;
        final long[] latency;
        boolean chainOk;
        int logRecords;
        long anchorRecords = -1;
        long replicaRecords = -1;
        long logBytes;
        long anchorBytes;
        long replicaBytes;
        long anchorWrites;

        Result(String name, int n, long totalNanos, long[] latency) {
            this.name = name;
            this.n = n;
            this.totalNanos = totalNanos;
            this.latency = latency;
        }

        double throughput() {
            return totalNanos <= 0 ? 0.0 : n / (totalNanos / 1e9);
        }

        double avgNanos() {
            return n == 0 ? 0.0 : (double) totalNanos / n;
        }

        long pct(double p) {
            long[] sorted = latency.clone();
            Arrays.sort(sorted);
            int idx = Math.max(0, (int) Math.ceil(sorted.length * p) - 1);
            return sorted[idx];
        }

        long p50Nanos() { return pct(0.50); }
        long p95Nanos() { return pct(0.95); }
        long p99Nanos() { return pct(0.99); }
    }
}
