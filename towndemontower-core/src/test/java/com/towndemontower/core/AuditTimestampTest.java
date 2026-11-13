package com.towndemontower.core;

import com.towndemontower.core.analysis.AuditLogReader;
import com.towndemontower.core.audit.AuditCrypto;
import com.towndemontower.core.audit.AuditRecord;
import com.towndemontower.core.audit.FileAuditStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * [core/test] 审计时间戳（V2.2）：存在的意义、格式、参与哈希、旧日志兼容。
 *
 * <p>这套断言的出发点是"时间过滤能不能信"：如果时间戳不参与链摘要，
 * 谁都能把一条审计的时间改到任意时刻而不破坏链——那样的过滤结果不能作为证据。
 * 所以下面既验"有值且格式对"，也验"改了它会被判成篡改"。</p>
 */
public class AuditTimestampTest {

    static int pass = 0;
    static int fail = 0;

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

    /** 定长 ISO-8601 UTC 毫秒：2026-11-13T01:23:45.678Z */
    static final String TS_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    static final byte[] KEY = "audit-ts-test-key".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("audit-ts-");
        try {
            recordsCarryTimestamp(dir);
            timestampIsAuthenticated(dir);
            legacyFourColumnStillLoads(dir);
        } finally {
            // 临时目录不清理也没关系，但留着会让反复调试时混淆新旧文件
            deleteQuietly(dir);
        }
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /* ---------- ① 新记录带时间戳，且写进文件第五列之前 ---------- */

    static void recordsCarryTimestamp(Path dir) throws Exception {
        Path f = dir.resolve("ts.log");
        FileAuditStore store = new FileAuditStore(f, KEY);
        store.appendAuto("ACTION_ONE", "payload-1");
        store.appendAuto("ACTION_TWO", "payload-2");
        store.flushAnchor();

        List<AuditRecord> all = store.all();
        eq("记录数", all.size(), 2);
        String ts0 = all.get(0).ts;
        String ts1 = all.get(1).ts;

        check("时间戳非空", ts0 != null);
        check("时间戳是定长 ISO-8601 UTC（毫秒）", ts0 != null && ts0.matches(TS_PATTERN));
        check("时间戳非递减（同毫秒也允许）", ts0 != null && ts1 != null && ts1.compareTo(ts0) >= 0);
        check("链自洽", store.verifyChain());

        // 时间过滤靠"定长 UTC 字符串比较"，先把这条性质钉住
        check("字符串比较即时间比较（晚于 2000 年）", ts0 != null && ts0.compareTo("2000-01-01T00:00:00.000Z") > 0);
        check("字符串比较即时间比较（早于 2100 年）", ts0 != null && ts0.compareTo("2100-01-01T00:00:00.000Z") < 0);

        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        String first = firstRecordLine(lines);
        check("文件里有记录行", first != null);
        String[] p = first == null ? new String[0] : first.split("\t", 5);
        eq("文件行是五列（seq/action/hash/ts/payload）", p.length, 5);
        eq("文件里的时间戳与内存一致", p.length == 5 ? p[3] : null, ts0);
        eq("文件里的内容与内存一致", p.length == 5 ? p[4] : null, "payload-1");

        // 重新打开：scan 会用同一套口径逐条重算，能打开就说明磁盘链没坏
        FileAuditStore reopened = new FileAuditStore(f, KEY);
        check("重开成功（磁盘链逐条重算通过）", true);
        eq("新进程内链从空开始（段内重新起链）", reopened.all().size(), 0);

        // 追加一条后，用离线读取器把两段都读出来：老记录的 ts 必须原样保留
        reopened.appendAuto("ACTION_THREE", "payload-3");
        reopened.flushAnchor();
        AuditLogReader.Loaded loaded = AuditLogReader.read(f, KEY);
        check("离线读取：链有效", loaded.chainValid);
        eq("离线读取：三条记录", loaded.records.size(), 3);
        eq("离线读取：第一条时间戳保留", loaded.records.get(0).ts, ts0);
        check("离线读取：第三条也有时间戳",
                loaded.records.get(2).ts != null && loaded.records.get(2).ts.matches(TS_PATTERN));
    }

    /* ---------- ② 改时间戳 = 篡改（ts 参与链摘要） ---------- */

    static void timestampIsAuthenticated(Path dir) throws Exception {
        Path f = dir.resolve("tamper.log");
        FileAuditStore store = new FileAuditStore(f, KEY);
        store.appendAuto("ACTION_ONE", "payload-1");
        store.flushAnchor();

        List<String> lines = new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8));
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("#SEGMENT") || line.trim().isEmpty()) continue;
            String[] p = line.split("\t", 5);
            if (p.length < 5) continue;
            // 只动时间戳，内容与哈希都保持原样
            lines.set(i, p[0] + "\t" + p[1] + "\t" + p[2] + "\t" + "2000-01-01T00:00:00.000Z" + "\t" + p[4]);
            changed = true;
            break;
        }
        check("测试准备：成功改写一行时间戳", changed);
        Files.write(f, lines, StandardCharsets.UTF_8);

        String message = null;
        try {
            new FileAuditStore(f, KEY);
        } catch (IllegalStateException e) {
            message = e.getMessage();
        }
        check("改写时间戳后拒绝启动（被判成篡改）", message != null);
        check("拒绝原因指向哈希不匹配",
                message != null && message.contains("哈希不匹配"));
    }

    /* ---------- ③ 旧四列日志仍然能读、能验 ---------- */

    static void legacyFourColumnStillLoads(Path dir) throws Exception {
        Path f = dir.resolve("legacy.log");
        String payload = "LEGACY|v1|ok";
        String hash = AuditCrypto.chain(KEY, AuditRecord.hashInput(1, "OLD_ACTION", payload, "GENESIS", null));
        Files.write(f, ("1\tOLD_ACTION\t" + hash + "\t" + payload + "\n").getBytes(StandardCharsets.UTF_8));

        FileAuditStore store = new FileAuditStore(f, KEY);
        check("旧四列日志可以打开（不误判篡改）", true);
        eq("旧日志加载后内存链为空（记录留在磁盘）", store.all().size(), 0);

        AuditLogReader.Loaded loaded = AuditLogReader.read(f, KEY);
        check("离线读取旧日志：链有效", loaded.chainValid);
        eq("离线读取旧日志：一条记录", loaded.records.size(), 1);
        eq("旧记录没有时间戳（不编造）", loaded.records.get(0).ts, null);
        check("旧记录仍标记为无时间戳", !loaded.records.get(0).hasTimestamp());
        eq("旧记录内容完整（payload 未被时间列切走）", loaded.records.get(0).payload, payload);
    }

    /* ---------- 工具 ---------- */

    static String firstRecordLine(List<String> lines) {
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty() && !line.startsWith("#SEGMENT")) {
                return line;
            }
        }
        return null;
    }

    static void deleteQuietly(Path dir) {
        try {
            if (!Files.exists(dir)) return;
            List<Path> paths = new ArrayList<>();
            // Windows 上必须显式关闭遍历流，否则目录句柄未释放，删目录会失败
            java.util.stream.Stream<Path> stream = Files.walk(dir);
            try {
                stream.forEach(paths::add);
            } finally {
                stream.close();
            }
            for (int i = paths.size() - 1; i >= 0; i--) {
                Files.deleteIfExists(paths.get(i));
            }
        } catch (Exception ignored) {
            // 清理失败不影响断言结论
        }
    }
}
