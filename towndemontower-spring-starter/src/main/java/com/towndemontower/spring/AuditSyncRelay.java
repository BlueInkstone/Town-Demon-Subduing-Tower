package com.towndemontower.spring;

import com.towndemontower.core.audit.AuditSyncSpool;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * [starter] 审计同步暂存区的定时补传与清理（V1.16）。
 *
 * <p>每轮做两件事：先把待补传记录推给远端（成功即删除），再按时间/容量清理到期数据。
 * 两件事各自 try/catch——补传异常不能阻止清理，否则远端长期不可用会先把本地磁盘撑满。</p>
 *
 * <p>spool 为 {@code null}（未启用暂存、或审计存储不是文件模式）时整轮直接跳过。</p>
 */
public class AuditSyncRelay {

    private static final System.Logger LOG = System.getLogger(AuditSyncRelay.class.getName());

    private final AuditSyncSpool spool;

    public AuditSyncRelay(AuditSyncSpool spool) {
        this.spool = spool;
    }

    @Scheduled(initialDelayString = "${towndemontower.audit-sync-retry-interval-ms:60000}",
            fixedDelayString = "${towndemontower.audit-sync-retry-interval-ms:60000}")
    public void tick() {
        runOnce();
    }

    /** 可手动触发（测试与运维端点用）。 */
    public void runOnce() {
        if (spool == null) return;
        try {
            AuditSyncSpool.RetryResult r = spool.retryOnce();
            if (r.delivered() > 0 || r.deadLettered() > 0) {
                LOG.log(System.Logger.Level.INFO, "[governance] 审计同步补传：成功 "
                        + r.delivered() + "、仍失败 " + r.retrying() + "、转 dead "
                        + r.deadLettered() + "、跳过 " + r.skipped());
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[governance] 审计同步补传轮次失败", e);
        }
        try {
            spool.cleanup();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[governance] 审计同步暂存清理失败", e);
        }
    }
}
