package com.towndemontower.core;

import com.towndemontower.core.audit.AuditCrypto;
import com.towndemontower.core.audit.AuditRecord;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * [core/test] V1.9.2 / I4-b —— 审计写入收权 + 锚点追平。
 *
 * <p>验证六组不变量：</p>
 * <ol>
 *   <li><b>收权</b>：{@link AuditStore} 上不再有能自带 hash 的写方法；
 *       {@code appendRecord} 对包外不可见（编译期约束，用反射表达）；</li>
 *   <li><b>唯一写入口</b>：{@code appendAuto} 自己算 seq / prevHash / hash，链自洽；</li>
 *   <li><b>默认不变松</b>：默认仍是每写一条追平锚点，{@code anchorLag()==0}；</li>
 *   <li><b>批量追平</b>：按条数与按时间两条触发路径，{@code flushAnchor()} 立即追平；</li>
 *   <li><b>落后期间的保护</b>：锚点位置哈希仍被校验 → 前缀整段重写仍被发现；</li>
 *   <li><b>批量化的代价</b>：尾部落差内被截断<b>发现不了</b>——这条要如实断言，
 *       不能只在文档里写；以及"日志文件整体删除"这次补上了检出。</li>
 * </ol>
 */
public class V19AuditSealTest {

    static int pass = 0, fail = 0;

    static final byte[] KEY = "seal-test-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }

    static void eq(String name, Object actual, Object expected) {
        boolean ok = (expected == null) ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + " expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) throws Exception {
        interfaceSealed();
        autoWritesOwnHashes();
        defaultStillEveryWrite();
        batchByCount();
        batchByTime();
        lagProtectsPrefix();
        costOfBatching();
        deletedLogDetected();
        errorOnMissingAnchorBaseline();
        memoryFlushIsNoop();
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
    }

    /* ---------- ① 收权 ---------- */

    static void interfaceSealed() throws Exception {
        List<Method> declared = Arrays.asList(AuditStore.class.getDeclaredMethods());
        check("收权-接口无 append 方法",
                declared.stream().noneMatch(m -> "append".equals(m.getName())));
        check("收权-接口无 appendRecord",
                declared.stream().noneMatch(m -> "appendRecord".equals(m.getName())));

        long voidWriters = declared.stream()
                .filter(m -> !m.isDefault())
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getReturnType() == void.class)
                .count();
        eq("收权-接口只有一个写方法", voidWriters, 1L);
        check("收权-写方法是 appendAuto",
                AuditStore.class.getMethod("appendAuto", String.class, String.class) != null);

        // appendRecord 只包内可见：public 查找必须失败（编译期挡住包外调用）
        boolean publicLookupFailed;
        try {
            HashChainAuditStore.class.getMethod("appendRecord", AuditRecord.class);
            publicLookupFailed = false;
        } catch (NoSuchMethodException expected) {
            publicLookupFailed = true;
        }
        check("收权-appendRecord 非 public", publicLookupFailed);

        Method internal = HashChainAuditStore.class.getDeclaredMethod("appendRecord", AuditRecord.class);
        check("收权-appendRecord 仍存在（包内用）", internal != null);
        check("收权-appendRecord 非 public 修饰",
                !Modifier.isPublic(internal.getModifiers()) && !Modifier.isProtected(internal.getModifiers()));

        // 构造记录本身不改变任何存储状态：没有写入口，伪造对象就是死物
        HashChainAuditStore s = new HashChainAuditStore(KEY);
        s.appendAuto("A", "1");
        int before = s.all().size();
        new AuditRecord(99, "FAKE", "payload", "GENESIS", "deadbeef");
        eq("收权-裸构造记录不入链", s.all().size(), before);
        check("收权-链仍可验", s.verifyChain());

