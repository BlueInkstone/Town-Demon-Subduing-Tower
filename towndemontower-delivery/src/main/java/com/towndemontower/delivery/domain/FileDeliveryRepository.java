package com.towndemontower.delivery.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * [delivery] 文件持久化版交付项仓储（零外部依赖，纯文本行存储）。
 *
 * <p>每行一条记录，字段以 {@code |} 分隔，编码方式：
 * <pre>
 * id|phaseName|owner|plannedStart|plannedEnd|actualEnd|progressPercent|statusOverride
 * </pre>
 * 时间为 ISO-8601 本地时间；空值写作空串。文件不存在时视为空库。
 */
public class FileDeliveryRepository implements DeliveryRepository {

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String SEP = "|";

    private final Path file;
    private final InMemoryDeliveryRepository delegate = new InMemoryDeliveryRepository();

    public FileDeliveryRepository(Path file) throws IOException {
        this.file = file;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        load();
    }

    @Override
    public DeliveryItem save(DeliveryItem item) {
        DeliveryItem saved = delegate.save(item);
        flush();
        return saved;
    }

    @Override
    public Optional<DeliveryItem> findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public List<DeliveryItem> findAll() {
        return delegate.findAll();
    }

    @Override
    public boolean deleteById(String id) {
        boolean removed = delegate.deleteById(id);
        if (removed) flush();
        return removed;
    }

    @Override
    public int count() {
        return delegate.count();
    }

    /** 从磁盘载入全部记录。 */
    private void load() throws IOException {
        if (!Files.exists(file)) return;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            String[] p = line.split("\\" + SEP, -1);
            if (p.length < 8) continue;
            DeliveryItem item = new DeliveryItem(
                    p[0],
                    p[1],
                    p[2],
                    parseTime(p[3]),
                    parseTime(p[4]),
                    parseTime(p[5]),
                    Integer.parseInt(p[6].trim()),
                    p[7].trim().isEmpty() ? null : DeliveryStatus.valueOf(p[7].trim()));
            delegate.save(item);
        }
    }

    /** 把内存态全量写回磁盘。 */
    private void flush() {
        List<String> lines = new ArrayList<>();
        for (DeliveryItem i : delegate.findAll()) {
            lines.add(String.join(SEP,
                    nz(i.id()),
                    nz(i.phaseName()),
                    nz(i.owner()),
                    fmt(i.plannedStart()),
                    fmt(i.plannedEnd()),
                    fmt(i.actualEnd()),
                    String.valueOf(i.progressPercent()),
                    i.statusOverride() == null ? "" : i.statusOverride().name()));
        }
        try {
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("save delivery items failed: " + file, e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s.replace(SEP, "/").replace("\n", " ");
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "" : TS.format(t);
    }

    private static LocalDateTime parseTime(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        return LocalDateTime.parse(s.trim(), TS);
    }
}
