package com.towndemontower.agents.governance;

import com.towndemontower.agents.domain.AccessProvisioner;
import com.towndemontower.agents.domain.Agent;
import com.towndemontower.agents.domain.AgentRole;
import com.towndemontower.agents.domain.AgentStatus;
import com.towndemontower.agents.domain.AgentSummary;
import com.towndemontower.agents.domain.ModelTier;
import com.towndemontower.agents.service.AgentInactiveException;
import com.towndemontower.agents.service.AgentNotFoundException;
import com.towndemontower.agents.service.AgentService;
import com.towndemontower.agents.service.AgentValidationException;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * [agents] 治理门面：把 Agent 的注册 / 改档 / 状态变更 / 注销接入 core 装饰器链，
 * 并在每次成功变更后<b>同步 RBAC 权限</b>。
 *
 * <p>链路（外→内）：Audit → ModeToolPolicy → IntentFormat → Schema → Capability
 * → Permission → RiskGate → TaintCheck → Snapshot → Sandbox。</p>
 *
 * <p><b>为什么权限同步放在这一层</b>：授权变更必须与档案变更同生共死。
 * 若把同步塞进领域服务，服务就绑死了 core；若放到 Controller，任何绕过 HTTP 的调用
 * （SDK、定时任务、批量导入）都会漏掉授权。门面是唯一必经的入口，放在这里最稳。</p>
 *
 * <p><b>权限口径</b>：
 * <ul>
 *   <li>档案里保存的是"应然权限"（配置），停用/注销后不清空，便于恢复时还原；</li>
 *   <li>RBAC 里是"实然权限"（生效），停用/注销立即回收。</li>
 * </ul>
 * 两者故意分离：接口同时返回"应然"与"实然"，可以一眼看出账实是否一致。</p>
 */
public class AgentGovernanceFacade {

    private final Executor executor;
    private final AgentService service;
    private final AccessProvisioner provisioner;
    private final boolean governed;
    private final ThreadLocal<SkillInvoker> current = new ThreadLocal<>();

    public AgentGovernanceFacade(SkillRegistry registry,
                                 AgentsModeToolPolicy modePolicy,
                                 RiskGate riskGate,
                                 PermissionEngine permissionEngine,
                                 SnapshotService snapshotService,
                                 AuditStore audit,
                                 ApprovalPolicy approvalPolicy,
                                 AgentService service,
                                 AccessProvisioner provisioner,
                                 boolean governed) {
        this.service = service;
        this.provisioner = provisioner;
        this.governed = governed;
        registry.register(AgentSkillDescriptors.register());
        registry.register(AgentSkillDescriptors.update());
        registry.register(AgentSkillDescriptors.changeStatus());
        registry.register(AgentSkillDescriptors.delete());
        registry.register(AgentSkillDescriptors.query());
        this.executor = ExecutorFactory.buildDefault(
                registry, modePolicy, riskGate, permissionEngine,
                snapshotService, invoker(), audit, approvalPolicy);
    }

    /**
     * 授予注册表自身所需的权限（RBAC）。
     *
     * <p>V1.9 起必须走 {@link PermissionProvisioner} 端口：{@code PermissionEngine}
     * 的写方法已收为包内可见，门面拿到的引擎只能读。</p>
     */
    public void grantPermissions(PermissionProvisioner provisioner, String agentId) {
        provisioner.grant(agentId, "agents:read", "agents:write");
    }

