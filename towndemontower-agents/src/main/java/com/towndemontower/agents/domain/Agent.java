package com.towndemontower.agents.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * [agents] Agent 档案（不可变值对象）。
 *
 * <p>字段：
 * <ul>
 *   <li>{@code id} —— Agent 标识（{@code AGT-0001}），也是治理链里的 {@code agentId}；</li>
 *   <li>{@code name} —— 显示名，注册表内唯一；</li>
 *   <li>{@code role} —— 角色（PM / ARCHITECT / DEVELOPER / QA / OPS）；</li>
 *   <li>{@code status} —— 状态，决定能否执行与是否保留权限；</li>
 *   <li>{@code skills} —— 可调用的工具（core {@code SkillRegistry} 的 key）；</li>
 *   <li>{@code permissions} —— 持有的 RBAC 权限；</li>
 *   <li>{@code modelTier} —— 使用的模型档位；</li>
 *   <li>{@code registeredAt} —— 注册时间；</li>
 *   <li>{@code note} —— 备注；</li>
 *   <li>{@code createdAt} / {@code updatedAt} —— 创建与最后变更时刻（审计用）。</li>
 * </ul>
 *
 * <p><b>ID 即身份</b>：{@code id} 会原样进入 {@code ExecutionContext.agentId}，
 * 被 RBAC 用来判定"这个主体能不能执行该工具"。因此本类不允许修改 ID——
 * {@link #withProfile} 用于改档，换身份请重新注册。</p>
 */
public final class Agent {

    private final String id;
    private final String name;
    private final AgentRole role;
    private final AgentStatus status;
    private final List<String> skills;
    private final List<String> permissions;
    private final ModelTier modelTier;
    private final LocalDateTime registeredAt;
    private final String note;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Agent(String id,
                 String name,
                 AgentRole role,
                 AgentStatus status,
                 List<String> skills,
                 List<String> permissions,
                 ModelTier modelTier,
                 LocalDateTime registeredAt,
                 String note,
                 Instant createdAt,
                 Instant updatedAt) {
        if (role == null) throw new IllegalArgumentException("role must not be null");
        if (status == null) throw new IllegalArgumentException("status must not be null");
        this.id = id;
        this.name = name;
        this.role = role;
        this.status = status;
        this.skills = normalize(skills);
        this.permissions = normalize(permissions);
        this.modelTier = modelTier == null ? RoleTemplate.defaultModelTier(role) : modelTier;
        this.registeredAt = registeredAt;
        this.note = note;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    /** 新建 Agent（无 ID、ACTIVE、创建时刻为当前）。 */
    public static Agent of(String name,
                           AgentRole role,
                           List<String> skills,
                           List<String> permissions,
                           ModelTier modelTier,
                           LocalDateTime registeredAt,
                           String note) {
        Instant now = Instant.now();
        return new Agent(null, name, role, AgentStatus.ACTIVE, skills, permissions,
                modelTier, registeredAt, note, now, now);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public AgentRole role() {
        return role;
    }

    public AgentStatus status() {
        return status;
    }

    /** 技能清单（只读、已去重保序）。 */
    public List<String> skills() {
        return skills;
    }

    /** 权限清单（只读、已去重保序）。 */
    public List<String> permissions() {
        return permissions;
    }

    public ModelTier modelTier() {
        return modelTier;
    }

    public LocalDateTime registeredAt() {
        return registeredAt;
    }

    public String note() {
        return note;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** 当前是否允许发起受治理的执行。 */
    public boolean canAct() {
        return status.canAct();
    }

    /** 当前是否应保留 RBAC 权限（SUSPENDED / RETIRED 为 false）。 */
    public boolean retainsPermissions() {
        return status.retainsPermissions();
    }

    public int skillCount() {
        return skills.size();
    }

    public int permissionCount() {
        return permissions.size();
    }

    /** 是否具备某个工具的使用权。 */
    public boolean hasSkill(String skill) {
        return skill != null && skills.contains(skill);
    }

    /**
     * 改档：返回替换档案字段后的新实例。null 表示"保持原值"，
     * 传空列表表示"清空该清单"。
     */
    public Agent withProfile(String name,
                             AgentRole role,
                             List<String> skills,
                             List<String> permissions,
                             ModelTier modelTier,
                             LocalDateTime registeredAt,
                             String note) {
        return new Agent(
                id,
                name != null ? name : this.name,
                role != null ? role : this.role,
                status,
                skills != null ? skills : this.skills,
                permissions != null ? permissions : this.permissions,
                modelTier != null ? modelTier : this.modelTier,
                registeredAt != null ? registeredAt : this.registeredAt,
                note != null ? note : this.note,
                createdAt,
                Instant.now());
    }

    /** 变更状态。 */
    public Agent withStatus(AgentStatus newStatus) {
        return new Agent(id, name, role, newStatus, skills, permissions,
                modelTier, registeredAt, note, createdAt, Instant.now());
    }

    /** 返回带指定 ID 的新实例（供仓储落库时补齐 ID）。 */
    public Agent withId(String newId) {
        return new Agent(newId, name, role, status, skills, permissions,
                modelTier, registeredAt, note, createdAt, updatedAt);
    }

    /** 去空白、去重并保持原有顺序；null 视为空清单。 */
    private static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return List.copyOf(new ArrayList<>(set));
    }

    @Override
    public String toString() {
        return "Agent{id=" + id + ", name=" + name + ", role=" + role
                + ", status=" + status + ", skills=" + skills.size()
                + ", perms=" + permissions.size() + "}";
    }
}
