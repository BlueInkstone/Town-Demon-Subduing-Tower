package com.towndemontower.core.approval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [core] 计划单的文件存储（V1.14）。
 *
 * <p>追加写、按快照回放，格式刻意保持"无第三方依赖"：</p>
 * <pre>
 * #PLAN-STORE v1
 * b64(id) \t b64(agent) \t b64(task) \t b64(summary) \t STATUS \t requestedMs \t decidedMs \t b64(by) \t b64(note) \t stepsEnc
 * </pre>
 * <p>{@code stepsEnc} 是若干 {@code b64(tool):b64(intent)} 用逗号连接；Base64 URL 字母表
 * 不含 {@code : ,}，所以不需要再套一层转义。所有自由文本字段都单独 Base64，
 * 换行/Tab/中文不会破坏行结构。</p>
 *
 * <p><b>损坏即拒绝</b>：加载时任何一行无法解析都抛 {@link IllegalStateException}，
 * 不跳过、不猜测。计划是审批事实来源，"读不出来就当没有"会让已批准的任务重新被拦，
 * 更糟的是让不该放行的计划被当成不存在。</p>
 *
 * <p><b>按大小轮转（V1.15）</b>：构造时传入 {@code maxBytes > 0}，主文件超过该字节数时，
 * 先把"每个计划单的最后一条快照"压成新文件，原始文件原样移动到 {@code <name>.<millis>.archive}，
 * 再继续追加。压缩只保留状态、不保留中间流转；完整流转仍在审计哈希链里，
 * 归档文件也保留原始行。{@code maxBytes = 0}（默认）表示不轮转，行为与 v1.14 完全一致。</p>
 */
public final class FilePlanApprovalStore implements PlanApprovalStore {

    private static final String HEADER = "#PLAN-STORE v1";
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final Path file;
    /** 主文件超过该字节数时先压缩归档；{@code 0} 表示纯追加、不轮转。 */
    private final long maxBytes;
    private final Object ioLock = new Object();

    /** 纯追加模式（v1.14 行为）。 */
    public FilePlanApprovalStore(Path file) {
        this(file, 0L);
    }

