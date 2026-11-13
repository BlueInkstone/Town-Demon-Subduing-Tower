package com.towndemontower.core.analysis;

import com.towndemontower.core.audit.AuditCrypto;
import com.towndemontower.core.audit.AuditRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * [core] 方向 C / M1：只读加载 {@code FileAuditStore} 落盘的审计日志并校验哈希链（FR-5）。
 *
 * <p><b>为什么不能直接 new FileAuditStore</b>：它的构造器会追加段头、重写锚点、可能推送副本，
 * 那是写操作，会破坏 FR-1「只读」与 AC-2「扫描前后审计链哈希不变」。这里复用同一份
 * 校验算式（{@link AuditCrypto#chain}）在内存里重算，绝不触碰磁盘写入。</p>
 *
 * <p>文件行格式（沿用 FileAuditStore 既有约定，未做任何改动）：
 * {@code #SEGMENT <uuid> prev=GENESIS} 分段头；记录行为
 * {@code <seq>\t<action>\t<hash>\t<payload>}，每段内部从 {@code GENESIS} 重新起链。</p>
 */
public final class AuditLogReader {

    private static final String SEGMENT_PREFIX = "#SEGMENT";
    private static final String GENESIS = "GENESIS";

    private AuditLogReader() {
    }

    public static final class Loaded {
        public final List<AuditRecord> records;
        public final boolean chainValid;
        /** 是否遇到「旧段用 SHA-256、当前密钥是 HMAC」的兼容情形。 */
        public final boolean legacySeen;
        /** 脱敏后的失败原因：只说明第几行什么问题，绝不回显原始日志行。 */
        public final String problem;

        Loaded(List<AuditRecord> records, boolean chainValid, boolean legacySeen, String problem) {
            this.records = new ArrayList<>(records);
            this.chainValid = chainValid;
            this.legacySeen = legacySeen;
            this.problem = problem;
        }
    }

    /** 生产路径：密钥只从环境变量取，与 FileAuditStore 默认行为一致（不回显、不落盘）。 */
    public static Loaded read(Path file) throws IOException {
        return read(file, AuditCrypto.envKey());
    }

    /** 显式注入密钥（测试 / 离线分析自有日志）；{@code null} 表示 SHA-256 链。 */
    public static Loaded read(Path file, byte[] key) throws IOException {
        if (file == null || !Files.isRegularFile(file))
            throw new IOException("审计文件不存在或不是普通文件");

        List<AuditRecord> out = new ArrayList<>();
        String prev = GENESIS;
        boolean legacy = false;
        int lineNo = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNo++;
            if (line == null || line.trim().isEmpty()) continue;
            if (line.startsWith(SEGMENT_PREFIX)) {
                prev = GENESIS;
                continue;
            }
            // 五列 = V2.2 起带时间戳；四列 = 旧日志（payload 在末列）
            String[] p = line.split("\t", 5);
            if (p.length < 4) return invalid(out, "第 " + lineNo + " 行格式损坏", legacy);

            long seq;
            try {
                seq = Long.parseLong(p[0]);
            } catch (NumberFormatException e) {
                return invalid(out, "第 " + lineNo + " 行序号不可解析", legacy);
            }
            String action = p[1];
            String hash = p[2];
            String ts = p.length >= 5 ? p[3] : null;
            String payload = p.length >= 5 ? p[4] : p[3];
            String input = AuditRecord.hashInput(seq, action, payload, prev, ts);
            String expected = AuditCrypto.chain(key, input);
            if (!expected.equals(hash)) {
                // 与 FileAuditStore.scan 相同的升级兼容判定：旧段可能用纯 SHA-256
                if (key != null && AuditCrypto.sha256(input).equals(hash)) {
                    legacy = true;
                } else {
                    return invalid(out, "第 " + lineNo + " 条摘要不匹配", legacy);
                }
            }
            out.add(new AuditRecord(seq, action, payload, prev, hash, ts));
            prev = hash;
        }
        return new Loaded(out, true, legacy, null);
    }

    private static Loaded invalid(List<AuditRecord> out, String problem, boolean legacy) {
        return new Loaded(out, false, legacy, problem);
    }
}
