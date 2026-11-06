package com.towndemontower.spring.tx;

import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.SkillResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * [starter/tx] 事务一致性的核心：在单个 Spring 事务内
 * ① 调用 core 装饰器链得到治理结果；② 落库 governance_decision；③ 写 Outbox（PENDING）；
 * ④ 发布提交后事件。事务回滚则三者皆无；提交成功后由 {@link OutboxRelay} 保证事件最终送达 WS。
 *
 * <p>注：本类不标注 @Component，统一由 {@code TownDemontowerAutoConfiguration} 以 @Bean 装配，
 * 避免在被组件扫描时与自动配置重复注册。</p>
 */
public class TransactionalGovernanceDecisionRecorder implements GovernanceDecisionRecorder {

    private final GovernanceDecisionRepository decisionRepository;
    private final OutboxRepository outboxRepository;
    private final ApplicationEventPublisher eventPublisher;

    public TransactionalGovernanceDecisionRecorder(GovernanceDecisionRepository decisionRepository,
                                                   OutboxRepository outboxRepository,
                                                   ApplicationEventPublisher eventPublisher) {
        this.decisionRepository = decisionRepository;
        this.outboxRepository = outboxRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public SkillResult decide(Executor executor, IntentObject intent, ExecutionContext ctx, String risk) {
        // ① 执行治理链（core 零改动；R2 返回 PENDING_APPROVAL 且不出真实工具）
        SkillResult result = executor.execute(intent, ctx);

        String decisionId = UUID.randomUUID().toString();
        String decisionStatus = result.success ? "OK" : (result.code == null ? "DENIED" : result.code);
        Instant now = Instant.now();

        // ② 决策落库
        decisionRepository.insert(decisionId, intent.tool, risk, result.code,
                decisionStatus, result.approvalTicketId, serializeArgs(intent.args), now);

        // ③ 写 Outbox（同一事务，保证"决策已存"与"待发事件"原子）
        outboxRepository.insert(decisionId, eventTypeFor(result), intent.tool, risk,
                result.code, decisionStatus, result.approvalTicketId, now);

        // ④ 发布提交后事件（仅在事务成功提交后触发中继发布）
        eventPublisher.publishEvent(new GovernanceDecisionCommittedEvent(decisionId));
        return result;
    }

    private static String eventTypeFor(SkillResult r) {
        if (r.success) return "DECISION_MADE";
        if ("PENDING_APPROVAL".equals(r.code)) return "APPROVAL_REQUESTED";
        return "DECISION_DENIED";
    }

    private static String serializeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (!first) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }
}
