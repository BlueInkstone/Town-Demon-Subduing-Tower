package com.towndemontower.core.executor;
import java.util.ArrayList;

import com.towndemontower.core.audit.AuditStore;

import java.util.Collection;
import java.util.List;

/**
 * [core] 权限写入端口（V1.9 / I3）。
 *
 * <p>{@link PermissionEngine} 自本版起只读：{@code grant} / {@code revoke} /
 * {@code revokeAll} 都是包内可见。要改授权，只能拿这个端口——区别不在"能不能"，
 * 而在<b>行为是否被显式声明并留痕</b>：</p>
 *
 * <ol>
 *   <li>持有 {@code PermissionEngine} Bean 的普通业务代码，写授权<b>编译不过</b>；</li>
 *   <li>取端口是一个明确动作（{@code PermissionProvisioner.of(engine, audit, "wiring")}），
 *       要写清 actor，便于在审计里对账；</li>
 *   <li>每次授予 / 回收都写 {@code PERMISSION_GRANTED} / {@code PERMISSION_REVOKED} /
 *       {@code PERMISSION_REVOKED_ALL}，含 actor、主体与权限清单。</li>
 * </ol>
 *
 * <p><b>这不是内核强制</b>：反射、同包类、替换 Bean 仍可绕过（见 docs/17）。
 * 它消除的是"默认路径"与"顺手提权"，不是全部绕过面。</p>
 */
public interface PermissionProvisioner {

    /** 追加授予（不回收既有权限）。 */
    void grant(String agentId, String... perms);

    /** 批量追加授予。 */
    void grant(String agentId, Collection<String> perms);

    /**
     * 精确回收。
     *
     * @return 实际移除的权限数量
     */
    int revoke(String agentId, String... perms);

    /**
     * 整员回收。
     *
     * @return 实际移除的权限数量
     */
    int revokeAll(String agentId);

    /** 覆盖式授予：先整员回收，再按清单授予（改档 / 换角色时的正确语义）。 */
    void replace(String agentId, Collection<String> perms);

    /** 审计留痕用的 actor；{@code null} 表示未指定（会在审计里记为 {@code -}）。 */
    String actor();

    /** 不带审计的端口（自测、离线脚本）；生产装配请用 {@link #of(PermissionEngine, AuditStore, String)}。 */
    static PermissionProvisioner of(PermissionEngine engine) {
        return new EnginePermissionProvisioner(engine, null, null);
    }

    /** 带审计的端口：每次授权变更都落 {@code AuditStore}。 */
    static PermissionProvisioner of(PermissionEngine engine, AuditStore audit, String actor) {
        return new EnginePermissionProvisioner(engine, audit, actor);
    }

    /** 只读能力，便于把"看"和"改"分给不同对象（例如只给看板读端口）。 */
    static List<String> describe(PermissionEngine engine, String agentId) {
        return new ArrayList<>(engine.permissionsOf(agentId));
    }
}
