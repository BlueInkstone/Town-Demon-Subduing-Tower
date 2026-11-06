package com.towndemontower.agents.domain;

import java.util.List;
import java.util.Set;

/**
 * [agents] 权限开通 / 回收端口。
 *
 * <p>Agent 档案是 RBAC 的<b>权威来源</b>，但真正做鉴权判定的是 core 的
 * {@code PermissionEngine}。领域层不直接依赖它，而是通过本端口表达意图：
 * 注册 → {@link #grant}；停用 / 注销 → {@link #revokeAll}。</p>
 *
 * <p>把这层做成接口而不是直接调 core，有两个实际好处：
 * <ol>
 *   <li>注册表可以脱离治理内核做单元测试（用内存桩记录调用）；</li>
 *   <li>接企业真实 IAM（LDAP / 飞书 / 企业微信）时，只需换一个实现。</li>
 * </ol>
 */
public interface AccessProvisioner {

    /** 给 Agent 授予权限（覆盖式：先回收再授予，避免残留历史权限）。 */
    void grant(String agentId, List<String> permissions);

    /** 回收 Agent 的全部权限。 */
    void revokeAll(String agentId);

    /** 查询 Agent 当前实际生效的权限（用于"账实是否一致"的对账）。 */
    Set<String> currentPermissions(String agentId);
}
