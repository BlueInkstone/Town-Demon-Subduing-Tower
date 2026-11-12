package com.towndemontower.core;

import java.nio.file.Paths;
import com.towndemontower.core.util.IO;
import java.util.Arrays;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.towndemontower.core.audit.AnchorReplica;
import com.towndemontower.core.audit.AnchorReplicaState;
import com.towndemontower.core.audit.AnchorSnapshot;
import com.towndemontower.core.audit.AuditReplicas;
import com.towndemontower.core.audit.FileAnchorReplica;
import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.core.audit.HttpAnchorReplica;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [core/test] V1.10 / I4-c —— 锚点异地化（副本扇出 + 启动回滚检出）。
 *
 * <p>验证六组不变量：</p>
 * <ol>
 *   <li>快照行格式可往返、可容忍旧锚点（没有 {@code at=}）；</li>
 *   <li>文件副本是<b>只追加账本</b>：删中间行 / 回滚整文件都能被发现；</li>
 *   <li>配置解析只认三种形式，写错就报错（不静默忽略）；</li>
 *   <li>启动时副本比本地<b>新</b> → 拒绝启动（这是"整目录回滚"唯一能露馅的地方）；</li>
 *   <li>副本<b>落后</b>是正常状态，不该阻断启动；</li>
 *   <li>写路径按 {@code replicaRequired} 决定"推不出去"是失败还是只计数。</li>
 * </ol>
 */
public class V19AnchorReplicaTest {

    static int pass = 0, fail = 0;

    static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

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
            System.out.println("[PASS] " + name + " = " + actual);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + " expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) throws Exception {
        snapshotFormat();
        fileReplica();
        parseSpec();
        startupChecks();
        pushPolicy();
        httpReplica();
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- ① 快照格式 ---------- */

    static void snapshotFormat() {
        AnchorSnapshot s = new AnchorSnapshot(7, "abc", "mac1", 1700000000000L);
        String line = s.line();
        check("快照-行含四个字段", line.contains("records=7") && line.contains("hash=abc")
                && line.contains("mac=mac1") && line.contains("at="));
        AnchorSnapshot back = AnchorSnapshot.parse(line, "test");
        eq("快照-往返条数", back.records(), 7L);
        eq("快照-往返哈希", back.hash(), "abc");
        eq("快照-往返mac", back.mac(), "mac1");
        eq("快照-往返时间", back.at(), 1700000000000L);

        // v1.9.2 之前写下的锚点没有 at= 字段，必须还能读
        AnchorSnapshot legacy = AnchorSnapshot.parse("records=3\thash=hh\tmac=mm", "old");
        eq("兼容-旧锚点条数", legacy.records(), 3L);
        eq("兼容-旧锚点时间为0", legacy.at(), 0L);
        check("兼容-旧锚点不是损坏", legacy.mac().equals("mm"));

        eq("解析-空文本返回null", AnchorSnapshot.parse("  ", "t"), null);
        boolean threw = false;
        try {
            AnchorSnapshot.parse("records=1", "t");
        } catch (IllegalStateException e) {
            threw = e.getMessage().contains("格式损坏");
        }
        check("解析-字段不足判损坏", threw);

        check("同位置-相同哈希", s.sameAt(new AnchorSnapshot(7, "abc", "x", 0)));
        check("同位置-不同哈希", !s.sameAt(new AnchorSnapshot(7, "abd", "x", 0)));
        check("同位置-不同条数", !s.sameAt(new AnchorSnapshot(8, "abc", "x", 0)));
    }

    /* ---------- ② 文件副本 ---------- */

