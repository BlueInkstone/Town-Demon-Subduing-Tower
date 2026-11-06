package com.towndemontower.delivery.governance;

import com.towndemontower.core.approval.ApprovalPolicy;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.executor.ExecutorFactory;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;
import com.towndemontower.core.executor.SkillInvoker;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.executor.SnapshotService;
import com.towndemontower.core.policy.RiskGate;
import com.towndemontower.delivery.domain.DeliveryItem;
import com.towndemontower.delivery.domain.DeliveryStatus;
import com.towndemontower.delivery.domain.DeliverySummary;
import com.towndemontower.delivery.service.DeliveryNotFoundException;
import com.towndemontower.delivery.service.DeliveryProgressService;
import com.towndemontower.delivery.service.DeliveryValidationException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * [delivery] 交付看板治理门面：把看板写操作接入 core 装饰器链。
 *
 * <p>与 starter 的 {@code AgentSkillAspect} 同构，但本模块<b>不依赖 Spring AOP</b>，
 * 而是用一个"每次调用绑定"的 invoker 直接驱动 core 链，避免为看板引入代理织入的复杂度。</p>
 *
 * <p>链路（外→内）：Audit → ModeToolPolicy → IntentFormat → Schema → Capability
 * → Permission → RiskGate → TaintCheck → Snapshot → Sandbox。</p>
 *
 * <p>因此每一次新增/更新/删除交付项都会：
 * <ul>
 *   <li>经模式白名单校验（交付看板工具在专属白名单内）；</li>
 *   <li>经 Schema 校验（必填参数齐备）；</li>
 *   <li>经 RBAC 校验（需 delivery:write 权限）；</li>
 *   <li>经风险闸门（R1 自动放行，R2 转人工审批）；</li>
 *   <li>经污点校验（污染会话禁止写操作）；</li>
 *   <li>经快照留痕（写前快照）；</li>
 *   <li><b>写入审计哈希链</b>（与业务方主链共享同一个 AuditStore）。</li>
 * </ul>
 *
 * <p><b>业务异常的透传</b>：core 的 {@code AuditDecorator} 会把链内抛出的所有
 * {@code RuntimeException} 统一转成 {@code INTERNAL_ERROR}。而交付项的
 * "字段校验失败 / 交付项不存在"是<b>正常的业务语义</b>（应映射 400 / 404），
 * 不能被降级为治理拒绝。因此门面在链尾把这两类异常包装成 {@link BusinessError}
 * 哨兵对象穿出链外，再由门面还原成原异常抛出——既保证审计留痕（调用确实发生过），
 * 又不丢失 400/404 语义。</p>
 */
public class DeliveryGovernanceFacade {

    private final Executor executor;
    private final DeliveryProgressService service;
    private final boolean governed;
    private final ThreadLocal<SkillInvoker> current = new ThreadLocal<>();

    /**
     * 构造门面并<b>自建</b>交付看板专用治理链。
     *
     * <p>门面自建 Executor 而非由外部注入，是为了打破"门面需要 Executor、Executor 又需要门面"
     * 的循环依赖：链尾 invoker 由本门面提供（{@link #invoker()}），
     * 而 {@link #execute} 使用的是本门面构造时已持有的链引用。</p>
     *
     * @param registry        工具注册表（交付看板工具注册其中；建议复用业务方注册表，
     *                        以便同一条治理链共享工具元数据）
     * @param modePolicy      交付看板专属模式白名单
     * @param governed        是否启用治理链；false 时直接调用领域服务（轻量场景，不进审计链）
     */
    public DeliveryGovernanceFacade(SkillRegistry registry,
                                    DeliveryModeToolPolicy modePolicy,
                                    RiskGate riskGate,
                                    PermissionEngine permissionEngine,
                                    SnapshotService snapshotService,
                                    AuditStore audit,
                                    ApprovalPolicy approvalPolicy,
                                    DeliveryProgressService service,
                                    boolean governed) {
        this.service = service;
        this.governed = governed;
        // 登记交付看板工具元数据
        registry.register(DeliverySkillDescriptors.create());
        registry.register(DeliverySkillDescriptors.update());
        registry.register(DeliverySkillDescriptors.delete());
        registry.register(DeliverySkillDescriptors.query());
        this.executor = ExecutorFactory.buildDefault(
                registry, modePolicy, riskGate, permissionEngine,
                snapshotService, invoker(), audit, approvalPolicy);
    }

