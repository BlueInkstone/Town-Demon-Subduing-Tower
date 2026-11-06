package com.towndemontower.agents.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * [agents] 文件持久化版 Agent 档案仓储（零外部依赖，纯文本行存储）。
 *
 * <p>每行一条记录，字段以 {@code |} 分隔，编码方式：
 * <pre>
 * id|name|role|status|modelTier|registeredAt|skills|permissions|note|createdAt|updatedAt
 * </pre>
 * 技能与权限用 {@code ,} 分隔；时间为 ISO-8601（{@code registeredAt} 为本地时间，
 * {@code createdAt/updatedAt} 为带时区的 Instant）。文件不存在时视为空注册表。</p>
 *
 * <p>行内 {@code |} 会被替换为 {@code /}（与 delivery 的文件仓储一致），
 * 避免分隔符被业务文本破坏。</p>
 */
public class FileAgentRepository implements AgentRepository {

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String SEP = "|";
    private static final String LIST_SEP = ",";

    private final Path file;
    private final InMemoryAgentRepository delegate = new InMemoryAgentRepository();

    public FileAgentRepository(Path file) throws IOException {
        this.file = file;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        load();
    }

    @Override
    public Agent save(Agent agent) {
        Agent saved = delegate.save(agent);
        flush();
        return saved;
    }

    @Override
    public Optional<Agent> findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public Optional<Agent> findByName(String name) {
        return delegate.findByName(name);
    }

    @Override
    public List<Agent> findAll() {
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
            if (p.length < 11) continue;
            Agent agent = new Agent(
                    p[0].trim(),
                    p[1],
                    AgentRole.parse(p[2]),
                    AgentStatus.parse(p[3]),
                    splitList(p[6]),
                    splitList(p[7]),
                    ModelTier.parse(p[4]),
                    parseTime(p[5]),
                    p[8].isEmpty() ? null : p[8],
                    parseInstant(p[9]),
                    parseInstant(p[10]));
            delegate.save(agent);
        }
    }

    /** 把内存态全量写回磁盘。 */
    private void flush() {
        List<String> lines = new ArrayList<>();
        for (Agent a : delegate.findAll()) {
            lines.add(String.join(SEP,
                    nz(a.id()),
                    nz(a.name()),
                    a.role().name(),
                    a.status().name(),
                    a.modelTier().name(),
                    fmt(a.registeredAt()),
                    String.join(LIST_SEP, a.skills()),
                    String.join(LIST_SEP, a.permissions()),
                    nz(a.note()),
                    a.createdAt() == null ? "" : a.createdAt().toString(),
                    a.updatedAt() == null ? "" : a.updatedAt().toString()));
        }
        try {
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("save agents failed: " + file, e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s.replace(SEP, "/").replace("\n", " ");
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "" : TS.format(t);
    }

    private static List<String> splitList(String s) {
        if (s == null || s.trim().isEmpty()) return List.of();
        return Arrays.stream(s.split(LIST_SEP))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .toList();
    }

    private static LocalDateTime parseTime(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        return LocalDateTime.parse(s.trim(), TS);
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        return Instant.parse(s.trim());
    }
}