    static void fileReplica() throws IOException {
        Path dir = tmp();
        Path ledger = dir.resolve("anchor.ledger");
        FileAnchorReplica r = new FileAnchorReplica(ledger, KEY);

        eq("文件副本-空账本pull为null", r.pull(), null);
        check("文件副本-默认非异地", !r.offHost());
        check("文件副本-名字含路径", r.name().startsWith("file:") && r.name().contains("anchor.ledger"));

        r.push(new AnchorSnapshot(3, "h3", "m3", 1L));
        r.push(new AnchorSnapshot(6, "h6", "m6", 2L));
        AnchorSnapshot last = r.pull();
        eq("文件副本-读回末条", last.records(), 6L);
        eq("文件副本-只追加不覆盖", Files.readAllLines(ledger).size(), 2);

        FileAnchorReplica off = new FileAnchorReplica(dir.resolve("x.ledger"), KEY, true);
        check("文件副本-可声明异地", off.offHost());

        // 删中间一行 → 链断（每一行的 mac 覆盖上一行 mac）
        r.push(new AnchorSnapshot(9, "h9", "m9", 3L));
        List<String> lines = new ArrayList<>(Files.readAllLines(ledger));
        lines.remove(1);
        Files.write(ledger, lines);
        String err = pullError(new FileAnchorReplica(ledger, KEY));
        check("文件副本-删中间行被发现", err != null && err.contains("链路断裂"));

        // 有密钥时改写账本：算不出 MAC，报的是链路断裂
        Path ledger2 = dir.resolve("rollback.ledger");
        FileAnchorReplica r2 = new FileAnchorReplica(ledger2, KEY);
        r2.push(new AnchorSnapshot(9, "h9", "m9", 3L));
        Files.write(ledger2, ("records=2\thash=h2\tmac=-\tat=1\n").getBytes(StandardCharsets.UTF_8));
        String err2 = pullError(new FileAnchorReplica(ledger2, KEY));
        check("文件副本-改写账本被链路校验挡住", err2 != null && err2.contains("链路断裂"));

        // 无密钥时 MAC 不可校验，靠"条数只能增"这条兜住同一类改写
        Path ledger2b = dir.resolve("rollback-nokey.ledger");
        Files.write(ledger2b, ("records=9\thash=h9\tmac=-\tat=1\nrecords=2\thash=h2\tmac=-\tat=2\n").getBytes(StandardCharsets.UTF_8));
        String err2b = pullError(new FileAnchorReplica(ledger2b, null));
        check("文件副本-无密钥时靠单调性发现回滚", err2b != null && err2b.contains("回滚"));

        // 无密钥：MAC 退化，条数单调这一条仍然有效
        Path ledger3 = dir.resolve("nokey.ledger");
        FileAnchorReplica r3 = new FileAnchorReplica(ledger3, null);
        r3.push(new AnchorSnapshot(4, "h4", "-", 1L));
        eq("无密钥副本-仍可写读", r3.pull().records(), 4L);
        check("无密钥副本-mac为占位", "-".equals(r3.pull().mac()));
    }

    /* ---------- ③ 配置解析 ---------- */

    static void parseSpec() throws IOException {
        Path dir = tmp();
        List<AnchorReplica> one = AuditReplicas.parse("file:" + dir.resolve("a.ledger"), KEY);
        eq("解析-单个文件副本", one.size(), 1);
        check("解析-未声明异地", !one.get(0).offHost());

        List<AnchorReplica> two = AuditReplicas.parse(
                " file+offhost:" + dir.resolve("b.ledger") + " ; https://collector.example.com/a ", KEY);
        eq("解析-分号与空格分隔", two.size(), 2);
        check("解析-声明异地生效", two.get(0).offHost());
        check("解析-https视为异地", two.get(1).offHost());

        List<AnchorReplica> local = AuditReplicas.parse("http://localhost:9999/anchor", KEY);
        check("解析-本机URL不算异地", !local.get(0).offHost());

        eq("解析-空配置即空表", AuditReplicas.parse("  ", KEY).size(), 0);
        eq("解析-null即空表", AuditReplicas.parse(null, KEY).size(), 0);
        eq("解析-多余分隔符被忽略", AuditReplicas.parse(",,", KEY).size(), 0);

        boolean threw = false;
        try {
            AuditReplicas.parse("ftp://x/y", KEY);
        } catch (IllegalArgumentException e) {
            threw = e.getMessage().contains("ftp://x/y");
        }
        check("解析-非法形式报错且回显原文", threw);

        boolean threw2 = false;
        try {
            AuditReplicas.parse("file:   ", KEY);
        } catch (IllegalArgumentException e) {
            threw2 = e.getMessage().contains("路径为空");
        }
        check("解析-空路径报错", threw2);
    }

    /* ---------- ④ 启动核对 ---------- */

