package com.towndemontower.core.executor;

import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.SkillDescriptor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [core] 数字镜像 RBAC（effectivePerms = ownerPerms ∩ agentPerms）。
 * 接口在 core；缓存与事件订阅实现归 starter。此处用内存 granted 集合演示。
 *
 * <p><b>V1.9 / I3：本类只读。</b>{@link #grant} / {@link #revoke} / {@link #revokeAll}
 * 已收为<b>包内可见</b>——持有 {@code PermissionEngine} Bean 的业务代码再也无法直接自我授权
 * （旧写法在编译期就失败，不再是"运行期没人管"）。需要改授权时，必须显式取
 * {@link PermissionProvisioner} 端口，并因此留下审计记录。</p>
 *
 * <p><b>授予与回收必须成对出现</b>：只授不撤的 RBAC 在"离职 / 停用"场景下会留下
 * 永久有效的权限，等于把最小权限原则架空。因此端口同时提供精确回收与整员回收。</p>
 *
 * <p><b>边界</b>：这是进程内的编译期收口，不是内核强制。反射仍可绕过
 * （见 {@code docs/17-强制力与绕过面分析.md}）；它挡住的是"顺手调一下"的误用与
 * "拿到 Bean 就能提权"的默认路径，挡不住蓄意越权。</p>
 */
public class PermissionEngine {
    private final Map<String, Set<String>> agentPerms = new ConcurrentHashMap<>();

    /** 包内可见：只能经 {@link PermissionProvisioner} 调用。 */
    void grant(String agentId, String... perms) {
        if (agentId == null || perms == null || perms.length == 0) return;
        Set<String> s = agentPerms.computeIfAbsent(agentId, k -> new HashSet<>());
        synchronized (s) {
            Collections.addAll(s, perms);
        }
    }

    /**
     * 精确回收若干权限（不存在的权限静默忽略）。
     *
     * @return 实际被移除的权限数量
     */
    int revoke(String agentId, String... perms) {
        if (agentId == null || perms == null || perms.length == 0) return 0;
        Set<String> s = agentPerms.get(agentId);
        if (s == null) return 0;
        int removed = 0;
        synchronized (s) {
            for (String p : perms) {
                if (p != null && s.remove(p)) removed++;
            }
            if (s.isEmpty()) agentPerms.remove(agentId);
        }
        return removed;
    }

    /**
     * 回收该主体的<b>全部</b>权限（离职 / 停用时调用）。
     *
     * @return 被移除的权限数量
     */
    int revokeAll(String agentId) {
        if (agentId == null) return 0;
        Set<String> removed = agentPerms.remove(agentId);
        return removed == null ? 0 : removed.size();
    }

    /** 查询某主体当前持有的权限快照（只读副本，按字典序排列，便于稳定输出）。 */
    public Set<String> permissionsOf(String agentId) {
        Set<String> granted = agentId == null ? null : agentPerms.get(agentId);
        if (granted == null) return Collections.emptySet();
        synchronized (granted) {
            return Collections.unmodifiableSet(new TreeSet<>(granted));
        }
    }

    /** 是否持有指定权限。 */
    public boolean isGranted(String agentId, String perm) {
        return permissionsOf(agentId).contains(perm);
    }

    public void assertAllowed(String agentId, String[] requiredPerms) {
        if (requiredPerms == null || requiredPerms.length == 0) return;
        Set<String> granted = permissionsOf(agentId);
        for (String p : requiredPerms) {
            if (!granted.contains(p)) {
                throw new PermissionDeniedException("agent " + agentId + " lacks perm " + p);
            }
        }
    }

    public void assertAllowedByAgent(String agentId, SkillDescriptor d) {
        assertAllowed(agentId, d.requiredPerms);
    }
}
