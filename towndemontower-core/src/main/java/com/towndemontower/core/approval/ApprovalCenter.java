package com.towndemontower.core.approval;

import com.towndemontower.core.contract.ApprovalTicket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [core] 审批中心：把"R2 转人工"从"返回一个单号就结束"变成<b>可查询、可决策、会超时</b>的实体。
 *
 * <p><b>为什么必须有它</b>：没有中心的审批是空转——单号是内存自增，重启即失效；
 * 没人能查有哪些单在等；也没有超时，于是调用方要么永久等待，要么自己加"超时就放行"的补丁，
 * 后者等于把 R2 降级成 R0。</p>
 *
 * <p><b>三条硬约束</b>：
 * <ol>
 *   <li><b>禁自批</b>：{@code approver} 与 {@code requestedBy} 相同即拒绝（含 null 双方，
 *       视为无法确认身份，一律拒绝）。</li>
 *   <li><b>超时即拒</b>：超过 TTL 仍 PENDING 的单在下次操作时被置为 {@link ApprovalStatus#EXPIRED}，
 *       视为 DENIED，不放行。</li>
 *   <li><b>状态单向</b>：PENDING → APPROVED / DENIED / EXPIRED，终态不可再改
 *       （重复决策返回 false 而不是覆盖，避免"先拒后批"把审计搞乱）。</li>
 * </ol>
 *
 * <p>持久化可选：给定目录时，每单写一个 {@code <id>.ticket} 文件，启动/查询时按目录扫描，
 * 因此重启后单据仍在。<b>这是"审批不因重启丢失"的最低要求</b>，真实生产应换 DB。</p>
 */
public class ApprovalCenter {

    /** 默认 TTL：1 小时。 */
    public static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final Map<String, ApprovalRecord> records = new ConcurrentHashMap<>();
    private final Path dir;
    private final Duration ttl;

    public ApprovalCenter() {
        this(null, DEFAULT_TTL);
    }

    public ApprovalCenter(Path dir, Duration ttl) {
        this.dir = dir;
        this.ttl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? DEFAULT_TTL : ttl;
        loadFromDisk();
    }

    /** 登记一张待审批单，返回单号（即 ticket.id）。 */
    public String create(ApprovalTicket ticket, String requestedBy, Instant now) {
        Instant at = now == null ? Instant.now() : now;
        ApprovalRecord r = new ApprovalRecord(
                ticket.id, ticket.taskId, ticket.tool, ticket.riskLevel,
                ticket.impactNote, ticket.rollbackPlan,
                requestedBy, ApprovalStatus.PENDING, at, at.plus(ttl), null, null, null);
        records.put(r.id, r);
        persist(r);
        return r.id;
    }

    /** 人工批准。禁自批；非 PENDING 返回 false。 */
    public boolean approve(String id, String approver, String comment, Instant now) {
        return decide(id, true, approver, comment, now);
    }

    /** 人工驳回。 */
    public boolean deny(String id, String approver, String comment, Instant now) {
        return decide(id, false, approver, comment, now);
    }

    private boolean decide(String id, boolean approved, String approver, String comment, Instant now) {
        ApprovalRecord r = records.get(id);
        if (r == null) return false;
        Instant at = now == null ? Instant.now() : now;
        synchronized (r.id.intern()) {
            ApprovalRecord cur = records.get(r.id);
            if (cur == null || cur.status != ApprovalStatus.PENDING) return false;
            if (at.isAfter(cur.deadline)) {
                ApprovalRecord expired = cur.with(ApprovalStatus.EXPIRED, null, "timeout", at);
                records.put(cur.id, expired);
                persist(expired);
                return false;
            }
            // 禁自批：申请人与审批人相同（含都为 null）一律拒绝
            if (approver == null || approver.equals(cur.requestedBy)) return false;
            ApprovalRecord next = cur.with(
                    approved ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED, approver, comment, at);
            records.put(cur.id, next);
            persist(next);
            return true;
        }
    }

    /**
     * 把超时未决的单置为 EXPIRED。
     *
     * @return 本次过期的单数（>0 时应告警：说明有人在等审批）
     */
    public int expireOverdue(Instant now) {
        Instant at = now == null ? Instant.now() : now;
        int n = 0;
        for (ApprovalRecord r : new ArrayList<>(records.values())) {
            if (r.status == ApprovalStatus.PENDING && at.isAfter(r.deadline)) {
                ApprovalRecord expired = r.with(ApprovalStatus.EXPIRED, null, "timeout", at);
                records.put(r.id, expired);
                persist(expired);
                n++;
            }
        }
        return n;
    }

    public Optional<ApprovalRecord> find(String id) {
        return Optional.ofNullable(records.get(id));
    }

    public ApprovalStatus statusOf(String id) {
        ApprovalRecord r = records.get(id);
        return r == null ? null : r.status;
    }

    /** 全部单据，按创建时间倒序（最新的先看）。 */
    public List<ApprovalRecord> list() {
        List<ApprovalRecord> out = new ArrayList<>(records.values());
        out.sort(Comparator.comparing((ApprovalRecord r) -> r.createdAt).reversed());
        return out;
    }

    public List<ApprovalRecord> list(ApprovalStatus status) {
        List<ApprovalRecord> out = new ArrayList<>();
        for (ApprovalRecord r : list()) if (r.status == status) out.add(r);
        return out;
    }

    public Duration ttl() {
        return ttl;
    }

    /* ---------- 持久化（可选） ---------- */

    private void persist(ApprovalRecord r) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(r.id + ".ticket"), r.toLine().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 单据落盘失败不应改变已做出的决策语义，但必须让对方知道：
            throw new IllegalStateException("approval persist failed for " + r.id, e);
        }
    }

    private void loadFromDisk() {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".ticket")).forEach(p -> {
                try {
                    String line = Files.readString(p, StandardCharsets.UTF_8).trim();
                    ApprovalRecord r = ApprovalRecord.parse(line);
                    if (r != null) records.putIfAbsent(r.id, r);
                } catch (IOException ignored) {
                    // 损坏的单据文件不影响其它单据加载
                }
            });
        } catch (IOException ignored) {
            // 目录不可读时退化为纯内存
        }
    }
}
