package com.towndemontower.core.executor;

import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillDescriptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [core] 执行前快照服务（V1.5 重写：从"记一行日志"改为"真副本 + 可恢复"）。
 *
 * <p>职责：写操作前把<b>目标文件的实际内容</b>复制到快照目录，并写一份清单
 * （原路径、当时是否存在、副本文件名、回滚提示）。{@link #restore(String)} 依据清单还原：
 * 原来存在的文件恢复原内容，原来<b>不存在</b>的文件删除——后者对应"新建文件"的回滚，
 * 只备份不记录"原本不存在"是回滚不干净的最常见原因。</p>
 *
 * <p>目标文件的识别：从意图参数中按 {@link #PATH_KEYS} 取路径类参数。
 * 这是有意的克制——通用文件系统快照需要 hook 到 OS 层，本产品不做；
 * 因此快照覆盖率等于"工具把目标路径写在约定参数里"的比例，这一点在文档中明示。</p>
 *
 * <p>兼容：{@link #snapshots} 与 {@link #failNext} 保留原有语义，旧调用方与测试不受影响。</p>
 */
public class SnapshotService {

    /** 被视为"目标路径"的参数名（大小写不敏感）。 */
    private static final Set<String> PATH_KEYS =
            Set.of("path", "file", "filepath", "target", "targetpath", "source", "dest");

    private static final int MAX_PATH_LEN = 4096;

    /** 快照记录（兼容旧行为：一行文本）。 */
    public final List<String> snapshots = new CopyOnWriteArrayList<>();

    /** 置 true 时下一次写操作直接失败，用于验证 fail-closed 行为。 */
    public volatile boolean failNext = false;

    private final Path root;
    private final AtomicLong seq = new AtomicLong();
    /** 敏感信息脱敏器（可为 null，表示不脱敏）。用于快照清单与内存记录中的参数。 */
    private final java.util.function.UnaryOperator<String> redactor;

    public SnapshotService() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "towndemontower-snapshots"), null);
    }

    public SnapshotService(Path root) {
        this(root, null);
    }

    public SnapshotService(Path root, java.util.function.UnaryOperator<String> redactor) {
        this.root = root;
        this.redactor = redactor;
    }

    private String redact(String s) {
        if (s == null || redactor == null) return s;
        String out = redactor.apply(s);
        return out == null ? s : out;
    }

    public Path root() {
        return root;
    }

    /**
     * 写前快照。
     *
     * @return 快照 ID（可用于 {@link #restore(String)}）
     * @throws SnapshotFailedException 快照无法完成时抛出，禁止写操作继续（fail-closed）
     */
    public String beforeWrite(IntentObject intent, SkillDescriptor d) {
        if (failNext) {
            throw new SnapshotFailedException("snapshot failed before " + d.name);
        }
        String id = "SNAP-" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        List<Captured> captured = capture(id, intent);
        writeManifest(id, d, intent, captured);
        snapshots.add("SNAP[" + d.name + "] id=" + id
                + " args=" + redact(String.valueOf(intent.args))
                + " rollback=" + redact(intent.rollbackHint)
                + " targets=" + captured.size());
        return id;
    }

    /**
     * 依据快照还原。
     *
     * @return 实际被还原（恢复或删除）的文件数
     */
    public int restore(String id) {
        if (id == null) return 0;
        Path manifest = root.resolve(id).resolve("manifest.tsv");
        if (!Files.isRegularFile(manifest)) return 0;
        int restored = 0;
        try {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                Captured c = Captured.parse(line);
                if (c == null) continue;
                Path target = Path.of(c.originalPath);
                Path stored = root.resolve(id).resolve(c.storedName);
                if (c.existed) {
                    if (!Files.isRegularFile(stored)) continue;
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Files.copy(stored, target, StandardCopyOption.REPLACE_EXISTING);
                    restored++;
                } else {
                    // 原本不存在 → 回滚语义是"删掉它"，不是"恢复它"
                    if (Files.deleteIfExists(target)) restored++;
                }
            }
        } catch (IOException e) {
            throw new SnapshotFailedException("restore failed for " + id + ": " + e.getMessage());
        }
        return restored;
    }

    /** 最近一次快照 ID（无则 null）。 */
    public String lastSnapshotId() {
        if (snapshots.isEmpty()) return null;
        return extractId(snapshots.get(snapshots.size() - 1));
    }

    /** 已生成的快照 ID 列表。 */
    public List<String> listSnapshotIds() {
        List<String> ids = new ArrayList<>();
        for (String s : snapshots) {
            String id = extractId(s);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static String extractId(String entry) {
        int i = entry.indexOf("id=");
        if (i < 0) return null;
        int j = entry.indexOf(' ', i);
        return j < 0 ? entry.substring(i + 3) : entry.substring(i + 3, j);
    }

    private List<Captured> capture(String id, IntentObject intent) {
        List<Captured> out = new ArrayList<>();
        if (intent == null || intent.args == null) return out;
        Path dir = root.resolve(id);
        int index = 0;
        for (var e : intent.args.entrySet()) {
            String key = e.getKey();
            if (key == null || !PATH_KEYS.contains(key.toLowerCase())) continue;
            Object v = e.getValue();
            if (!(v instanceof String raw) || raw.isBlank() || raw.length() > MAX_PATH_LEN) continue;
            Path target;
            try {
                target = Path.of(raw.trim());
            } catch (InvalidPathException ex) {
                continue; // 非法路径不阻断快照流程，交由 Schema/业务层报错
            }
            if (Files.isDirectory(target)) continue; // 目录快照需递归策略，本期不做
            boolean existed = Files.isRegularFile(target);
            String storedName = "f" + (index++) + ".bin";
            try {
                Files.createDirectories(dir);
                if (existed) {
                    Files.copy(target, dir.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                throw new SnapshotFailedException("snapshot copy failed for " + raw + ": " + ex.getMessage());
            }
            out.add(new Captured(target.toAbsolutePath().toString(), existed, storedName));
        }
        return out;
    }

    private void writeManifest(String id, SkillDescriptor d, IntentObject intent, List<Captured> captured) {
        StringBuilder sb = new StringBuilder();
        sb.append("# tool=").append(d.name)
                .append(" rollback=").append(redact(intent.rollbackHint == null ? "" : intent.rollbackHint))
                .append('\n');
        for (Captured c : captured) sb.append(c.toLine()).append('\n');
        try {
            Path dir = root.resolve(id);
            Files.createDirectories(dir);
            Files.write(dir.resolve("manifest.tsv"), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SnapshotFailedException("manifest write failed: " + e.getMessage());
        }
    }

    /** 单个目标的快照记录。 */
    private record Captured(String originalPath, boolean existed, String storedName) {

        String toLine() {
            return "target\t" + clean(originalPath) + "\texisted\t" + existed + "\tstored\t" + storedName;
        }

        static Captured parse(String line) {
            if (line == null || line.startsWith("#") || line.isBlank()) return null;
            String[] p = line.split("\t");
            if (p.length < 6 || !"target".equals(p[0])) return null;
            return new Captured(p[1], Boolean.parseBoolean(p[3]), p[5]);
        }

        private static String clean(String s) {
            return s.replace('\t', ' ').replace('\n', ' ');
        }
    }
}