        // 内存实现也有 flushAnchor（装配处不必分支）
        s.flushAnchor();
        check("收权-flushAnchor 对内存实现可用", true);
    }

    /* ---------- ② 唯一写入口自己算哈希 ---------- */

    static void autoWritesOwnHashes() {
        HashChainAuditStore s = new HashChainAuditStore(KEY);
        s.appendAuto("A", "1");
        s.appendAuto("B", "2");

        List<AuditRecord> all = s.all();
        eq("写入-条数", all.size(), 2);
        eq("写入-序号从1连续", all.get(0).seq, 1L);
        eq("写入-序号连续", all.get(1).seq, 2L);
        eq("写入-首条前驱", all.get(0).prevHash, "GENESIS");
        eq("写入-后条前驱接前条", all.get(1).prevHash, all.get(0).hash);
        eq("写入-首条哈希由存储计算", all.get(0).hash,
                AuditCrypto.chain(KEY, "1|A|1|GENESIS"));
        check("写入-链可验", s.verifyChain());
    }

    /* ---------- ③ 默认仍是每条追平 ---------- */

    static void defaultStillEveryWrite() throws IOException {
        Path dir = Files.createTempDirectory("tower-seal-default");
        try {
            FileAuditStore s = new FileAuditStore(dir.resolve("audit.log"), KEY);
            eq("默认-批大小为1", s.anchorBatch(), 1);
            eq("默认-不按时间追平", s.anchorIntervalMs(), 0L);
            s.appendAuto("A", "1");
            eq("默认-写一条即入锚", s.anchorRecords(), 1L);
            eq("默认-无落后", s.anchorLag(), 0L);
            s.appendAuto("B", "2");
            eq("默认-两条都入锚", s.anchorRecords(), 2L);
            eq("默认-锚点等于文件条数", s.anchorRecords(), (long) s.fileRecordCount());
            check("默认-链可验", s.verifyChain());
        } finally {
            deleteTree(dir);
        }
    }

    /* ---------- ④ 批量：按条数 ---------- */

    static void batchByCount() throws IOException {
        Path dir = Files.createTempDirectory("tower-seal-batch");
        try {
            Path log = dir.resolve("audit.log");
            FileAuditStore s = new FileAuditStore(log, KEY, 3, 0);
            eq("批量-批大小", s.anchorBatch(), 3);
            eq("批量-初始锚点为0条", s.anchorRecords(), 0L);

            s.appendAuto("A", "1");
            eq("批量-第1条未入锚", s.anchorRecords(), 0L);
            eq("批量-落后1", s.anchorLag(), 1L);
            s.appendAuto("A", "2");
            eq("批量-第2条未入锚", s.anchorRecords(), 0L);
            s.appendAuto("A", "3");
            eq("批量-满3条入锚", s.anchorRecords(), 3L);
            eq("批量-落后归零", s.anchorLag(), 0L);

            s.appendAuto("A", "4");
            eq("批量-第4条又落后", s.anchorLag(), 1L);

            s.flushAnchor();
            eq("批量-手动追平到4", s.anchorRecords(), 4L);
            eq("批量-追平后无落后", s.anchorLag(), 0L);

            s.flushAnchor();
            eq("批量-重复追平幂等", s.anchorRecords(), 4L);

            FileAuditStore reopen = new FileAuditStore(log, KEY);
            eq("批量-重开不误报", reopen.fileRecordCount(), 4);
            check("批量-重开后链可验", reopen.verifyChain());
        } finally {
            deleteTree(dir);
        }
    }

    /* ---------- ⑤ 批量：按时间 ---------- */

    static void batchByTime() throws InterruptedException, IOException {
        Path dir = Files.createTempDirectory("tower-seal-time");
        try {
            FileAuditStore s = new FileAuditStore(dir.resolve("audit.log"), KEY, 100, 40);
            eq("定时-批大小100", s.anchorBatch(), 100);
            eq("定时-间隔40ms", s.anchorIntervalMs(), 40L);

            // 少于阈值不追平
            s.appendAuto("A", "1");
            eq("定时-刚写完不追平", s.anchorRecords(), 0L);

            Thread.sleep(70);
            s.appendAuto("A", "2");
            eq("定时-超时后追平", s.anchorRecords(), 2L);
            eq("定时-追平后无落后", s.anchorLag(), 0L);
        } finally {
            deleteTree(dir);
        }
    }

    /* ---------- ⑥ 落后期间：前缀仍受保护 ---------- */

    static void lagProtectsPrefix() throws IOException {
        Path dir = Files.createTempDirectory("tower-seal-lag");
        try {
            Path log = dir.resolve("audit.log");
            // 无密钥 → SHA-256，攻击者能自己重算整段（这正是要防的场景）
            FileAuditStore s = new FileAuditStore(log, null, 4, 0);
            for (int i = 1; i <= 6; i++) s.appendAuto("A", "p" + i);
            eq("落后-锚点停在第4条", s.anchorRecords(), 4L);
            eq("落后-落后2条", s.anchorLag(), 2L);

            // 攻击者：把前 4 条整段改写并重算哈希链，再把后面两条删掉
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            List<String> header = new ArrayList<>();
            List<String[]> recs = new ArrayList<>();
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                if (line.startsWith("#SEGMENT")) {
                    header.add(line);
                } else {
                    recs.add(line.split("\t", 4));
                }
            }
            List<String> rewritten = new ArrayList<>(header);
            String prev = "GENESIS";
            for (int i = 0; i < 4; i++) {
                String[] r = recs.get(i);
                long seq = Long.parseLong(r[0]);
                String payload = "tampered-" + seq;
                String h = AuditCrypto.chain(null, seq + "|" + r[1] + "|" + payload + "|" + prev);
                rewritten.add(seq + "\t" + r[1] + "\t" + h + "\t" + payload);
                prev = h;
            }
            Files.write(log, (String.join("\n", rewritten) + "\n").getBytes(StandardCharsets.UTF_8));

            String thrown = reopenFailure(log, null);
            check("落后-前缀重写被发现", thrown != null);
            check("落后-原因指向改写而非截断", thrown != null && thrown.contains("改写"));
        } finally {
            deleteTree(dir);
        }
    }

    /* ---------- ⑦ 批量的代价：尾部截断不可见 ---------- */

    static void costOfBatching() throws IOException {
        Path dir = Files.createTempDirectory("tower-seal-cost");
        try {
            Path log = dir.resolve("audit.log");
            FileAuditStore s = new FileAuditStore(log, KEY, 4, 0);
            for (int i = 1; i <= 6; i++) s.appendAuto("A", "p" + i);
            eq("代价-锚点在第4条", s.anchorRecords(), 4L);

            // 删掉未入锚的最后两条 → 锚点与日志"看起来"完全一致
            keepRecords(log, 4);
            String silent = reopenFailure(log, KEY);
            check("代价-落后窗口内截断确实发现不了（已知代价）", silent == null);

            // 继续删到 3 条（低于锚点）→ 立刻被抓
            keepRecords(log, 3);
            String caught = reopenFailure(log, KEY);
            check("代价-越过锚点即被发现", caught != null && caught.contains("截断"));
        } finally {
            deleteTree(dir);
        }
    }

    /* ---------- ⑧ 日志文件整体删除 ---------- */

    static void deletedLogDetected() throws IOException {
        Path dir = Files.createTempDirectory("tower-seal-deleted");
        try {
            Path log = dir.resolve("audit.log");
            FileAuditStore s = new FileAuditStore(log, KEY);
            s.appendAuto("A", "1");
            s.appendAuto("B", "2");
            s.appendAuto("C", "3");
            eq("删除-锚点3条", s.anchorRecords(), 3L);

            // 直接把日志删了（锚点还在）：旧实现会认为"没有日志"，重新盖一条 0 条的新锚点
            Files.delete(log);
            String thrown = reopenFailure(log, KEY);
            check("删除-日志被删拒绝启动", thrown != null);
            check("删除-原因指向截断", thrown != null && thrown.contains("截断"));
        } finally {
            deleteTree(dir);
        }
    }

    /* ---------- ⑨ 空基线不误报 ---------- */

    static void errorOnMissingAnchorBaseline() throws IOException {
        Path dir = Files.createTempDirectory("tower-seal-empty");
        try {
            Path log = dir.resolve("audit.log");
            FileAuditStore first = new FileAuditStore(log, KEY);
            eq("空基线-新日志锚点0条", first.anchorRecords(), 0L);
            FileAuditStore again = new FileAuditStore(log, KEY);
            eq("空基线-空日志重开不报错", again.fileRecordCount(), 0);
        } finally {
            deleteTree(dir);
        }
    }

    /* ---------- ⑩ 内存实现 flushAnchor ---------- */

    static void memoryFlushIsNoop() {
        HashChainAuditStore s = new HashChainAuditStore(KEY);
        s.appendAuto("A", "1");
        s.flushAnchor();
        eq("内存-flushAnchor 不改变链", s.all().size(), 1);
        check("内存-flushAnchor 后链可验", s.verifyChain());
    }

    /* ---------- 工具 ---------- */

    /** 重开日志；返回失败原因（{@code null} 表示没抛）。 */
    static String reopenFailure(Path log, byte[] key) {
        try {
            new FileAuditStore(log, key);
            return null;
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }

    /** 只保留前 n 条记录行，段头原样保留。 */
    static void keepRecords(Path log, int n) throws IOException {
        List<String> out = new ArrayList<>();
        int kept = 0;
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            if (line.startsWith("#SEGMENT")) {
                out.add(line);
                continue;
            }
            if (kept < n) {
                out.add(line);
                kept++;
            }
        }
        Files.write(log, (String.join("\n", out) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }
}
