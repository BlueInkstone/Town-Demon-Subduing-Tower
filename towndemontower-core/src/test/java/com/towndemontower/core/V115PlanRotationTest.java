package com.towndemontower.core;

import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.Arrays;

import com.towndemontower.core.approval.FilePlanApprovalStore;
import com.towndemontower.core.approval.PlanApproval;
import com.towndemontower.core.approval.PlanApprovalService;
import com.towndemontower.core.approval.PlanArtifact;
import com.towndemontower.core.approval.PlanStatus;
import com.towndemontower.core.approval.PlanStep;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * [V1.15] 计划单按大小轮转 / 归档。
 *
 * <p>钉住六件事：{@code maxBytes=0} 时行为不变；超阈值后主文件被压成"每单最后一条"；
 * 压缩前后回放结果一致；原文件进归档且不覆盖历史；极小阈值不丢单；压缩失败不破坏主文件。</p>
 */
public class V115PlanRotationTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        defaultKeepsAppendOnly();
        rotateCompactsAndArchives();
        rotationKeepsReplaySemantics();
        tinyThresholdLosesNothing();
        seqContinuesAfterRotation();
        archivesNeverOverwrite();
        corruptFileFailsClosedAndKeepsOriginal();
        concurrentWritesAndReads();
        invalidThresholdRejected();
        parentDirectoryCreated();

        System.out.println();
        System.out.println("RESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // 基础行为
    // ------------------------------------------------------------------

    static void defaultKeepsAppendOnly() throws Exception {
        Path dir = tmp("append");
        Path log = dir.resolve("plans.log");
        FilePlanApprovalStore store = new FilePlanApprovalStore(log);
        check("默认-阈值即 0", store.maxBytes() == 0L);
        for (int i = 0; i < 20; i++) {
            store.save(pending("P" + i, "task" + i));
        }
        check("默认-不产生归档", archiveCount(dir) == 0);
        check("默认-行数等于写入次数 + 1", recordLines(log) == 20);
    }

    static void rotateCompactsAndArchives() throws Exception {
        Path dir = tmp("rotate");
        Path log = dir.resolve("plans.log");
        FilePlanApprovalStore store = new FilePlanApprovalStore(log, 400);

        for (int i = 0; i < 10; i++) {
            String id = "P" + i;
            store.save(pending(id, "task" + i));
            store.save(pending(id, "task" + i).approved("human-1", "同意", Instant.now()));
        }
        List<PlanApproval> loaded = store.load();
        Map<String, PlanApproval> latest = new LinkedHashMap<>();
        for (PlanApproval p : loaded) latest.put(p.id, p);

        check("轮转-产生归档文件", archiveCount(dir) >= 1);
        check("轮转-主文件明显变短", recordLines(log) < 20);
        check("轮转-单数不丢", latest.size() == 10);
        check("轮转-每单状态为 APPROVED",
                latest.values().stream().allMatch(p -> p.status == PlanStatus.APPROVED));
        check("轮转-归档文件保留原始行", archivedRecordLines(dir) >= 1);
    }

    static void rotationKeepsReplaySemantics() throws Exception {
        Path dir = tmp("replay");
        Path log = dir.resolve("plans.log");
        FilePlanApprovalStore store = new FilePlanApprovalStore(log, 1200);
        PlanArtifact planned = PlanArtifact.of("修复两个文件", Arrays.asList(
                new PlanStep("applyPatch", "改 A"),
                new PlanStep("runTest", "跑 B")));

        PlanApprovalService writer = new PlanApprovalService(store, null);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String id = writer.request("agent-1", "task-" + i, planned);
            ids.add(id);
            writer.approve(id, "human-1", "同意");
        }
        // 触发至少一次压缩（不断追加修订版）
        for (String id : ids) {
            writer.revise("agent-1", "task-" + ids.indexOf(id), planned);
        }
        long archives = archiveCount(dir);
        check("回放-构造期间发生了轮转", archives >= 1);

        PlanApprovalService reader = new PlanApprovalService(new FilePlanApprovalStore(log), null);
        boolean allReplayed = true;
        for (int i = 0; i < ids.size(); i++) {
            PlanApproval p = reader.latest("agent-1", "task-" + i);
            if (p == null || p.plan == null || p.plan.steps.size() != 2) {
                allReplayed = false;
                break;
            }
        }
        check("回放-压缩后步骤不丢", allReplayed);
        check("回放-压缩后单号可查", reader.get(ids.get(0)) != null);
    }

    static void tinyThresholdLosesNothing() throws Exception {
        Path dir = tmp("tiny");
        Path log = dir.resolve("plans.log");
        // 阈值小于单行大小：每次 save 前都会压缩，用来看"最坏情况"是否丢数据。
        FilePlanApprovalStore store = new FilePlanApprovalStore(log, 60);
        for (int i = 0; i < 6; i++) {
            store.save(pending("T" + i, "task" + i));
        }
        List<PlanApproval> loaded = store.load();
        check("极小阈值-单数不丢", loaded.size() == 6);
        check("极小阈值-状态正确",
                loaded.stream().allMatch(p -> p.status == PlanStatus.PENDING));
        check("极小阈值-多次归档", archiveCount(dir) >= 1);
    }

    static void seqContinuesAfterRotation() throws Exception {
        Path dir = tmp("seq");
        Path log = dir.resolve("plans.log");
        FilePlanApprovalStore store = new FilePlanApprovalStore(log, 300);
        PlanApprovalService first = new PlanApprovalService(store, null);
        String oldId = null;
        for (int i = 0; i < 6; i++) {
            oldId = first.request("agent-1", "task-" + i, PlanArtifact.text("计划"));
        }
        long archives = archiveCount(dir);
        check("续号-写入过程发生了轮转", archives >= 1);

        PlanApprovalService restarted = new PlanApprovalService(new FilePlanApprovalStore(log), null);
        String newId = restarted.request("agent-1", "task-new", PlanArtifact.text("新计划"));
        check("续号-重启后可提交", newId != null && !newId.equals(oldId));
        check("续号-序号不回退", seqOf(newId) > seqOf(oldId));
    }

    static void archivesNeverOverwrite() throws Exception {
        Path dir = tmp("archive");
        Path log = dir.resolve("plans.log");
        FilePlanApprovalStore store = new FilePlanApprovalStore(log, 200);
        for (int i = 0; i < 20; i++) {
            store.save(pending("A" + i, "task" + i));
        }
        List<Path> archives = listArchives(dir);
        check("归档-至少两份", archives.size() >= 2);
        check("归档-文件名带 .archive",
                archives.stream().allMatch(p -> p.getFileName().toString().endsWith(".archive")));
        check("归档-互不覆盖", archives.stream().distinct().count() == archives.size());
        check("归档-每份都含表头", archives.stream().allMatch(V115PlanRotationTest::hasHeader));
    }

    // ------------------------------------------------------------------
    // 失败与并发
    // ------------------------------------------------------------------

    static void corruptFileFailsClosedAndKeepsOriginal() throws Exception {
        Path dir = tmp("corrupt");
        Path log = dir.resolve("plans.log");
        FilePlanApprovalStore store = new FilePlanApprovalStore(log, 40);
        store.save(pending("C1", "task1"));
        Files.write(log, ("not-a-valid-record\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        long sizeBefore = Files.size(log);
        boolean threw = false;
        try {
            store.save(pending("C2", "task2"));
        } catch (IllegalStateException | IllegalArgumentException e) {
            threw = true;
        }
        check("损坏-压缩前读取即拒绝", threw);
        check("损坏-主文件未被清空", Files.exists(log) && Files.size(log) == sizeBefore);
        boolean loadThrew = false;
        try {
            store.load();
        } catch (IllegalStateException | IllegalArgumentException e) {
            loadThrew = true;
        }
        check("损坏-load 拒绝而不是跳过", loadThrew);
    }

    static void concurrentWritesAndReads() throws Exception {
        Path dir = tmp("concurrent");
        Path log = dir.resolve("plans.log");
        FilePlanApprovalStore store = new FilePlanApprovalStore(log, 800);
        int threads = 4;
        int perThread = 15;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads + 1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        store.save(pending("C" + tid + "-" + i, "task" + i));
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 10; i++) store.load();
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            } finally {
                done.countDown();
            }
        }).start();

        start.countDown();
        done.await();

        check("并发-无异常", error.get() == null);
        List<PlanApproval> loaded = store.load();
        check("并发-单数不丢", loaded.size() == threads * perThread);
        check("并发-between 有轮转", archiveCount(dir) >= 1);
    }

    static void invalidThresholdRejected() throws Exception {
        Path dir = tmp("invalid");
        boolean threw = false;
        try {
            new FilePlanApprovalStore(dir.resolve("plans.log"), -1);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("参数-负阈值被拒", threw);
    }

    static void parentDirectoryCreated() throws Exception {
        Path base = tmp("mkdir");
        Path log = base.resolve("nested/deeper/plans.log");
        new FilePlanApprovalStore(log, 500);
        check("目录-父目录自动创建", Files.exists(log));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    static PlanApproval pending(String id, String taskId) {
        return new PlanApproval(id, "agent-1", taskId, PlanArtifact.text("计划"),
                PlanStatus.PENDING, Instant.now(), null, null, null);
    }

    static Path tmp(String name) throws Exception {
        Path base = Paths.get("target", "v115-rotation");
        Files.createDirectories(base);
        return Files.createTempDirectory(base, name + "-");
    }

    static long recordLines(Path log) throws Exception {
        try (Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.trim().isEmpty() && !l.startsWith("#")).count();
        }
    }

    static List<Path> listArchives(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".archive"))
                    .collect(Collectors.toList());
        }
    }

    static long archiveCount(Path dir) throws Exception {
        return listArchives(dir).size();
    }

    static long archivedRecordLines(Path dir) throws Exception {
        long total = 0;
        for (Path p : listArchives(dir)) {
            total += recordLines(p);
        }
        return total;
    }

    static boolean hasHeader(Path p) {
        try {
            return Files.readAllLines(p, StandardCharsets.UTF_8).contains("#PLAN-STORE v1");
        } catch (Exception e) {
            return false;
        }
    }

    static long seqOf(String id) {
        int dash = id.lastIndexOf('-');
        return Long.parseLong(id.substring(dash + 1));
    }
}