    static void startupChecks() throws IOException {
        // 正常：写 5 条后重开，副本与本地一致
        Path dir = tmp();
        Path log = dir.resolve("audit.log");
        Path ledger = dir.resolve("anchor.ledger");
        FileAnchorReplica replica = new FileAnchorReplica(ledger, KEY);
        FileAuditStore s = new FileAuditStore(log, KEY, 1, 0, Arrays.asList(replica), true);
        for (int i = 0; i < 5; i++) s.appendAuto("A", "p" + i);
        eq("启动-副本跟上写入", replica.pull().records(), 5L);
        s.flushAnchor();

        FileAuditStore reopen = new FileAuditStore(log, KEY, 1, 0,
                Arrays.asList(new FileAnchorReplica(ledger, KEY)), true);
        eq("启动-一致时正常打开", reopen.anchorRecords(), 5L);
        List<AnchorReplicaState> states = reopen.replicaStates();
        eq("启动-核对结果个数", states.size(), 1);
        check("启动-结果可读", states.get(0).readable());
        eq("启动-结果条数", states.get(0).records(), 5L);
        reopen.flushAnchor();

        // 落后：日志已有 4 条，副本只记得 1 条（推送失败过 / 副本刚接入）→ 不阻断
        Path dir2 = tmp();
        Path log2 = dir2.resolve("audit.log");
        Path ledger2 = dir2.resolve("anchor.ledger");
        FileAuditStore s2 = new FileAuditStore(log2, KEY);   // 先无副本地跑起来
        for (int i = 0; i < 4; i++) s2.appendAuto("A", "p" + i);
        s2.flushAnchor();
        new FileAnchorReplica(ledger2, KEY).push(new AnchorSnapshot(1, "h1", "m1", 1L));
        FileAuditStore reopen2 = new FileAuditStore(log2, KEY, 1, 0,
                Arrays.asList(new FileAnchorReplica(ledger2, KEY)), true);
        eq("启动-副本落后不阻断", reopen2.anchorRecords(), 4L);
        check("启动-落后状态可读", reopen2.startupReplicaStates().get(0).readable());
        eq("启动-落后条数如实报告", reopen2.startupReplicaStates().get(0).records(), 1L);
        // 构造末尾的"重写锚点"本身就把落后补上了，所以实时状态已经是 4：
        // 两份读数服务不同问题——startup 用于排障回溯，replicaStates 用于监控现状
        eq("启动-构造即追平（实时状态）", reopen2.replicaStates().get(0).records(), 4L);
        reopen2.flushAnchor();
        eq("启动-追平后副本前进", new FileAnchorReplica(ledger2, KEY).pull().records(), 4L);

        // 回滚：日志被删、锚点被删，副本仍记得 5 条
        Path dir3 = tmp();
        Path log3 = dir3.resolve("audit.log");
        Path ledger3 = dir3.resolve("anchor.ledger");
        FileAnchorReplica rep3 = new FileAnchorReplica(ledger3, KEY);
        FileAuditStore s3 = new FileAuditStore(log3, KEY, 1, 0, Arrays.asList(rep3), true);
        for (int i = 0; i < 5; i++) s3.appendAuto("A", "p" + i);
        s3.flushAnchor();
        Files.deleteIfExists(log3);
        Files.deleteIfExists(dir3.resolve("audit.log.anchor"));
        String rollback = openError(() -> new FileAuditStore(log3, KEY, 1, 0,
                Arrays.asList(new FileAnchorReplica(ledger3, KEY)), true));
        check("启动-整目录回滚被检出", rollback != null && rollback.contains("回滚或删除"));

        // 回滚到中间：日志只剩前 2 条
        Path dir4 = tmp();
        Path log4 = dir4.resolve("audit.log");
        Path ledger4 = dir4.resolve("anchor.ledger");
        FileAnchorReplica rep4 = new FileAnchorReplica(ledger4, KEY);
        FileAuditStore s4 = new FileAuditStore(log4, KEY, 1, 0, Arrays.asList(rep4), true);
        for (int i = 0; i < 5; i++) s4.appendAuto("A", "p" + i);
        s4.flushAnchor();
        List<String> keep = new ArrayList<>();
        int dataLines = 0;
        for (String line : Files.readAllLines(log4)) {
            if (line.startsWith("#SEGMENT")) {
                keep.add(line);
            } else if (!line.trim().isEmpty() && dataLines < 2) {
                keep.add(line);
                dataLines++;
            }
        }
        Files.write(log4, keep);
        Files.deleteIfExists(log4.resolveSibling("audit.log.anchor"));
        String partial = openError(() -> new FileAuditStore(log4, KEY, 1, 0,
                Arrays.asList(new FileAnchorReplica(ledger4, KEY)), true));
        check("启动-回滚到中间也被检出", partial != null && partial.contains("回滚或删除"));

        // 同条数不同哈希：副本属于另一条链
        Path dir5 = tmp();
        Path log5 = dir5.resolve("audit.log");
        Path ledger5 = dir5.resolve("anchor.ledger");
        FileAnchorReplica rep5 = new FileAnchorReplica(ledger5, KEY);
        FileAuditStore s5 = new FileAuditStore(log5, KEY, 1, 0, Arrays.asList(rep5), true);
        for (int i = 0; i < 3; i++) s5.appendAuto("A", "p" + i);
        s5.flushAnchor();
        // 用同一把钥匙追加一条"同条数、不同哈希"的合法副本行：
        // 链路本身校验通过，只有与本地日志对账才看得出不是同一条链
        rep5.push(new AnchorSnapshot(3, "deadbeef", "ignored", 9L));
        eq("启动-伪造副本链路自洽", new FileAnchorReplica(ledger5, KEY).pull().records(), 3L);
        String conflict = openError(() -> new FileAuditStore(log5, KEY, 1, 0,
                Arrays.asList(new FileAnchorReplica(ledger5, KEY)), true));
        check("启动-同条数不同哈希被检出", conflict != null
                && (conflict.contains("不一致") || conflict.contains("另一条")));

        // 副本不可读 + required=false → 放行但留下 error
        int dead = freePort();
        Path dir6 = tmp();
        FileAuditStore lenient = new FileAuditStore(dir6.resolve("audit.log"), KEY, 1, 0,
                Arrays.asList(new HttpAnchorReplica("http://127.0.0.1:" + dead + "/a", null,
                        java.time.Duration.ofSeconds(1))), false);
        eq("启动-副本不可读但降级放行", lenient.replicaStates().size(), 1);
        check("启动-降级时记录错误", !lenient.replicaStates().get(0).readable());
        check("启动-降级时仍可用", lenient.verifyChain());
        lenient.flushAnchor();

        // 副本不可读 + required=true → 拒绝启动
        String strict = openError(() -> new FileAuditStore(dir6.resolve("strict.log"), KEY, 1, 0,
                Arrays.asList(new HttpAnchorReplica("http://127.0.0.1:" + dead + "/a", null,
                        java.time.Duration.ofSeconds(1))), true));
        check("启动-副本不可读且required拒绝启动", strict != null && strict.contains("不可读"));
    }

