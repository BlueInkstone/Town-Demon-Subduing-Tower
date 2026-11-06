package com.towndemontower.spring.tx;

import java.time.Instant;

/**
 * [starter/tx] 推送给人（审批者 / Ops）的治理事件。
 * 由 Outbox 中继在事务提交后通过 {@link ApprovalEventPublisher} 发出（WebSocket 总线 / STOMP）。
 */
public record ApprovalEvent(
        String decisionId,
        String tool,
        String risk,
        String code,
        String decisionStatus,
        String approvalTicketId,
        String eventType,
        Instant createdAt
) {
}
