package com.towndemontower.core.decorator;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.context.PipelineState;
import com.towndemontower.core.context.PipelineStateStore;
import com.towndemontower.core.executor.CapabilityException;
import com.towndemontower.core.executor.IntentFormatException;
import com.towndemontower.core.executor.PermissionDeniedException;
import com.towndemontower.core.executor.SchemaValidationException;
import com.towndemontower.core.executor.SnapshotFailedException;
import com.towndemontower.core.metrics.GovernanceMetrics;

import java.time.Instant;

/**
 * [core] 最外层装饰器：审计。无论链路是放行、拒绝还是异常，都追加写审计并接哈希链。
 * 关键不变量（FR-12）：Permission 拒绝 / 任何异常下，审计仍记录。
 *
 * <p><b>V1.5 接线</b>：把另外两件"每次调用都必须发生"的事挂在这里，而不是新增装饰器层——
 * 链的环节与顺序是写死的（10 层），不应为了记录指标/落状态而改变拓扑：</p>
 * <ol>
 *   <li>{@link GovernanceMetrics}：按结果码计数，供指标层暴露；</li>
 *   <li>{@link PipelineStateStore}：把本次调用的模式与裁决写入任务状态（断点续跑、
 *       门禁结果留档、审计关联都依赖它）。</li>
 * </ol>
 *
 * <p>两者均可选：为 null 时行为与旧版完全一致（旧构造器保留）。状态落盘失败<b>不影响裁决</b>
 * ——治理的正确性不依赖存储可用性——但会在审计里记一条 {@code STATE_PERSIST_FAILED}。</p>
 *
 * <p>指标口径：{@code OK} 只计总数；{@code PENDING_APPROVAL} 计入"待审批"（过度保守的分母），
 * <b>不计入拒绝</b>；其余非 OK 码计入拒绝。这样"拒绝率"与"转人工率"才是两个独立的数。</p>
 */
public class AuditDecorator implements Executor {

    private static final String OK = "OK";
    private static final String PENDING = "PENDING_APPROVAL";

    private final Executor next;
    private final AuditStore audit;
    private final GovernanceMetrics metrics;
    private final PipelineStateStore stateStore;

    public AuditDecorator(Executor next, AuditStore audit) {
        this(next, audit, null, null);
    }

    public AuditDecorator(Executor next, AuditStore audit,
                          GovernanceMetrics metrics, PipelineStateStore stateStore) {
        this.next = next;
        this.audit = audit;
        this.metrics = metrics;
        this.stateStore = stateStore;
    }

    @Override
    public SkillResult execute(IntentObject intent, ExecutionContext ctx) {
        try {
            SkillResult r = next.execute(intent, ctx);
            audit.appendAuto("SKILL_INVOKED",
                    intent.tool + "|" + r.code + "|task=" + ctx.taskId + "|mode=" + ctx.mode
                            + attribution(ctx));
            record(ctx, r.code);
            return r;
        } catch (RuntimeException ex) {
            String code = map(ex);
            audit.appendAuto("SKILL_INVOKED",
                    intent.tool + "|" + code + "|" + ex.getClass().getSimpleName() + "|task=" + ctx.taskId
                            + attribution(ctx));
            record(ctx, code);
            return SkillResult.denied(code, safeMessage(code, ex));
        }
    }

    /**
     * 归属信息：这次调用是<b>哪个 Agent</b>、<b>哪个调用者</b>发起的（V1.9 / I3-b）。
     *
     * <p>此前审计只记 {@code task} 与 {@code mode}，看日志答不出"谁干的"——
     * 所有注册表写操作都归属同一个配置身份。绑定后 {@code ctx.agentId} 随请求变化，
     * 这里把它落进每一条 {@code SKILL_INVOKED}，归属就不再依赖接口日志。</p>
     *
     * <p>值可能为空字符串（无绑定的旧调用方），但字段本身恒出现：
     * 稳定的 payload 结构比"有时有、有时没有"更好解析。</p>
     */
    private static String attribution(ExecutionContext ctx) {
        return "|agent=" + nullSafe(ctx.agentId) + "|owner=" + nullSafe(ctx.ownerUserId);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 对外错误信息脱敏。
     *
     * <p>内部错误一律不回显异常文本——堆栈/类名/SQL 片段都是攻击者的信息来源；
     * 完整信息留在审计里（审计可达但不对调用方开放）。非内部错误的业务提示保留，
     * 但压成单行并限长，避免多行文本伪造日志行。</p>
     */
    private static String safeMessage(String code, RuntimeException ex) {
        if ("INTERNAL_ERROR".equals(code)) {
            return "internal error（详情见审计记录，不在响应中暴露）";
        }
        String m = ex.getMessage();
        if (m == null || m.isBlank()) return code;
        String oneLine = m.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }

    /** 指标计数 + 流水线状态落盘。两者都是"尽力而为"，绝不影响裁决结果。 */
    private void record(ExecutionContext ctx, String code) {
        if (metrics != null) {
            metrics.inc(GovernanceMetrics.TOTAL);
            if (code != null && !code.isEmpty() && !OK.equals(code)) {
                metrics.inc(code);
                if (PENDING.equals(code)) {
                    metrics.inc(GovernanceMetrics.PENDING_APPROVAL);
                } else {
                    metrics.inc(GovernanceMetrics.DENIED);
                }
            }
        }
        if (stateStore == null || ctx == null || ctx.taskId == null) return;
        try {
            PipelineState s = stateStore.load(ctx.taskId);
            if (s == null) {
                s = new PipelineState();
                s.taskId = ctx.taskId;
            }
            s.mode = ctx.mode;
            s.lastDecision = code;
            s.updatedAt = Instant.now().toString();
            stateStore.save(ctx.taskId, s);
        } catch (RuntimeException ex) {
            audit.appendAuto("STATE_PERSIST_FAILED", ctx.taskId + "|" + ex.getClass().getSimpleName());
        }
    }

    private static String map(RuntimeException ex) {
        if (ex instanceof IntentFormatException || ex instanceof SchemaValidationException)
            return "INTENT_FORMAT_INVALID";
        if (ex instanceof PermissionDeniedException) return "PERMISSION_DENIED";
        if (ex instanceof CapabilityException) return "CAPABILITY_NOT_ALLOWED";
        if (ex instanceof SnapshotFailedException) return "SNAPSHOT_FAILED";
        return "INTERNAL_ERROR";
    }
}
