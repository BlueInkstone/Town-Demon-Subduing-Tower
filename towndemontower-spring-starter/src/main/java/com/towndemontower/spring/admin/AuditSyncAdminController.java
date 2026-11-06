package com.towndemontower.spring.admin;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.AuditSyncSpool;
import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.spring.TownDemontowerProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [starter/admin] 审计同步暂存区运维接口（V1.16）。
 *
 * <p>暴露四件事：<b>看</b>（统计 + 待补传）、<b>看死信</b>、<b>手动补传一轮</b>、<b>手动清理</b>。
 * 定时任务 {@code AuditSyncRelay} 仍在后台按周期执行，这里的触发是"立即执行一次"，
 * 用于远端刚恢复或磁盘告警时的现场处置。</p>
 *
 * <p><b>不提供删除待补传记录、不提供改快照内容</b>：暂存区保存的是尚未送达的证据，
 * 管理面能删就能"悄悄抹掉一次同步失败"。死信只能在文件系统层人工处理。</p>
 */
@RestController
@RequestMapping("/towndemontower/admin/sync-spool")
public class AuditSyncAdminController {

    private final AuditStore auditStore;
    private final AdminGuard guard;

    public AuditSyncAdminController(AuditStore auditStore, TownDemontowerProperties props) {
        this.auditStore = auditStore;
        this.guard = new AdminGuard(props);
    }

    /** 统计 + 待补传记录（不含 dead）。 */
    @GetMapping
    public ApiResponse status(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token,
            @RequestParam(value = "limit", required = false) Integer limit) {
        guard.check(token);
        AuditSyncSpool spool = requireSpool();
        int lim = AdminParams.limit(limit, 50, 200);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dir", spool.dir().toString());
        data.put("stats", stats(spool.stats()));
        List<AuditSyncSpool.PendingSync> pending = spool.pending();
        data.put("pendingTotal", pending.size());
        data.put("items", views(pending, lim));
        return ApiResponse.ok(data);
    }

    /** 死信列表：补传次数达上限、需人工处理。 */
    @GetMapping("/dead")
    public ApiResponse dead(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token,
            @RequestParam(value = "limit", required = false) Integer limit) {
        guard.check(token);
        AuditSyncSpool spool = requireSpool();
        int lim = AdminParams.limit(limit, 50, 200);
        List<AuditSyncSpool.PendingSync> dead = spool.dead();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deadTotal", dead.size());
        data.put("items", views(dead, lim));
        return ApiResponse.ok(data);
    }

    /** 立即补传一轮（与定时任务同一逻辑，互斥由 spool 的同步方法保证）。 */
    @PostMapping("/retry")
    public ApiResponse retry(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token) {
        guard.check(token);
        AuditSyncSpool.RetryResult result = requireSpool().retryOnce();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("delivered", result.delivered());
        data.put("retrying", result.retrying());
        data.put("deadLettered", result.deadLettered());
        data.put("skipped", result.skipped());
        return ApiResponse.ok(data);
    }

    /** 立即按"时间 + 容量"双阈值清理一次。 */
    @PostMapping("/cleanup")
    public ApiResponse cleanup(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token) {
        guard.check(token);
        AuditSyncSpool.CleanupResult result = requireSpool().cleanup();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("droppedByAge", result.droppedByAge());
        data.put("droppedBySize", result.droppedBySize());
        data.put("remaining", result.remaining());
        data.put("remainingBytes", result.remainingBytes());
        return ApiResponse.ok(data);
    }

    private AuditSyncSpool requireSpool() {
        AuditSyncSpool spool = auditStore instanceof FileAuditStore file ? file.syncSpool() : null;
        if (spool == null) {
            throw new AdminApiException(HttpStatus.NOT_FOUND, "SPOOL_DISABLED",
                    "同步暂存区未启用：需要文件审计存储、至少一个锚点副本，且未关闭 audit-sync-spool-enabled");
        }
        return spool;
    }

    private static Map<String, Object> stats(AuditSyncSpool.Stats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pending", s.pending());
        m.put("pendingBytes", s.pendingBytes());
        m.put("dead", s.dead());
        m.put("deadBytes", s.deadBytes());
        m.put("captured", s.captured());
        m.put("retried", s.retried());
        m.put("delivered", s.delivered());
        m.put("failed", s.failed());
        m.put("deadLettered", s.deadLettered());
        m.put("dropped", s.dropped());
        m.put("skipped", s.skipped());
        return m;
    }

    private static List<Map<String, Object>> views(List<AuditSyncSpool.PendingSync> list, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditSyncSpool.PendingSync p : list.subList(0, Math.min(limit, list.size()))) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("file", p.file().getFileName().toString());
            one.put("replica", p.replica());
            one.put("attempts", p.attempts());
            one.put("createdAt", Instant.ofEpochMilli(p.createdAt()).toString());
            one.put("lastError", p.lastError());
            one.put("snapshot", p.snapshotLine());
            out.add(one);
        }
        return out;
    }
}
