package com.towndemontower.agents.service;

import com.towndemontower.agents.domain.Agent;
import com.towndemontower.agents.domain.AgentRepository;
import com.towndemontower.agents.domain.AgentRole;
import com.towndemontower.agents.domain.AgentStatus;
import com.towndemontower.agents.domain.AgentSummary;
import com.towndemontower.agents.domain.AgentSummaryCalculator;
import com.towndemontower.agents.domain.ModelTier;
import com.towndemontower.agents.domain.RoleTemplate;
import com.towndemontower.agents.domain.SkillCatalog;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * [agents] Agent 注册表服务（纯 Java，零 Spring 依赖）。
 *
 * <p>职责：注册（register）、改档（update）、状态流转（changeStatus）、
 * 注销（delete）、按状态/角色/关键字筛选、以及汇总。字段校验都在这一层完成，
 * 保证任何入口（REST / SDK / 定时任务）拿到的数据都是合法的。</p>
 *
 * <p><b>本类不碰 RBAC</b>：权限的实际授予与回收由 {@code AgentGovernanceFacade}
 * 在调用成功后通过 {@code AccessProvisioner} 完成。这样领域逻辑可以脱离治理内核
 * 独立测试，也避免"服务层写库成功但授权失败"时职责混淆——授权失败应该让整个
 * 调用可见地失败，而不是被埋在日常的 CRUD 里。</p>
 */
public class AgentService {

    /** 权限格式：{@code 域:动作}，如 {@code delivery:write}、{@code ops:deploy}。 */
    private static final Pattern PERMISSION_PATTERN =
            Pattern.compile("^[a-z][a-z0-9-]*:[a-z][a-z0-9*-]*$");

    private static final int NAME_MAX = 32;
    private static final int NOTE_MAX = 120;

    private final AgentRepository repository;
    private final SkillCatalog skillCatalog;
    private final Supplier<Instant> clock;

    public AgentService(AgentRepository repository, SkillCatalog skillCatalog) {
        this(repository, skillCatalog, Instant::now);
    }

    public AgentService(AgentRepository repository,
                        SkillCatalog skillCatalog,
                        Supplier<Instant> clock) {
        if (repository == null) throw new IllegalArgumentException("repository must not be null");
        this.repository = repository;
        this.skillCatalog = skillCatalog == null ? SkillCatalog.unlimited() : skillCatalog;
        this.clock = clock == null ? Instant::now : clock;
    }

    /** 当前时刻（可注入固定时钟）。 */
    public Instant now() {
        return clock.get();
    }

    /**
     * 注册一个 Agent。
     *
     * @param name         显示名（必填，注册表内唯一）
     * @param role         角色（必填）
     * @param skills       技能清单；<b>null 表示套用角色模板</b>，空列表表示不配技能
     * @param permissions  权限清单；null 表示套用角色模板，空列表表示暂不授权
     * @param modelTier    模型档位；null 表示套用角色模板
     * @param registeredAt 注册时间；null 表示当前时刻
     * @param note         备注（可空）
     */
    public Agent register(String name,
                          AgentRole role,
                          List<String> skills,
                          List<String> permissions,
                          ModelTier modelTier,
                          LocalDateTime registeredAt,
                          String note) {
        String normalizedName = validateName(name, null);
        if (role == null) {
            throw new AgentValidationException("role", "角色不能为空");
        }

        List<String> finalSkills = skills == null ? RoleTemplate.defaultSkills(role) : skills;
        List<String> finalPerms = permissions == null
                ? RoleTemplate.defaultPermissions(role) : permissions;
        ModelTier finalTier = modelTier == null ? RoleTemplate.defaultModelTier(role) : modelTier;

        validateSkills(finalSkills);
        validatePermissions(finalPerms);
        String finalNote = validateNote(note);

        Agent agent = Agent.of(normalizedName, role, finalSkills, finalPerms, finalTier,
                registeredAt == null ? LocalDateTime.ofInstant(clock.get(), ZoneId.systemDefault()) : registeredAt,
                finalNote);
        return repository.save(agent);
    }

    /**
     * 改档（部分更新：null 表示保持原值，空列表表示清空该清单）。
     *
     * @throws AgentNotFoundException   Agent 不存在
     * @throws AgentValidationException 字段非法（含改名重名）
     */
    public Agent update(String id,
                        String name,
                        AgentRole role,
                        List<String> skills,
                        List<String> permissions,
                        ModelTier modelTier,
                        LocalDateTime registeredAt,
                        String note) {
        Agent existing = repository.findById(id)
                .orElseThrow(() -> new AgentNotFoundException(id));

        String normalizedName = name == null ? null : validateName(name, existing.id());
        validateSkills(skills);
        validatePermissions(permissions);
        String finalNote = note == null ? null : validateNote(note);

        Agent merged = existing.withProfile(normalizedName, role, skills, permissions,
                modelTier, registeredAt, finalNote);
        return repository.save(merged);
    }