    /* ---------- ⑤ 写路径策略 ---------- */

    static void pushPolicy() throws IOException {
        int dead = freePort();
        Path dir = tmp();

        FileAuditStore loud = new FileAuditStore(dir.resolve("loud.log"), KEY, 1, 0,
                Arrays.asList(new HttpAnchorReplica("http://127.0.0.1:" + dead + "/a", null,
                        java.time.Duration.ofSeconds(1))), false);
        loud.appendAuto("A", "1");
        eq("推送-降级模式不抛", loud.all().size(), 1);
        check("推送-降级模式计入失败", loud.replicaFailures() > 0);
        check("推送-降级模式记录错误", !loud.replicaErrors().isEmpty());
        loud.flushAnchor();

        FileAuditStore strict = null;
        String err = null;
        try {
            strict = new FileAuditStore(dir.resolve("strict.log"), KEY, 1, 0,
                    Arrays.asList(new HttpAnchorReplica("http://127.0.0.1:" + dead + "/a", null,
                            java.time.Duration.ofSeconds(1))), false);
        } catch (RuntimeException e) {
            err = e.getMessage();
        }
        check("推送-降级模式可构造", strict != null && err == null);

        FileAuditStore strict2 = null;
        try {
            strict2 = new FileAuditStore(dir.resolve("strict2.log"), KEY, 1, 0, Arrays.asList(), true);
            strict2.appendAuto("A", "1");
        } catch (RuntimeException e) {
            err = e.getMessage();
        }
        check("推送-无副本时required无副作用", strict2 != null && strict2.all().size() == 1);

        // 本地写成功的记录不会因为副本失败而消失：证据在，只是缺一份异地拷贝
        FileAuditStore half = new FileAuditStore(dir.resolve("half.log"), KEY, 1, 0,
                Arrays.asList(new HttpAnchorReplica("http://127.0.0.1:" + dead + "/a", null,
                        java.time.Duration.ofSeconds(1))), false);
        half.appendAuto("A", "keep-me");
        check("推送-失败不影响本地链", half.verifyChain());
        half.flushAnchor();
    }