    /** 注册 Agent（受治理），成功后按角色模板/入参授予权限。 */
    public Agent register(ExecutionContext ctx,
                          String name,
                          AgentRole role,
                          List<String> skills,
                          List<String> permissions,
                          ModelTier modelTier,
                          LocalDateTime registeredAt,
                          String note) {
        if (role == null) {
            throw new AgentValidationException("role", "角色不能为空");
        }
        Map<String, Object> args = new HashMap<>();
        args.put("name", name == null ? "" : name);
        args.put("role", role.name());
        if (skills != null) args.put("skills", String.join(",", skills));
        if (permissions != null) args.put("permissions", String.join(",", permissions));
        if (modelTier != null) args.put("modelTier", modelTier.name());
        if (registeredAt != null) args.put("registeredAt", registeredAt.toString());
        if (note != null) args.put("note", note);

        Agent agent = execute(ctx, AgentsModeToolPolicy.TOOL_REGISTER, args,
                (d, intent) -> service.register(
                        str(intent.args.get("name")),
                        role(intent.args.get("role")),
                        list(intent.args.get("skills")),
                        list(intent.args.get("permissions")),
                        tier(intent.args.get("modelTier")),
                        time(intent.args.get("registeredAt")),
                        str(intent.args.get("note"))));
        syncAccess(agent);
        return agent;
    }

    /** 改档（受治理，部分更新：null 保持原值，空清单表示清空），成功后重新同步权限。 */
    public Agent update(ExecutionContext ctx,
                        String id,
                        String name,
                        AgentRole role,
                        List<String> skills,
                        List<String> permissions,
                        ModelTier modelTier,
                        LocalDateTime registeredAt,
                        String note) {
        Map<String, Object> args = new HashMap<>();
        args.put("id", id);
        if (name != null) args.put("name", name);
        if (role != null) args.put("role", role.name());
        if (skills != null) args.put("skills", String.join(",", skills));
        if (permissions != null) args.put("permissions", String.join(",", permissions));
        if (modelTier != null) args.put("modelTier", modelTier.name());
        if (registeredAt != null) args.put("registeredAt", registeredAt.toString());
        if (note != null) args.put("note", note);

        Agent agent = execute(ctx, AgentsModeToolPolicy.TOOL_UPDATE, args,
                (d, intent) -> service.update(
                        str(intent.args.get("id")),
                        str(intent.args.get("name")),
                        role(intent.args.get("role")),
                        list(intent.args.get("skills")),
                        list(intent.args.get("permissions")),
                        tier(intent.args.get("modelTier")),
                        time(intent.args.get("registeredAt")),
                        str(intent.args.get("note"))));
        syncAccess(agent);
        return agent;
    }

    /** 状态流转（受治理）。SUSPENDED / RETIRED 立即回收权限，恢复 ACTIVE 则按档案重新授予。 */
    public Agent changeStatus(ExecutionContext ctx, String id, AgentStatus status) {
        if (status == null) {
            throw new AgentValidationException("status", "状态不能为空");
        }
        Map<String, Object> args = new HashMap<>();
        args.put("id", id);
        args.put("status", status.name());

        Agent agent = execute(ctx, AgentsModeToolPolicy.TOOL_CHANGE_STATUS, args,
                (d, intent) -> service.changeStatus(
                        str(intent.args.get("id")),
                        status(intent.args.get("status"))));
        syncAccess(agent);
        return agent;
    }

    /** 注销并删除档案（受治理，仅对 RETIRED 生效），并确保权限已回收。 */
    public void delete(ExecutionContext ctx, String id) {
        Map<String, Object> args = new HashMap<>();
        args.put("id", id);
        execute(ctx, AgentsModeToolPolicy.TOOL_DELETE, args, (d, intent) -> {
            service.delete(str(intent.args.get("id")));
            return Boolean.TRUE;
        });
        provisioner.revokeAll(id);
    }

    /** 查询注册表（受治理，R0 只读）。 */
    public List<Agent> list(ExecutionContext ctx, AgentStatus status, AgentRole role, String keyword) {
        Map<String, Object> args = new HashMap<>();
        if (status != null) args.put("status", status.name());
        if (role != null) args.put("role", role.name());
        if (keyword != null) args.put("keyword", keyword);
        return execute(ctx, AgentsModeToolPolicy.TOOL_QUERY, args,
                (d, intent) -> service.list(
                        status(intent.args.get("status")),
                        role(intent.args.get("role")),
                        str(intent.args.get("keyword"))));
    }

