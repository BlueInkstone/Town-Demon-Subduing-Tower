package com.towndemontower.spring.tx;

import java.time.Instant;

/**
 * [starter/tx] Outbox 表行映射。
 * publishStatus: PENDING / PUBLISHED / FAILED；retryCount 用于限次重试。
 */
public record OutboxMessage(
        String id,
        String decisionId,
        String eventType,
        String tool,
        String risk,
        String code,
        String decisionStatus,
        String approvalTicketId,
        String publishStatus,
        int retryCount,
        Instant createdAt
) {
}