    /**
     * @param maxBytes 轮转阈值（字节）；{@code 0} 表示不轮转
     */
    public FilePlanApprovalStore(Path file, long maxBytes) {
        if (file == null) throw new IllegalArgumentException("file 不能为空");
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes 不能为负");
        this.file = file;
        this.maxBytes = maxBytes;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(file)) {
                Files.writeString(file, HEADER + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE);
            }
        } catch (IOException e) {
            throw new IllegalStateException("计划存储初始化失败：" + file, e);
        }
    }

    public Path file() {
        return file;
    }

    /** 轮转阈值；{@code 0} 表示不轮转。 */
    public long maxBytes() {
        return maxBytes;
    }

    @Override
    public void save(PlanApproval p) {
        if (p == null) throw new IllegalArgumentException("approval 不能为空");
        String line = encode(p) + "\n";
        synchronized (ioLock) {
            try {
                if (maxBytes > 0 && Files.exists(file) && Files.size(file) > maxBytes) {
                    rotate();
                }
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException("计划写入失败：" + file, e);
            }
        }
    }

    @Override
    public List<PlanApproval> load() {
        synchronized (ioLock) {
            return loadUnlocked();
        }
    }

    private List<PlanApproval> loadUnlocked() {
        try {
            if (!Files.exists(file)) return List.of();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<PlanApproval> out = new ArrayList<>();
            int n = 0;
            for (String line : lines) {
                n++;
                if (line == null || line.isBlank() || line.startsWith("#")) continue;
                out.add(decode(line, n));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("计划读取失败：" + file, e);
        }
    }

    /**
     * 把主文件压缩成"每单最后一条快照"，原文件整体移到归档路径。
     *
     * <p>先写临时文件、再移动原文件、最后原子替换：任何一步失败都让主文件保持可读；
     * 替换失败时会把归档移回原位，不让计划单凭空消失。</p>
     */
    private void rotate() throws IOException {
        List<PlanApproval> all = loadUnlocked();
        if (all.isEmpty()) return;
        Map<String, PlanApproval> latest = new LinkedHashMap<>();
        for (PlanApproval p : all) {
            // 先删后放：LinkedHashMap 的插入顺序即"最后一次写入顺序"，压缩后顺序与回放一致。
            latest.remove(p.id);
            latest.put(p.id, p);
        }
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (PlanApproval p : latest.values()) {
            sb.append(encode(p)).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Path archive = archivePath();
        move(file, archive);
        try {
            move(tmp, file);
        } catch (IOException e) {
            move(archive, file);
            throw e;
        }
    }

    /** 归档路径：{@code <name>.<millis>.archive}；同一毫秒重复时加序号，绝不覆盖历史归档。 */
    private Path archivePath() {
        String base = file.getFileName() + "." + System.currentTimeMillis();
        Path p = file.resolveSibling(base + ".archive");
        int n = 1;
        while (Files.exists(p)) {
            p = file.resolveSibling(base + "-" + n++ + ".archive");
        }
        return p;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String encode(PlanApproval p) {
        StringBuilder steps = new StringBuilder();
        for (PlanStep s : p.plan.steps) {
            if (steps.length() > 0) steps.append(',');
            steps.append(ENC.encodeToString(s.tool.getBytes(StandardCharsets.UTF_8)))
                 .append(':')
                 .append(ENC.encodeToString(s.intent.getBytes(StandardCharsets.UTF_8)));
        }
        return String.join("\t",
                ENC.encodeToString(p.id.getBytes(StandardCharsets.UTF_8)),
                ENC.encodeToString(p.agentId.getBytes(StandardCharsets.UTF_8)),
                ENC.encodeToString(p.taskId.getBytes(StandardCharsets.UTF_8)),
                ENC.encodeToString(p.summary.getBytes(StandardCharsets.UTF_8)),
                p.status.name(),
                Long.toString(p.requestedAt.toEpochMilli()),
                p.decidedAt == null ? "" : Long.toString(p.decidedAt.toEpochMilli()),
                p.decidedBy == null ? "" : ENC.encodeToString(p.decidedBy.getBytes(StandardCharsets.UTF_8)),
                p.note == null ? "" : ENC.encodeToString(p.note.getBytes(StandardCharsets.UTF_8)),
                steps.toString());
    }

    private static PlanApproval decode(String line, int lineNo) {
        String[] f = line.split("\t", -1);
        if (f.length != 10) {
            throw corrupt(lineNo, "字段数=" + f.length + "（应为 10）");
        }
        try {
            String id = text(f[0]);
            String agent = text(f[1]);
            String task = text(f[2]);
            String summary = text(f[3]);
            PlanStatus status = PlanStatus.valueOf(f[4].trim());
            Instant requestedAt = Instant.ofEpochMilli(Long.parseLong(f[5].trim()));
            Instant decidedAt = f[6].isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(f[6].trim()));
            String by = f[7].isEmpty() ? null : text(f[7]);
            String note = f[8].isEmpty() ? null : text(f[8]);
            List<PlanStep> steps = decodeSteps(f[9], lineNo);
            if (id.isEmpty() || agent.isEmpty() || task.isEmpty()) {
                throw corrupt(lineNo, "id/agent/task 不可为空");
            }
            return new PlanApproval(id, agent, task, PlanArtifact.of(summary, steps),
                    status, requestedAt, decidedAt, by, note);
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw corrupt(lineNo, e.getMessage());
        }
    }

    private static List<PlanStep> decodeSteps(String enc, int lineNo) {
        if (enc.isEmpty()) return List.of();
        List<PlanStep> out = new ArrayList<>();
        for (String pair : enc.split(",", -1)) {
            String[] p = pair.split(":", -1);
            if (p.length != 2) throw corrupt(lineNo, "步骤格式损坏");
            out.add(new PlanStep(text(p[0]), text(p[1])));
        }
        return out;
    }

    private static String text(String b64) {
        return new String(DEC.decode(b64), StandardCharsets.UTF_8);
    }

    private static IllegalStateException corrupt(int lineNo, String why) {
        return new IllegalStateException("计划存储损坏（第 " + lineNo + " 行）：" + why);
    }
}