    /**
     * 状态流转：ACTIVE ⇄ PAUSED ⇄ SUSPENDED → RETIRED。
     *
     * @throws AgentValidationException 试图让已注销的 Agent 恢复（注销为终态）
     */
    public Agent changeStatus(String id, AgentStatus target) {
        Agent existing = repository.findById(id)
                .orElseThrow(() -> new AgentNotFoundException(id));
        if (target == null) {
            throw new AgentValidationException("status", "状态不能为空");
        }
        if (existing.status() == target) {
            return existing;
        }
        if (existing.status().isFinal()) {
            throw new AgentValidationException("status",
                    "该 Agent 已注销，ID 已封存，不能变更状态；如需重新启用请分配新 ID");
        }
        return repository.save(existing.withStatus(target));
    }

    /**
     * 注销并删除档案。
     *
     * <p><b>为什么必须先 RETIRED 才能删除</b>：直接删掉一个可用状态的 Agent，
     * 等于把"它曾持有哪些权限、执行过什么"的线索一并抹掉。先置为 RETIRED
     * （回收权限、留存档案）再删除，审计链才能对得上。</p>
     */
    public void delete(String id) {
        Agent existing = repository.findById(id)
                .orElseThrow(() -> new AgentNotFoundException(id));
        if (existing.status() != AgentStatus.RETIRED) {
            throw new AgentValidationException("status",
                    "请先将该 Agent 置为 RETIRED 再删除档案，以保留审计线索");
        }
        if (!repository.deleteById(id)) {
            throw new AgentNotFoundException(id);
        }
    }

    /** 按 ID 查询。 */
    public Optional<Agent> findById(String id) {
        return repository.findById(id);
    }

    /**
     * 列出 Agent。
     *
     * @param status  状态过滤；null 表示不过滤
     * @param role    角色过滤；null 表示不过滤
     * @param keyword 关键字（匹配名称 / ID / 技能）；null 或空表示不过滤
     */
    public List<Agent> list(AgentStatus status, AgentRole role, String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<Agent> result = new ArrayList<>();
        for (Agent a : repository.findAll()) {
            if (status != null && a.status() != status) continue;
            if (role != null && a.role() != role) continue;
            if (!kw.isEmpty() && !matches(a, kw)) continue;
            result.add(a);
        }
        return result;
    }

    /** 汇总。 */
    public AgentSummary summary() {
        return AgentSummaryCalculator.calculate(repository.findAll(), now());
    }

    /** 执行前守卫：非 ACTIVE 的 Agent 不允许发起任务。 */
    public Agent assertCanAct(String agentId) {
        Agent agent = repository.findById(agentId)
                .orElseThrow(() -> new AgentNotFoundException(agentId));
        if (!agent.canAct()) {
            throw new AgentInactiveException(agentId, agent.status());
        }
        return agent;
    }

    public int count() {
        return repository.count();
    }

    /* ---------------- 校验 ---------------- */

    private String validateName(String name, String selfId) {
        if (name == null || name.trim().isEmpty()) {
            throw new AgentValidationException("name", "名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > NAME_MAX) {
            throw new AgentValidationException("name", "名称不能超过 " + NAME_MAX + " 个字符");
        }
        repository.findByName(trimmed).ifPresent(found -> {
            if (selfId == null || !found.id().equals(selfId)) {
                throw new AgentValidationException("name",
                        "注册表中已存在同名 Agent：" + found.id() + "（" + found.name() + "）");
            }
        });
        return trimmed;
    }

    private void validateSkills(List<String> skills) {
        if (skills == null || skills.isEmpty()) return;
        List<String> unknown = skillCatalog.unknown(skills);
        if (!unknown.isEmpty()) {
            throw new AgentValidationException("skills",
                    "以下技能不在系统工具注册表中：" + String.join("、", unknown));
        }
    }

    private void validatePermissions(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) return;
        for (String p : permissions) {
            String t = p == null ? "" : p.trim();
            if (!PERMISSION_PATTERN.matcher(t).matches()) {
                throw new AgentValidationException("permissions",
                        "权限格式应为「域:动作」（如 delivery:write），非法值：" + p);
            }
        }
    }

    private String validateNote(String note) {
        if (note == null) return null;
        String t = note.trim();
        if (t.length() > NOTE_MAX) {
            throw new AgentValidationException("note", "备注不能超过 " + NOTE_MAX + " 个字符");
        }
        return t.isEmpty() ? null : t;
    }

    private static boolean matches(Agent a, String kw) {
        if (a.name() != null && a.name().toLowerCase(Locale.ROOT).contains(kw)) return true;
        if (a.id() != null && a.id().toLowerCase(Locale.ROOT).contains(kw)) return true;
        if (a.role().label().contains(kw) || a.role().code().toLowerCase(Locale.ROOT).contains(kw)) {
            return true;
        }
        for (String s : a.skills()) {
            if (s.toLowerCase(Locale.ROOT).contains(kw)) return true;
        }
        return false;
    }
}
