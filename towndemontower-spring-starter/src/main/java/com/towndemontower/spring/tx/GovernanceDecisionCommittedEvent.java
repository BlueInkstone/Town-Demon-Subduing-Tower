package com.towndemontower.spring.tx;

/**
 * [starter/tx] 事务提交后事件：携带刚落库的治理决策 id，触发 Outbox 中继发布。
 * 必须在 {@code @Transactional} 方法内通过 ApplicationEventPublisher 发布，
 * 由 {@code @TransactionalEventListener(AFTER_COMMIT)} 在提交后消费。
 */
public record GovernanceDecisionCommittedEvent(String decisionId) {
}
