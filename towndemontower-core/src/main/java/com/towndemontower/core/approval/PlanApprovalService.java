package com.towndemontower.core.approval;

import com.towndemontower.core.audit.AuditStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [core] 执行计划审批服务（V1.13 / Plan-and-Execute）。
 *
 * <p><b>解决什么</b>：{@code PLAN_APPROVAL_REQUIRED} 这个错误码从 v1.0 起就存在，
 * 但<b>没有任何产生方</b>——也就是说"先出方案、人工确认后再动手"这句话在代码里
 * 一直只是文档。本类补上这条链：agent 提交计划 → 人审批 → 声明了
 * {@code requiresPlanApproval} 的工具才可以执行。</p>
 *
 * <p><b>三个刻意的取舍</b>：</p>
 * <ol>
 *   <li><b>禁自批</b>：审批人不能是提交计划的 agent（与 {@link ApprovalCenter} 一致）。
 *       否则"提交 + 自己批"两步就能绕过整条流程。</li>
 *   <li><b>按 (agent, task) 生效</b>：批的是"这个任务这份计划"，不是"这个人以后都行"。
 *       换任务要重新批——这是防止一次审批被无限复用的关键。</li>
 *   <li><b>否决不可补批</b>：REJECTED 之后再调 {@code approve} 直接抛错，必须重新提交。
 *       允许补批等于允许"先否再改口"，审批记录就失去意义。</li>
 * </ol>
 *
 * <p><b>V1.14 存储</b>：默认仍是内存（{@link InMemoryPlanApprovalStore}），
 * 接入方装配 {@link FilePlanApprovalStore} 后计划单跨重启回放；启动时装载全部快照，
 * 计划单号序号接着历史最大值继续，不会出现"重启后编号撞车"。
 * 回放失败（文件损坏）直接拒绝启动，不跳过损坏记录。</p>
 */
public class PlanApprovalService {

    private final Map<String, PlanApproval> byId = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final PlanApprovalStore store;
    /** 可选：计划提交/批准/否决都进同一条审计哈希链（null 时只做内存状态）。 */
    private final AuditStore audit;

    public PlanApprovalService() {
        this(new InMemoryPlanApprovalStore(), null);
    }

    /** v1.13 兼容构造：内存存储 + 审计。 */
    public PlanApprovalService(AuditStore audit) {
        this(new InMemoryPlanApprovalStore(), audit);
    }

    /** V1.14：指定存储，启动时回放。 */
    public PlanApprovalService(PlanApprovalStore store, AuditStore audit) {
        this.store = store == null ? new InMemoryPlanApprovalStore() : store;
        this.audit = audit;
        for (PlanApproval p : this.store.load()) {
            if (p == null || p.id == null) continue;
            byId.put(p.id, p);
            bumpSeq(p.id);
        }
    }

    /**
     * 提交计划，返回计划单号。
     *
     * <p>同一 (agent, task) 已有 <b>PENDING 或 APPROVED</b> 的单时<b>复用</b>那张单：
     * 避免重复调用把待办堆成一片，也避免"多提交几次就有一个被顺手批了"；
     * 已批准的计划再提交不应生成新单——否则"再提一次"就能把已批准的计划
     * 换成一条待审批记录（或让同样的任务出现两张互相矛盾的批准单）。</p>
     *
     * <p>只有最新单为 REJECTED 时才允许重新提交：否决必须显式走"修订版"流程，
     * 不能被一次重复调用悄悄覆盖。</p>
     *
     * @throws IllegalArgumentException agentId / taskId 为空
     */
    public synchronized String request(String agentId, String taskId, String summary) {
        return request(agentId, taskId, PlanArtifact.text(summary));
    }

    /**
     * V1.14：提交结构化计划。
     *
     * <p>步骤里点名的工具会收窄本次批准的范围；不传步骤（{@link PlanArtifact#text}）
     * 时行为与 v1.13 完全一致（任务级放行）。</p>
     *
     * @throws IllegalArgumentException agentId / taskId 为空
     */
    public synchronized String request(String agentId, String taskId, PlanArtifact plan) {
        requireText(agentId, "agentId");
        requireText(taskId, "taskId");
        PlanApproval latest = latest(agentId, taskId);
        if (latest != null
                && (latest.status == PlanStatus.PENDING || latest.status == PlanStatus.APPROVED)) {
            return latest.id;
        }
        return create(agentId, taskId, plan, "PLAN_REQUESTED");
    }

    /**
     * V1.14：修订已批准/已否决的计划，生成新的 PENDING 单并<b>重新走审批</b>。
     *
     * <p>为什么需要单独的方法：{@link #request} 对已批准任务是幂等的（防止重复提交
     * 把批准单顶掉），所以"批准后发现还要多用一个工具"无法靠 {@code request} 扩范围。
     * {@code revise} 是显式动作——旧单保留在存储里可追溯，新单成为 latest，状态回到
     * PENDING；在新单批准前，该任务的所有计划要求工具都会被拦（fail-closed）。</p>
     *
     * <p>最新单已经是 PENDING 时直接复用，不堆待办。</p>
     */
    public synchronized String revise(String agentId, String taskId, PlanArtifact plan) {
        requireText(agentId, "agentId");
        requireText(taskId, "taskId");
        PlanApproval latest = latest(agentId, taskId);
        if (latest != null && latest.status == PlanStatus.PENDING) {
            return latest.id;
        }
        return create(agentId, taskId, plan, "PLAN_REVISED");
    }