    /** 授予交付看板所需权限（RBAC）。 */
    public void grantPermissions(PermissionProvisioner provisioner, String agentId) {
        provisioner.grant(agentId, "delivery:read", "delivery:write");
    }

    /**
     * 创建交付项（受治理）。
     *
     * @throws DeliveryGovernanceException 被治理链拒绝（权限不足 / 模式不允许 / 风险闸门拒绝等）
     * @throws DeliveryValidationException 字段校验失败（业务语义，HTTP 400）
     */
    public DeliveryItem create(ExecutionContext ctx,
                                String phaseName,
                                String owner,
                                LocalDateTime plannedStart,
                                LocalDateTime plannedEnd,
                                LocalDateTime actualEnd,
                                Integer progress,
                                DeliveryStatus statusOverride) {
        Map<String, Object> args = new HashMap<>();
        args.put("phaseName", phaseName);
        args.put("owner", owner);
        args.put("plannedStart", plannedStart == null ? "" : plannedStart.toString());
        args.put("plannedEnd", plannedEnd == null ? "" : plannedEnd.toString());
        if (actualEnd != null) args.put("actualEnd", actualEnd.toString());
        if (progress != null) args.put("progressPercent", progress);
        if (statusOverride != null) args.put("status", statusOverride.name());

        return execute(ctx, DeliveryModeToolPolicy.TOOL_CREATE, args,
                (d, intent) -> service.create(
                        str(intent.args.get("phaseName")),
                        str(intent.args.get("owner")),
                        time(intent.args.get("plannedStart")),
                        time(intent.args.get("plannedEnd")),
                        time(intent.args.get("actualEnd")),
                        num(intent.args.get("progressPercent")),
                        status(intent.args.get("status"))));
    }

    /**
     * 更新交付项（受治理；部分更新：null 字段保持原值）。
     *
     * @param clearStatusOverride true 表示清除人工指定的状态，恢复"自动推导"
     */
    public DeliveryItem update(ExecutionContext ctx,
                                String id,
                                String phaseName,
                                String owner,
                                LocalDateTime plannedStart,
                                LocalDateTime plannedEnd,
                                LocalDateTime actualEnd,
                                Integer progress,
                                DeliveryStatus statusOverride,
                                boolean clearStatusOverride) {
        Map<String, Object> args = new HashMap<>();
        args.put("id", id);
        if (phaseName != null) args.put("phaseName", phaseName);
        if (owner != null) args.put("owner", owner);
        if (plannedStart != null) args.put("plannedStart", plannedStart.toString());
        if (plannedEnd != null) args.put("plannedEnd", plannedEnd.toString());
        if (actualEnd != null) args.put("actualEnd", actualEnd.toString());
        if (progress != null) args.put("progressPercent", progress);
        if (statusOverride != null) args.put("status", statusOverride.name());
        if (clearStatusOverride) args.put("clearStatus", Boolean.TRUE);

        return execute(ctx, DeliveryModeToolPolicy.TOOL_UPDATE, args,
                (d, intent) -> service.update(
                        str(intent.args.get("id")),
                        str(intent.args.get("phaseName")),
                        str(intent.args.get("owner")),
                        time(intent.args.get("plannedStart")),
                        time(intent.args.get("plannedEnd")),
                        time(intent.args.get("actualEnd")),
                        num(intent.args.get("progressPercent")),
                        status(intent.args.get("status")),
                        Boolean.TRUE.equals(intent.args.get("clearStatus"))));
    }

