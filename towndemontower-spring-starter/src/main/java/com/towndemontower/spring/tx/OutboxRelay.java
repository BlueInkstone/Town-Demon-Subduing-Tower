package com.towndemontower.spring.tx;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * [starter/tx] Outbox 中继：把"已落库但待发布"的事件最终送达 WS 总线。
 *
 * <p>两条触发路径，互为兜底：
 * ① {@link TransactionalEventListener#phase()=AFTER_COMMIT}：业务事务提交后立即尝试发布（常见路径）；
 * ② {@link Scheduled} 周期轮询：覆盖"提交后进程崩溃 / 首次发布失败"等未送达场景（崩溃恢复）。</p>
 *
 * <p>发布失败仅把行标记为重试 +1，保持 PENDING 等待下次；超过 {@link #MAX_RETRY} 视为需人工介入（告警）。</p>
 *
 * <p>注：本类不标注 @Component，统一由自动配置以 @Bean 装配，避免组件扫描重复注册。</p>
 */
public class OutboxRelay {

    /** 单条消息最大重试次数，超过后停止自动重试（交由告警/人工）。 */
    public static final int MAX_RETRY = 5;

    private final OutboxRepository outboxRepository;
    private final ApprovalEventPublisher publisher;

    public OutboxRelay(OutboxRepository outboxRepository, ApprovalEventPublisher publisher) {
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
    }

    /** 业务事务提交后：发布该决策关联的待发事件。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCommitted(GovernanceDecisionCommittedEvent event) {
        publishPending(outboxRepository.findPendingByDecision(event.decisionId()));
    }

    /** 周期轮询：崩溃恢复 + 失败重试。 */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void poll() {
        publishPending(outboxRepository.findPending());
    }

    private void publishPending(List<OutboxMessage> pending) {
        for (OutboxMessage m : pending) {
            if (m.retryCount() >= MAX_RETRY) {
                continue; // 超过上限：交由告警/人工，不再自动重试
            }
            try {
                publisher.publish(toEvent(m));
                outboxRepository.markPublished(m.id());
            } catch (Exception ex) {
                outboxRepository.incrementRetry(m.id());
            }
        }
    }

    private static ApprovalEvent toEvent(OutboxMessage m) {
        return new ApprovalEvent(m.decisionId(), m.tool(), m.risk(), m.code(),
                m.decisionStatus(), m.approvalTicketId(), m.eventType(), m.createdAt());
    }
}
