package com.towndemontower.spring;

import com.towndemontower.core.approval.ApprovalCenter;
import com.towndemontower.core.audit.AnchorReplica;
import com.towndemontower.core.audit.AuditCrypto;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.core.audit.HttpAnchorReplica;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.auth.ApiCallerRegistry;
import com.towndemontower.core.auth.CallerAgentBindings;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;
import com.towndemontower.core.gate.GateScriptRunner;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.List;

/**
 * [starter] 启动自检：把"默认配置是否真的安全"变成启动时的一次硬检查。
 *
 * <p>做的事只有两件，但都在启动期、不可跳过：</p>
 * <ol>
 *   <li><b>落地显式授权</b>：把 {@code towndemontower.permissions-bootstrap} 授予配置的 agentId。
 *       这是默认配置下唯一的正当授权入口（{@code autoGrant} 已默认关闭）；</li>
 *   <li><b>检查配置漂移</b>：CRITICAL 项在 {@code integrity-strict=true}（默认）时
 *       <b>直接拒绝启动</b>，而不是打条 WARN 就当没看见。</li>
 * </ol>
 *
 * <p>为什么必须拒绝启动：治理被静默降级的系统，比明确不可用的系统更危险——
 * 前者会让所有人以为"有审计、有拦截"，直到出事才发现闸门根本没接上。</p>
 */
public class GovernanceIntegrityChecker implements InitializingBean {

    public enum Severity { CRITICAL, WARN, INFO }

    public record Finding(String code, Severity severity, String detail) {
    }

    private final TownDemontowerProperties props;
    private final AuditStore auditStore;
    private final PermissionEngine permissionEngine;
    private final PermissionProvisioner permissionProvisioner;
    private final ApprovalCenter approvalCenter;
    private final List<Finding> findings = new ArrayList<>();

    public GovernanceIntegrityChecker(TownDemontowerProperties props,
                                      AuditStore auditStore,
                                      PermissionEngine permissionEngine,
                                      ApprovalCenter approvalCenter) {
        this(props, auditStore, permissionEngine, approvalCenter,
                PermissionProvisioner.of(permissionEngine));
    }

    /** V1.9：显式传入授权端口，bootstrap 授权因此也会留下审计记录。 */
    public GovernanceIntegrityChecker(TownDemontowerProperties props,
                                      AuditStore auditStore,
                                      PermissionEngine permissionEngine,
                                      ApprovalCenter approvalCenter,
                                      PermissionProvisioner permissionProvisioner) {
        this.props = props;
        this.auditStore = auditStore;
        this.permissionEngine = permissionEngine;
        this.approvalCenter = approvalCenter;
        this.permissionProvisioner = permissionProvisioner;
    }

    @Override
    public void afterPropertiesSet() {
        applyBootstrapGrants();
        findings.clear();
        findings.addAll(collect());

        boolean blocked = props.isIntegrityStrict() && !compliant();
        if (auditStore != null) {
            auditStore.appendAuto("GOVERNANCE_SELF_CHECK",
                    "compliant=" + compliant() + "|findings=" + codes()
                            + "|strict=" + props.isIntegrityStrict() + "|blocked=" + blocked);
        }
        if (blocked) {
            throw new IllegalStateException(
                    "治理配置未通过安全自检，拒绝启动（如需强制启动请显式设置 "
                            + "towndemontower.integrity-strict=false，并承担审计缺失风险）：" + criticalDetails());
        }
    }

    /** 显式授权入口：默认配置下一个权限都不给，除非这里声明。 */
    private void applyBootstrapGrants() {
        List<String> perms = props.getPermissionsBootstrap();
        if (perms == null || perms.isEmpty() || permissionEngine == null) return;
        permissionProvisioner.grant(props.getAgentId(), perms.toArray(new String[0]));
    }

    /** 当前发现项（启动后也可通过观测端点读取）。 */
    public List<Finding> findings() {
        return List.copyOf(findings);
    }

    /** 是否存在 CRITICAL 项。 */
    public boolean compliant() {
        return findings.stream().noneMatch(f -> f.severity() == Severity.CRITICAL);
    }

    public List<String> codes() {
        List<String> out = new ArrayList<>();
        for (Finding f : findings) out.add(f.code());
        return out;
    }