    /** 删除交付项（受治理）。 */
    public void delete(ExecutionContext ctx, String id) {
        Map<String, Object> args = new HashMap<>();
        args.put("id", id);
        execute(ctx, DeliveryModeToolPolicy.TOOL_DELETE, args, (d, intent) -> {
            service.delete(str(intent.args.get("id")));
            return Boolean.TRUE;
        });
    }

    /** 查询看板（受治理，R0 只读）。 */
    public List<DeliveryItem> list(ExecutionContext ctx, DeliveryStatus status) {
        Map<String, Object> args = new HashMap<>();
        if (status != null) args.put("status", status.name());
        return execute(ctx, DeliveryModeToolPolicy.TOOL_QUERY, args,
                (d, intent) -> service.list(status(intent.args.get("status"))));
    }

    /** 整体进度汇总（受治理，R0 只读）。 */
    public DeliverySummary summary(ExecutionContext ctx) {
        Map<String, Object> args = new HashMap<>();
        return execute(ctx, DeliveryModeToolPolicy.TOOL_QUERY, args,
                (d, intent) -> service.summary());
    }

    /** 按 id 查询单个交付项（受治理，R0 只读）。 */
    public Optional<DeliveryItem> findById(ExecutionContext ctx, String id) {
        Map<String, Object> args = new HashMap<>();
        args.put("id", id);
        return execute(ctx, DeliveryModeToolPolicy.TOOL_QUERY, args,
                (d, intent) -> service.findById(id));
    }

    /** 驱动一次受治理调用：绑定 per-call invoker → 执行 core 链 → 校验结果。 */
    private <T> T execute(ExecutionContext ctx, String tool, Map<String, Object> args, Call<T> call) {
        IntentObject intent = new IntentObject(tool, args, 1.0,
                "交付看板操作：" + tool,
                "更新交付进度看板数据",
                "回滚到上一次看板快照");

        // 未启用治理链：直接调用领域服务（不进审计链），用于轻量场景
        if (!governed) {
            return call.invoke(null, intent);
        }

        current.set((d, i) -> {
            try {
                return call.invoke(d, i);
            } catch (DeliveryValidationException | DeliveryNotFoundException e) {
                // 业务语义异常：穿出治理链，由门面还原为 400 / 404
                return new BusinessError(e);
            }
        });
        try {
            SkillResult r = executor.execute(intent, ctx);
            if (!r.success) {
                throw new DeliveryGovernanceException(r.code, r.message, r.approvalTicketId);
            }
            Object out = r.output;
            if (out instanceof BusinessError be) {
                throw be.error;
            }
            @SuppressWarnings("unchecked")
            T result = (T) out;
            return result;
        } finally {
            current.remove();
        }
    }

    /** 提供给 core 链尾使用的 invoker：从 ThreadLocal 取本次调用的真实实现。 */
    private SkillInvoker invoker() {
        return (d, i) -> {
            SkillInvoker c = current.get();
            if (c == null) {
                throw new IllegalStateException("DeliveryGovernanceFacade: no per-call invoker bound");
            }
            return c.invoke(d, i);
        };
    }

    /** 一次受治理调用的内部实现。 */
    @FunctionalInterface
    private interface Call<T> {
        T invoke(SkillDescriptor d, IntentObject intent);
    }

    /**
     * 业务异常哨兵：把领域层的 400/404 语义异常穿过治理链带到链外。
     */
    static final class BusinessError {
        final RuntimeException error;

        BusinessError(RuntimeException error) {
            this.error = error;
        }
    }

    // ---- 参数转换辅助 ----

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static LocalDateTime time(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        return LocalDateTime.parse(s);
    }

    private static Integer num(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        return Integer.valueOf(s);
    }

    private static DeliveryStatus status(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        return DeliveryStatus.parse(s);
    }
}