    private String create(String agentId, String taskId, PlanArtifact plan, String auditAction) {
        PlanArtifact artifact = plan == null ? PlanArtifact.text("") : plan;
        String id = "P" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        PlanApproval p = new PlanApproval(id, agentId, taskId, artifact, PlanStatus.PENDING,
                Instant.now(), null, null, null);
        byId.put(id, p);
        // 先落存储、再写审计：存储是审批事实来源，写不进去就不该对外宣告"已受理"。
        store.save(p);
        audit(auditAction, p, null);
        return id;
    }

    /**
     * 批准计划。
     *
     * @throws IllegalArgumentException 单号不存在 / 审批人为空 / 审批人就是提交者（自批）
     * @throws IllegalStateException    计划已被否决（不可补批，请重新提交）
     */
    public synchronized PlanApproval approve(String ticketId, String approver, String note) {
        PlanApproval p = mustGet(ticketId);
        requireText(approver, "approver");
        if (approver.equals(p.agentId)) {
            throw new IllegalArgumentException("不能自批：审批人不能是提交计划的 agent（" + approver + "）");
        }
        if (p.status == PlanStatus.REJECTED) {
            throw new IllegalStateException("计划 " + ticketId + " 已被否决，不能补批；请重新提交计划");
        }
        if (p.status == PlanStatus.APPROVED) {
            return p;
        }
        PlanApproval next = p.approved(approver, note, Instant.now());
        byId.put(ticketId, next);
        store.save(next);
        audit("PLAN_APPROVED", next, approver);
        return next;
    }

    /**
     * 否决计划。
     *
     * @throws IllegalArgumentException 单号不存在 / 审批人为空 / 自批
     */
    public synchronized PlanApproval reject(String ticketId, String approver, String note) {
        PlanApproval p = mustGet(ticketId);
        requireText(approver, "approver");
        if (approver.equals(p.agentId)) {
            throw new IllegalArgumentException("不能自否：审批人不能是提交计划的 agent（" + approver + "）");
        }
        if (p.status == PlanStatus.REJECTED) {
            return p;
        }
        PlanApproval next = p.rejected(approver, note, Instant.now());
        byId.put(ticketId, next);
        store.save(next);
        audit("PLAN_REJECTED", next, approver);
        return next;
    }

    /** 该 (agent, task) 的最新计划是否已批准——闸门用的就是这个判断。 */
    public boolean isApproved(String agentId, String taskId) {
        PlanApproval latest = latest(agentId, taskId);
        return latest != null && latest.isApproved();
    }

    /**
     * V1.14：最新计划已批准，并且覆盖该工具。
     *
     * <p>旧格式计划（没有工具范围）覆盖一切；结构化计划只放行点名的工具。</p>
     */
    public boolean isApprovedFor(String agentId, String taskId, String toolName) {
        PlanApproval latest = latest(agentId, taskId);
        return latest != null && latest.isApproved() && latest.coversTool(toolName);
    }

    /** 该 (agent, task) 的最新计划；没有提交过时为 {@code null}。 */
    public PlanApproval latest(String agentId, String taskId) {
        if (agentId == null || taskId == null) return null;
        PlanApproval best = null;
        for (PlanApproval p : byId.values()) {
            if (!agentId.equals(p.agentId) || !taskId.equals(p.taskId)) continue;
            if (best == null) {
                best = p;
                continue;
            }
            // 同一毫秒内提交两张单时，用序号决胜——否则 latest() 会随 Map 遍历顺序漂移，
            // "刚提交的修订版"可能被旧的 REJECTED 盖住。
            if (p.requestedAt.isAfter(best.requestedAt)
                    || (p.requestedAt.equals(best.requestedAt) && seqOf(p.id) > seqOf(best.id))) {
                best = p;
            }
        }
        return best;
    }

    private void bumpSeq(String id) {
        long n = seqOf(id);
        if (n > 0) seq.accumulateAndGet(n, Math::max);
    }

    /** 从 {@code P<millis>-<seq>} 取序号；格式不符返回 0。 */
    private static long seqOf(String id) {
        if (id == null) return 0;
        int dash = id.lastIndexOf('-');
        if (dash < 0 || dash == id.length() - 1) return 0;
        try {
            return Long.parseLong(id.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public PlanApproval get(String ticketId) {
        return ticketId == null ? null : byId.get(ticketId);
    }

    /** 全部计划（按提交时间升序），供看板与落盘使用。 */
    public List<PlanApproval> all() {
        List<PlanApproval> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing((PlanApproval p) -> p.requestedAt).thenComparing(p -> p.id));
        return List.copyOf(out);
    }

    /**
     * 计划生命周期写审计。<b>不写 summary</b>：计划摘要由调用方提供，可能含路径、
     * 服务名等敏感信息，而审批链只回答"谁在什么时候批了哪张单"。
     */
    private void audit(String action, PlanApproval p, String by) {
        if (audit == null) return;
        audit.appendAuto(action, "id=" + p.id + "|agent=" + p.agentId
                + "|task=" + p.taskId + "|status=" + p.status
                + "|by=" + (by == null ? "" : by));
    }

    private PlanApproval mustGet(String ticketId) {
        PlanApproval p = get(ticketId);
        if (p == null) {
            throw new IllegalArgumentException("计划单不存在：" + ticketId);
        }
        return p;
    }

    private static void requireText(String v, String field) {
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