    private List<Finding> collect() {
        List<Finding> out = new ArrayList<>();

        // ① 关闭治理却允许放行 —— 等于把闸门整个拆掉
        boolean allowWhenDisabled = "allow".equalsIgnoreCase(props.getDisabledBehavior());
        if (!props.isEnabled() && allowWhenDisabled) {
            out.add(new Finding("DISABLED_ALLOW", Severity.CRITICAL,
                    "enabled=false 且 disabled-behavior=allow：治理关闭后所有调用都会放行"));
        }
        if (!props.isEnabled() && !allowWhenDisabled) {
            out.add(new Finding("GOVERNANCE_DISABLED", Severity.WARN,
                    "治理已熔断：调用将被全部拒绝（fail-closed）"));
            // ①-2 过渡宽限：不拦第一次，但要让人看见"第一次被放过了"
            if (props.isLegacyGrace()) {
                out.add(new Finding("LEGACY_GRACE_ARMED", Severity.WARN,
                        "enabled=false 且 legacy-grace=true：进程内首次调用会先告警留痕再放行一次"
                                + "（审计 LEGACY_GOVERNANCE_GRACE），第二次起才熔断；"
                                + "如需立即硬拒绝请设 towndemontower.legacy-grace=false"));
            }
        }

        // ② 审计只有内存副本 —— 重启即无证据
        if (!"file".equalsIgnoreCase(props.getAuditStorage())) {
            out.add(new Finding("AUDIT_VOLATILE", Severity.CRITICAL,
                    "audit-storage 非 file：审计重启即丢，无法事后追溯"));
        }
        // ③ 审计链在启动时就断了
        if (auditStore != null && !auditStore.verifyChain()) {
            out.add(new Finding("AUDIT_CHAIN_BROKEN", Severity.CRITICAL,
                    "审计哈希链校验失败，日志可能被改写"));
        }

        // ④ 审批单不落盘 —— 重启后无法证明"谁批过"
        if (props.getApprovalDir() == null || props.getApprovalDir().isBlank()) {
            out.add(new Finding("APPROVAL_NOT_PERSISTED", Severity.CRITICAL,
                    "approval-dir 为空：审批单仅存内存，重启后丢失"));
        }
        if (approvalCenter != null && approvalCenter.ttl().toMinutes() > 24 * 60) {
            out.add(new Finding("APPROVAL_TTL_TOO_LONG", Severity.WARN,
                    "审批单有效期超过 24h，等同于长期挂起的放行许可"));
        }

        // ⑤ 观测端点裸奔
        if (props.isMetricsEndpoint() && (props.getMetricsToken() == null || props.getMetricsToken().isBlank())) {
            out.add(new Finding("ENDPOINT_WITHOUT_TOKEN", Severity.CRITICAL,
                    "metrics-endpoint=true 但未配置 metrics-token：遥测数据对任何人可读"));
        }

        // ⑥ 注解自授权（默认已关，显式打开仍可用但必须留痕）
        if (props.isAutoGrantDeclaredPerms()) {
            out.add(new Finding("AUTO_GRANT_ENABLED", Severity.WARN,
                    "auto-grant-declared-perms=true：写注解即自我授权，绕开注册表"));
        }

        // ⑦ 门禁密钥：V1.7 起只认环境变量。缺失/不合格不会回退到任何默认值，
        //    而是在真正调用门禁时以 GATE_KEY_UNAVAILABLE 拒绝（fail-closed）。
        //    启动期只报 WARN 而非 CRITICAL：门禁脚本默认并未装配，若因此拒绝启动，
        //    会让"加个依赖就能跑"的接入体验直接断裂——真正的强制点在 run() 里。
        GateScriptRunner.KeyStatus gateKey = GateScriptRunner.envStatus();
        if (gateKey == GateScriptRunner.KeyStatus.MISSING) {
            out.add(new Finding("GATE_KEY_MISSING", Severity.WARN,
                    "环境变量 TOWNDEMONTOWER_GATE_KEY 未设置：门禁脚本无法签名/验签，"
                            + "调用门禁将以 GATE_KEY_UNAVAILABLE 直接拒绝"));
        } else if (gateKey == GateScriptRunner.KeyStatus.WEAK) {
            out.add(new Finding("GATE_KEY_WEAK", Severity.CRITICAL,
                    "TOWNDEMONTOWER_GATE_KEY 已设置但不合格（<" + GateScriptRunner.MIN_KEY_LENGTH
                            + " 字符 / 占位值 / 字符种类过少）：等同未配置，门禁将拒绝执行"));
        }

        // ⑧ 身份仍是默认值
        if ("agent-default".equals(props.getAgentId())) {
            out.add(new Finding("AGENT_ID_DEFAULT", Severity.WARN,
                    "agentId 仍为默认值：所有调用共享同一身份，审计无法区分主体"));
        }

        // ⑨ 状态仅内存
        if (!"file".equalsIgnoreCase(props.getStateStorage())) {
            out.add(new Finding("STATE_VOLATILE", Severity.WARN,
                    "state-storage 非 file：模式与裁决不落盘，重启后不可追溯"));
        }

        // ⑩ 输入校验被关掉
        if (!props.isRejectControlChars() || !props.isRejectPathTraversal()) {
            out.add(new Finding("INPUT_VALIDATION_RELAXED", Severity.WARN,
                    "输入校验被关闭（控制字符/路径穿越），下游需自行防护"));
        }

        // ⑪ 审计链只有 SHA-256：能查"被改过"，查不出"整链重算"
        if (auditStore instanceof HashChainAuditStore chain && !chain.keyAvailable()) {
            out.add(new Finding("AUDIT_KEY_MISSING", Severity.WARN,
                    "环境变量 " + AuditCrypto.KEY_ENV + " 未设置：审计链使用 SHA-256，"
                            + "可发现单点篡改，但挡不住整链重算；配置后自动切换 HMAC-SHA256"));
        }

        // ⑫ 锚点批量化（V1.9.2 / I4-b）：开了就要在启动日志里说清代价
        if (props.getAuditAnchorBatch() > 1) {
            out.add(new Finding("AUDIT_ANCHOR_BATCHED", Severity.INFO,
                    "audit-anchor-batch=" + props.getAuditAnchorBatch()
                            + "：锚点最多落后 " + (props.getAuditAnchorBatch() - 1) + " 条，"
                            + "这段区间内被整段删除的尾部审计链内仍自洽、外锚发现不了；"
                            + "需要最强证据强度请保持默认 1（每条追平）"));
        }

        // ⑬ 锚点副本（V1.10 / I4-c）：本地锚点只能证明"这套文件自洽"，
        //    整个目录回滚到旧快照时它一样自洽——真正的外部证词只有副本能给。
        if (auditStore instanceof FileAuditStore file) {
            List<AnchorReplica> replicas = file.replicas();
            if (replicas.isEmpty()) {
                out.add(new Finding("ANCHOR_SINGLE_COPY", Severity.INFO,
                        "未配置 audit-anchor-replicas：锚点与审计日志同盘，"
                                + "把整个目录回滚到旧快照后日志与锚点彼此自洽、本地校验全部通过；"
                                + "要挡住这种回滚，必须有一份本机之外的锚点"));
            } else {
                long offHost = replicas.stream().filter(AnchorReplica::offHost).count();
                if (offHost == 0) {
                    out.add(new Finding("ANCHOR_REPLICA_NOT_OFFHOST", Severity.WARN,
                            "锚点副本都不在本机之外（" + replicas.stream()
                                    .map(AnchorReplica::name).toList() + "）：只做到权限域隔离，"
                                    + "拿到宿主机权限的人仍可把日志与副本一起回滚；"
                                    + "跨机请用 http(s) 收集端"));
                }
                if (!props.isAuditAnchorReplicaRequired()) {
                    out.add(new Finding("ANCHOR_REPLICA_BEST_EFFORT", Severity.WARN,
                            "audit-anchor-replica-required=false：副本不可读/不可写时不阻断，"
                                    + "异地证据可能长期缺失而监控上无人能发现——"
                                    + "建议只在副本所属系统仍在建设期时临时关闭"));
                }
                java.util.Map<String, String> replicaErrors = file.replicaErrors();
                if (!replicaErrors.isEmpty()) {
                    out.add(new Finding("ANCHOR_REPLICA_DEGRADED", Severity.WARN,
                            "锚点副本当前处于失败状态：" + replicaErrors
                                    + "；累计失败 " + file.replicaFailures() + " 次"));
                }
                if (replicas.stream().anyMatch(r -> r instanceof HttpAnchorReplica)
                        && HttpAnchorReplica.envToken() == null) {
                    out.add(new Finding("ANCHOR_TOKEN_MISSING", Severity.WARN,
                            "存在 http(s) 锚点副本但环境变量 " + HttpAnchorReplica.TOKEN_ENV
                                    + " 未设置：收集端若要求鉴别，推送会因鉴权失败而中断"));
                }
            }
        }

        // ⑭ 凭据 → Agent 绑定表（V1.9 / I3-b）：只检查"配了但配错"的形态。
        //    与 ⑦ 同样的取材方式——直接读环境变量，不依赖某个模块是否装配了过滤器。
        //    这里<b>不</b>判断"有没有配"：绑定是否必需取决于模块的 auth-mode，而 starter
        //    不拥有那份配置。空表告警因此放在各模块启动日志里，避免在这里误报。
        CallerAgentBindings bindings = CallerAgentBindings.fromEnv();
        if (bindings.status() == CallerAgentBindings.Status.PARTIAL
                || bindings.status() == CallerAgentBindings.Status.WEAK) {
            out.add(new Finding("CALLER_BINDING_PARTIAL", Severity.WARN,
                    CallerAgentBindings.ENV + " 存在被拒条目（" + bindings.rejected()
                            + "）：这些调用者将没有绑定，严格模式下会被 CALLER_NOT_PROVISIONED 拒绝"));
        }
        if (bindings.strict()) {
            List<String> orphan = bindings.callersWithoutToken(ApiCallerRegistry.fromEnv());
            if (!orphan.isEmpty()) {
                out.add(new Finding("CALLER_BINDING_ORPHAN", Severity.WARN,
                        "绑定表里的调用者没有对应令牌：" + orphan
                                + "（认证时不会出现该名字，绑定永不生效）"));
            }
        }
        return out;
    }

    private String criticalDetails() {
        StringBuilder sb = new StringBuilder();
        for (Finding f : findings) {
            if (f.severity() == Severity.CRITICAL) {
                sb.append('[').append(f.code()).append("] ").append(f.detail()).append("; ");
            }
        }
        return sb.toString();
    }
}