    /* ---------- ⑥ HTTP 副本 ---------- */

    static void httpReplica() throws Exception {
        Collector collector = new Collector();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/anchor", collector);
        server.start();
        int port = server.getAddress().getPort();
        try {
            HttpAnchorReplica r = new HttpAnchorReplica("http://127.0.0.1:" + port + "/anchor",
                    "tok-123", java.time.Duration.ofSeconds(2));
            check("HTTP副本-本机不算异地", !r.offHost());
            check("HTTP副本-名字含URL", r.name().contains("/anchor"));

            eq("HTTP副本-首次pull为null", r.pull(), null);
            r.push(new AnchorSnapshot(11, "h11", "m11", 5L));
            eq("HTTP副本-推送次数", collector.pushCount, 1);
            eq("HTTP副本-令牌随行", collector.token, "tok-123");
            check("HTTP副本-服务端收到行", collector.last != null && collector.last.contains("records=11"));

            AnchorSnapshot back = r.pull();
            eq("HTTP副本-读回条数", back.records(), 11L);
            eq("HTTP副本-读回哈希", back.hash(), "h11");

            collector.failStatus = 500;
            boolean pushFailed = false;
            try {
                r.push(new AnchorSnapshot(12, "h12", "m12", 6L));
            } catch (IllegalStateException e) {
                pushFailed = e.getMessage().contains("HTTP 500");
            }
            check("HTTP副本-非2xx判失败并带状态码", pushFailed);
            check("HTTP副本-失败写入lastError", r.lastError() != null && r.lastError().contains("500"));

            // 远端 host 判定：example.com 不是本机
            HttpAnchorReplica remote = new HttpAnchorReplica("https://collector.example.com/a",
                    null, java.time.Duration.ofSeconds(1));
            check("HTTP副本-远端算异地", remote.offHost());
        } finally {
            server.stop(0);
        }

        // 服务停掉之后必须抛，而不是返回 null（"读不出来"和"还没有"是两件事）
        boolean unreachable = false;
        try {
            new HttpAnchorReplica("http://127.0.0.1:" + freePort() + "/anchor", null,
                    java.time.Duration.ofSeconds(1)).pull();
        } catch (IllegalStateException e) {
            unreachable = true;
        }
        check("HTTP副本-不可达抛异常而非null", unreachable);
    }

    /* ---------- 工具 ---------- */

    /** 收端：POST 覆盖"最新一行"，GET 返回它；可切换失败状态码。 */
    static final class Collector implements HttpHandler {
        volatile String last;
        volatile String token;
        volatile int pushCount;
        volatile int failStatus = 200;

        @Override
        public void handle(HttpExchange ex) throws IOException {
            token = ex.getRequestHeaders().getFirst("X-Anchor-Token");
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                last = new String(IO.readAllBytes(ex.getRequestBody()), StandardCharsets.UTF_8).trim();
                pushCount++;
                ex.sendResponseHeaders(failStatus, -1);
            } else if (last == null) {
                ex.sendResponseHeaders(204, -1);
            } else {
                byte[] body = last.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
            ex.close();
        }
    }

    static Path tmp() throws IOException {
        Path dir = Paths.get("target", "replica-" + UUID.randomUUID());
        Files.createDirectories(dir);
        return dir;
    }

    static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    /** 构造即可能失败（启动校验发生在构造期），所以接一个工厂而不是现成的实例。 */
    static String openError(java.util.function.Supplier<FileAuditStore> make) {
        try {
            make.get().flushAnchor();
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    static String pullError(AnchorReplica r) {
        try {
            r.pull();
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

}
