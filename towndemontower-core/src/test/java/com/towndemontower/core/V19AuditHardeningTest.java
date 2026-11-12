package com.towndemontower.core;
import java.util.Arrays;

import com.towndemontower.core.audit.AuditCrypto;
import com.towndemontower.core.audit.AuditRecord;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * [core/test] V1.9 / I4 —— 审计链加固（HMAC + 外锚）。
 *
 * <p>核心要证明的两件事，都是旧实现<b>做不到</b>的：</p>
 * <ol>
 *   <li><b>整链重算无效</b>：攻击者按 SHA-256 重造整条链并接上 prevHash，
 *       在 HMAC 模式下 {@code verifyChain()} 必须仍为 false；</li>
 *   <li><b>截断可见</b>：删掉尾部记录后，日志自身仍自洽，但外锚的条数对不上，
 *       重启时直接拒绝。</li>
 * </ol>
 */
public class V19AuditHardeningTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }

    static final byte[] KEY = "audit-key-0123456789abcdefghijklmn".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        hmacChain();
        recomputeAttack();
        legacyMode();
        fileAnchor();
        fileTruncation();
        fileTamper();
        upgradePath();
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- ① HMAC 链 ---------- */

    static void hmacChain() {
        HashChainAuditStore s = new HashChainAuditStore(KEY);
        s.appendAuto("A", "1");
        s.appendAuto("B", "2");

        check("链-模式为HMAC", s.mode() == AuditCrypto.Mode.HMAC_SHA256);
        check("链-密钥可用", s.keyAvailable());
        check("链-自身可验", s.verifyChain());
        check("链-记录数", s.all().size() == 2);

        String input = s.all().get(0).seq + "|A|1|GENESIS";
        check("链-摘要与SHA256不同", !s.all().get(0).hash.equals(AuditCrypto.sha256(input)));
        check("链-摘要与HMAC一致",
                s.all().get(0).hash.equals(AuditCrypto.chain(KEY, input)));
        check("链-GENESIS前驱", "GENESIS".equals(s.all().get(0).prevHash));
        check("链-后继前驱正确", s.all().get(1).prevHash.equals(s.all().get(0).hash));
    }

    /* ---------- ② 重算攻击（旧实现的真实缺口） ---------- */

    static void recomputeAttack() {
        HashChainAuditStore s = new HashChainAuditStore(KEY);
        s.appendAuto("DELETE_FILE", "path=/data/customer.db");
        s.appendAuto("DROP_TABLE", "table=orders");

        // 攻击者：把 payload 改成无害内容，并按 SHA-256 重算整条链（他拿不到密钥）
        List<AuditRecord> forged = new ArrayList<>();
        String prev = "GENESIS";
        long seq = 0;
        for (String payload : Arrays.asList("path=/tmp/ok", "table=tmp")) {
            seq++;
            String h = AuditCrypto.sha256(seq + "|NOOP|" + payload + "|" + prev);
            forged.add(new AuditRecord(seq, "NOOP", payload, prev, h));
            prev = h;
        }
        HashChainAuditStore attacked = new HashChainAuditStore(KEY);
        com.towndemontower.core.audit.AuditRecordInjector.inject(attacked, forged);

        check("重算-伪造链看似自洽", forged.get(1).prevHash.equals(forged.get(0).hash));
        check("重算-验签失败", !attacked.verifyChain());

        // 对照组：无密钥时同样的伪造会被接受——这正是本版要修的问题
        HashChainAuditStore weak = new HashChainAuditStore((byte[]) null);
        com.towndemontower.core.audit.AuditRecordInjector.inject(weak, forged);
        check("重算-无密钥时确实挡不住（对照）", weak.verifyChain());
    }

    /* ---------- ③ 无密钥退化 ---------- */

    static void legacyMode() {
        HashChainAuditStore s = new HashChainAuditStore((byte[]) null);
        s.appendAuto("A", "1");
        check("退化-模式为SHA256", s.mode() == AuditCrypto.Mode.SHA256);
        check("退化-无密钥标记", !s.keyAvailable());
        check("退化-正常链可验", s.verifyChain());

        List<AuditRecord> bad = new ArrayList<>(s.all());
        AuditRecord r = bad.get(0);
        bad.set(0, new AuditRecord(r.seq, r.action, "改过的内容", r.prevHash, r.hash));
        HashChainAuditStore tampered = new HashChainAuditStore((byte[]) null);
        com.towndemontower.core.audit.AuditRecordInjector.inject(tampered, bad);
        check("退化-单点篡改仍可发现", !tampered.verifyChain());
    }

    /* ---------- ④ 外锚：正常写入与重启 ---------- */

    static void fileAnchor() throws IOException {
        Path dir = Files.createTempDirectory("tower-anchor");
        try {
            Path log = dir.resolve("audit.log");
            FileAuditStore s = new FileAuditStore(log, KEY);
            s.appendAuto("A", "1");
            s.appendAuto("B", "2");
            s.appendAuto("C", "3");

            check("锚点-文件生成", Files.exists(s.anchorFile()));
            check("锚点-条数与文件一致", s.anchorRecords() == s.fileRecordCount());
            check("锚点-条数为3", s.anchorRecords() == 3);

            FileAuditStore reopen = new FileAuditStore(log, KEY);
            check("锚点-正常重启不抛", reopen.fileRecordCount() == 3);
            reopen.appendAuto("D", "4");
            check("锚点-重启后继续写", reopen.anchorRecords() == 4);
            check("锚点-重开后链可验", reopen.verifyChain());

            String anchorText = new String(Files.readAllBytes(reopen.anchorFile()), StandardCharsets.UTF_8);
            check("锚点-内容含mac", anchorText.contains("mac=") && !anchorText.contains("mac=-"));
        } finally {
            delete(dir);
        }
    }

    /* ---------- ⑤ 外锚：截断 ---------- */

    static void fileTruncation() throws IOException {
        Path dir = Files.createTempDirectory("tower-trunc");
        try {
            Path log = dir.resolve("audit.log");
            FileAuditStore s = new FileAuditStore(log, KEY);
            s.appendAuto("A", "1");
            s.appendAuto("B", "2");
            s.appendAuto("C", "3");

            // 删掉最后一条记录（保留段头与其它行）
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                if (line != null && !line.trim().isEmpty() && !line.startsWith("#SEGMENT")) {
                    lines.remove(i);
                    break;
                }
            }
            Files.write(log, lines, StandardCharsets.UTF_8);
            check("截断-日志只剩2条", countRecords(log) == 2);

            // 日志自身仍自洽（截断不改历史哈希），但锚点记得是 3 条
            String thrown = expectFailure(log, KEY);
            check("截断-重启被拒", thrown != null);
            check("截断-原因指向截断", thrown != null && thrown.contains("截断"));
        } finally {
            delete(dir);
        }
    }

    /* ---------- ⑥ 外锚：篡改 ---------- */

    static void fileTamper() throws IOException {
        Path dir = Files.createTempDirectory("tower-tamper");
        try {
            Path log = dir.resolve("audit.log");
            FileAuditStore s = new FileAuditStore(log, KEY);
            s.appendAuto("A", "1");
            s.appendAuto("B", "2");

            // ① 改日志内容（哈希不再匹配）
            List<String> lines = new ArrayList<>(Files.readAllLines(log, StandardCharsets.UTF_8));
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line != null && !line.trim().isEmpty() && !line.startsWith("#SEGMENT")) {
                    String[] f = line.split("\t", 4);
                    f[3] = "999";                       // 改 payload，保留原 hash
                    lines.set(i, String.join("\t", f));
                    break;
                }
            }
            Files.write(log, lines, StandardCharsets.UTF_8);
            String contentFail = expectFailure(log, KEY);
            check("篡改-改内容被发现", contentFail != null);

            // ② 恢复日志，改锚点 MAC
            Path dir2 = Files.createTempDirectory("tower-tamper2");
            try {
                Path log2 = dir2.resolve("audit.log");
                FileAuditStore s2 = new FileAuditStore(log2, KEY);
                s2.appendAuto("A", "1");
                String anchor = new String(Files.readAllBytes(s2.anchorFile()), StandardCharsets.UTF_8);
                Files.write(s2.anchorFile(),
                        anchor.replaceAll("mac=[0-9a-f]+", "mac=deadbeef").getBytes(StandardCharsets.UTF_8));
                String macFail = expectFailure(log2, KEY);
                check("篡改-改锚点MAC被发现", macFail != null && macFail.contains("篡改"));

                // ③ 连记录数和末条哈希一起改（无密钥则算不出 MAC）
                Files.write(log2, ("\n#SEGMENT x prev=GENESIS\n").getBytes(StandardCharsets.UTF_8));
                Files.write(s2.anchorFile(), "records=0\thash=GENESIS\tmac=deadbeef\n".getBytes(StandardCharsets.UTF_8));
                check("篡改-伪造锚点仍失败", expectFailure(log2, KEY) != null);
            } finally {
                delete(dir2);
            }
        } finally {
            delete(dir);
        }
    }

    /* ---------- ⑦ 升级路径：旧 SHA-256 日志 + 新密钥 ---------- */

    static void upgradePath() throws IOException {
        Path dir = Files.createTempDirectory("tower-upgrade");
        try {
            Path log = dir.resolve("audit.log");
            FileAuditStore old = new FileAuditStore(log, null);   // 旧版：无密钥
            old.appendAuto("OLD", "1");
            old.appendAuto("OLD", "2");
            check("升级-旧日志锚点无MAC",
                    new String(Files.readAllBytes(old.anchorFile()), StandardCharsets.UTF_8).contains("mac=-"));

            FileAuditStore upgraded = new FileAuditStore(log, KEY);
            check("升级-带密钥重开旧日志不报错", upgraded.fileRecordCount() == 2);
            upgraded.appendAuto("NEW", "3");
            check("升级-新记录写入", upgraded.anchorRecords() == 3);
            check("升级-锚点已换成带MAC",
                    !new String(Files.readAllBytes(upgraded.anchorFile()), StandardCharsets.UTF_8)
                            .contains("mac=-"));
            check("升级-旧段仍可读", upgraded.all().size() == 1);   // 内存链只装本次会话记录
        } finally {
            delete(dir);
        }
    }

    /* ------------------------------------------------------------------ */

    static int countRecords(Path log) throws IOException {
        int n = 0;
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (line != null && !line.trim().isEmpty() && !line.startsWith("#SEGMENT")) n++;
        }
        return n;
    }

    /** 返回失败信息；未失败返回 null。 */
    static String expectFailure(Path log, byte[] key) {
        try {
            new FileAuditStore(log, key);
            return null;
        } catch (RuntimeException e) {
            StringBuilder sb = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) sb.append(t.getMessage()).append('|');
            return sb.toString();
        }
    }

    static void delete(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响断言
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
