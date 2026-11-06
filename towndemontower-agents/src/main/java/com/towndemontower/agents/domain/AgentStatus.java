package com.towndemontower.agents.domain;

/**
 * [agents] Agent 状态。
 *
 * <p>状态直接决定两个技术后果，而不只是标签：
 * <ol>
 *   <li><b>能否执行</b>（{@link #canAct()}）——只有 ACTIVE 允许发起受治理的工具调用；</li>
 *   <li><b>是否保留权限</b>（{@link #retainsPermissions()}）——SUSPENDED / RETIRED 必须回收 RBAC 权限，
 *       否则停用的 Agent 仍能通过鉴权，最小权限原则形同虚设。</li>
 * </ol>
 *
 * <p>流转：{@code ACTIVE ⇄ PAUSED ⇄ SUSPENDED → RETIRED}。
 * RETIRED 是终态——ID 一旦注销不再复用，重新启用请分配新 ID（保留历史审计归属）。</p>
 */
public enum AgentStatus {

    /** 可用：允许执行，持有权限。 */
    ACTIVE("可用", true, true),

    /** 暂停：不允许执行，但保留权限（恢复时无需重新授权）。 */
    PAUSED("暂停", false, true),

    /** 停用：不允许执行，且立即回收权限（如异常排查期间）。 */
    SUSPENDED("停用", false, false),

    /** 已注销：终态，权限永久回收，ID 封存。 */
    RETIRED("已注销", false, false);

    private final String label;
    private final boolean canAct;
    private final boolean retainsPermissions;

    AgentStatus(String label, boolean canAct, boolean retainsPermissions) {
        this.label = label;
        this.canAct = canAct;
        this.retainsPermissions = retainsPermissions;
    }

    /** 中文标签。 */
    public String label() {
        return label;
    }

    /** 是否允许发起受治理的执行。 */
    public boolean canAct() {
        return canAct;
    }

    /** 是否保留已授予的权限（为 false 时必须回收）。 */
    public boolean retainsPermissions() {
        return retainsPermissions;
    }

    /** 是否为终态（注销不可逆）。 */
    public boolean isFinal() {
        return this == RETIRED;
    }

    /** 是否为未注销状态。 */
    public boolean isRegistered() {
        return this != RETIRED;
    }

    /** 宽松解析：枚举名或中文标签。解析失败返回 null。 */
    public static AgentStatus parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        for (AgentStatus st : values()) {
            if (st.name().equalsIgnoreCase(s) || st.label.equals(s)) return st;
        }
        return null;
    }
}