    /** 汇总（受治理，R0 只读）。 */
    public AgentSummary summary(ExecutionContext ctx) {
        return execute(ctx, AgentsModeToolPolicy.TOOL_QUERY, new HashMap<>(),
                (d, intent) -> service.summary());
    }

    /** 按 ID 查询（受治理，R0 只读）。 */
    public Optional<Agent> findById(ExecutionContext ctx, String id) {
        Map<String, Object> args = new HashMap<>();
        args.put("id", id);
        return execute(ctx, AgentsModeToolPolicy.TOOL_QUERY, args,
                (d, intent) -> service.findById(str(intent.args.get("id"))));
    }

    /**
     * 查询 Agent 的"实然权限"（RBAC 中当前真实生效的权限）。
     *
     * <p>直接读授权端口，不进治理链：这只是一次内存读取，且调用方通常是已经通过
     * {@code agents:read} 的列表接口。</p>
     */
    public Set<String> effectivePermissions(String agentId) {
        return provisioner.currentPermissions(agentId);
    }

    /** 执行前守卫：非 ACTIVE 的 Agent 不允许派发任务（供其它模块接入）。 */
    public Agent assertCanAct(String agentId) {
        return service.assertCanAct(agentId);
    }

    /**
     * 同步权限：保留权限的状态（ACTIVE / PAUSED）按档案授予，SUSPENDED / RETIRED 整员回收。
     */
    private void syncAccess(Agent agent) {
        if (agent.retainsPermissions()) {
            provisioner.grant(agent.id(), new ArrayList<>(agent.permissions()));
        } else {
            provisioner.revokeAll(agent.id());
        }
    }

    /** 驱动一次受治理调用：绑定 per-call invoker → 执行 core 链 → 校验结果。 */
    private <T> T execute(ExecutionContext ctx, String tool, Map<String, Object> args, Call<T> call) {
        IntentObject intent = new IntentObject(tool, args, 1.0,
                "Agent 注册表操作：" + tool,
                "变更 Agent 档案与授权",
                "回滚到上一次注册表快照");

        if (!governed) {
            return call.invoke(null, intent);
        }

        current.set((d, i) -> {
            try {
                return call.invoke(d, i);
            } catch (AgentValidationException | AgentNotFoundException | AgentInactiveException e) {
                return new BusinessError(e);
            }
        });
        try {
            SkillResult r = executor.execute(intent, ctx);
            if (!r.success) {
                throw new AgentGovernanceException(r.code, r.message, r.approvalTicketId);
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

    private SkillInvoker invoker() {
        return (d, i) -> {
            SkillInvoker c = current.get();
            if (c == null) {
                throw new IllegalStateException("AgentGovernanceFacade: no per-call invoker bound");
            }
            return c.invoke(d, i);
        };
    }

    @FunctionalInterface
    private interface Call<T> {
        T invoke(SkillDescriptor d, IntentObject intent);
    }

    static final class BusinessError {
        final RuntimeException error;

        BusinessError(RuntimeException error) {
            this.error = error;
        }
    }

    // ---- 参数转换辅助 ----

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isEmpty() ? null : s;
    }

    private static LocalDateTime time(Object o) {
        String s = str(o);
        return s == null ? null : LocalDateTime.parse(s.trim());
    }

    /** 逗号分隔字符串 → 清单；空串返回空清单（表示"清空"），null 表示"保持原值"。 */
    private static List<String> list(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static AgentRole role(Object o) {
        return o == null ? null : AgentRole.parse(String.valueOf(o));
    }

    private static AgentStatus status(Object o) {
        return o == null ? null : AgentStatus.parse(String.valueOf(o));
    }

    private static ModelTier tier(Object o) {
        return o == null ? null : ModelTier.parse(String.valueOf(o));
    }
}
