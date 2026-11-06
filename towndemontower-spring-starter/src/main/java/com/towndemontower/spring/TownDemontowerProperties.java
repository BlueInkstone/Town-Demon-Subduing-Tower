package com.towndemontower.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * [starter] 配置属性（前缀 {@code towndemontower}）。
 *
 * <p>仅承载适配层自身的开关与上下文默认值；治理规则本身在 core，不改此处即启用 core 默认策略。</p>
 */
@ConfigurationProperties(prefix = "towndemontower")
public class TownDemontowerProperties {

    /** 总开关；默认开启。设为 false 整个适配层不装配。 */
    private boolean enabled = true;

    /** 执行视图（AgentMode）：DEV_EXECUTION / ARCHITECT_PLANNING / AUDIT_REVIEWING。 */
    private String mode = "DEV_EXECUTION";

    /** 默认 agentId，用于权限引擎授权与审计上下文。 */
    private String agentId = "agent-default";

    /** 默认租户。 */
    private String tenantId = "tenant-default";

    /** 默认会话 / 任务 / 所有者，用于审计上下文（可按请求覆盖）。 */
    private String sessionId = "default-session";
    private String taskId = "default-task";
    private String ownerUserId = "default-owner";

    /** 会话是否已读取不可信来源（污染标记，写/外发需额外校验）。 */
    private boolean tainted = false;

    /**
     * 是否把 @AgentSkill 声明的 requiredPerms 自动授权给 agentId。
     *
     * <p><b>默认 false（V1.6 起）</b>：权限必须由调用方显式授予（{@link #permissionsBootstrap}、
     * 注册表下发或自有 IAM），"写个注解就自己给自己发权限"不再成立。
     * 需要兼容旧行为时显式打开，并在自检里会被记 {@code AUTO_GRANT_ENABLED}。</p>
     */
    private boolean autoGrantDeclaredPerms = false;

    /**
     * 启动时显式授予 {@link #agentId} 的权限清单（RBAC 的正当入口）。
     * 默认空 —— 不声明就一个权限都没有，调用带权限要求的工具会被拒。
     */
    private List<String> permissionsBootstrap = new ArrayList<>();

    /**
     * 治理被关闭（{@code enabled=false}）时的行为：{@code deny}（默认，全部拒绝）或 {@code allow}。
     *
     * <p>默认 deny：关掉开关应当是"熔断"，不是"拆掉闸门"。选 {@code allow} 会被自检记为
     * CRITICAL，strict 模式下直接拒绝启动。</p>
     */
    private String disabledBehavior = "deny";

    /**
     * 旧用法过渡宽限：{@code enabled=false} 时，<b>进程内第一次</b>本应被熔断的调用予以放行，
     * 并写审计 {@code LEGACY_GOVERNANCE_GRACE} + SEVERE 日志；<b>第二次起一律拒绝</b>。
     *
     * <p>默认 {@code true}：这是给"升级前就在用 {@code enabled=false} 当放行开关"的接入方
     * 留的一次观测机会——它跑得动一次，但会在审计和自检里留下明确痕迹，而不是静默失效。
     * 设 {@code false} 则首次调用即硬拒绝。</p>
     *
     * <p>宽限只在"未显式弃权"（{@code disabled-behavior != allow}）时生效；显式写
     * {@code allow} 是知情选择，不再需要宽限，同时会命自检 CRITICAL。</p>
     */
    private boolean legacyGrace = true;

    /** 审计存储：{@code file}（默认，落盘 + 启动校验链）或 {@code memory}（仅内存，重启即丢）。 */
    private String auditStorage = "file";

    /** file 模式下的审计日志路径。 */
    private String auditFile = ".towndemontower/audit.log";

    /**
     * 审计外锚追平粒度：每写 N 条审计把锚点（{@code .anchor}）追平一次。
     *
     * <p>默认 {@code 1} = <b>每写一条就追平</b>，这是 v1.9 的证据强度，也是本项默认值
     * 刻意不为"性能"让步的原因：锚点一旦落后，落后的那几条被整段删除后链<b>仍然自洽</b>，
     * 外锚发现不了。批量化只在"审计写入成为瓶颈、且接受尾部截断可见性下降"时才开。</p>
     *
     * <p>大于 1 时自检会报 {@code AUDIT_ANCHOR_BATCHED}(INFO)，把这条代价写在启动日志里，
     * 而不是只写在文档里。</p>
     */
    private int auditAnchorBatch = 1;

    /**
     * 按时间追平的间隔（毫秒），与 {@link #auditAnchorBatch} 取"先到者"。
     * {@code 0}（默认）= 不按时间追平。低频场景（几分钟才一条审计）开批量化时，
     * 建议配一个上限，避免锚点长期停在很久以前。
     */
    private long auditAnchorIntervalMs = 0L;

    /**
     * 审计外锚副本（V1.10 / I4-c）。逗号或分号分隔，每段一个副本：
     * <pre>
     * file:/var/lib/towndemontower/anchor.ledger     本机另一个路径（权限域隔离）
     * file+offhost:/mnt/audit-vault/anchor.ledger    运维声明"这块盘在别的失败域"
     * https://collector.example.com:8443/anchor      真正跨主机
     * </pre>
     *
     * <p>默认空 = 只有本地锚点。本地锚点与日志同盘，<b>整个目录回滚到旧快照时本地检查全会通过</b>
     * ——副本是唯一能识破这件事的东西。写错一段就启动失败，不静默忽略。</p>
     */
    private String auditAnchorReplicas = "";

    /**
     * 副本不可读 / 不可写时是否拒绝（默认 {@code true}）。
     *
     * <p>两条路径都按它判断：启动时读不回来 → 无法排除回滚，拒绝启动；运行时推不出去 →
     * 证据链少了一份异地拷贝，抛异常。关掉它等于接受"配了副本但可以悄悄不生效"，
     * 自检会报 {@code ANCHOR_REPLICA_BEST_EFFORT}。</p>
     *
     * <p>注意：副本<b>读得到且与本地矛盾</b>时永远拒绝，不受本开关影响——
     * 这个开关表达的是"暂时不可用能不能先起来"，不是"证据对不上能不能先起来"。</p>
     */
    private boolean auditAnchorReplicaRequired = true;

    /**
     * 是否启用"推送失败的锚点本地暂存"（V1.16）。关掉后行为回到 v1.10：
     * 失败只记状态，不落盘暂存。
     */
    private boolean auditSyncSpoolEnabled = true;

    /** 暂存目录；留空则使用 {@code <audit-file>.spool}。 */
    private String auditSyncSpoolDir = "";

    /** 暂存记录保留时长（小时，默认 7 天）。 */
    private long auditSyncRetentionHours = 168L;

    /** 暂存目录容量上限（字节，默认 64 MiB）；超出后从最旧的记录删起。 */
    private long auditSyncMaxBytes = 64L * 1024 * 1024;

    /** 单条记录最多自动补传次数；超过后移入 {@code dead/} 保留，不删除。 */
    private int auditSyncMaxAttempts = 5;

    /** 补传与清理的轮询间隔（毫秒，默认 60 秒）。 */
    private long auditSyncRetryIntervalMs = 60_000L;

    /**
     * 是否暴露治理管理接口（{@code /towndemontower/admin/**}，默认关闭）。
     *
     * <p>管理接口能批准/驳回审批单、触发补传与清理，权限高于观测端点；
     * 因此与 {@code metrics-endpoint} 一样默认不装配，需要时显式打开，并复用
     * {@code metrics-token} 作为访问令牌（{@code X-Governance-Token}）。</p>
     */
    private boolean adminApiEnabled = false;

    /** 流水线状态存储：{@code file}（默认）或 {@code memory}。 */
    private String stateStorage = "file";

    /** file 模式下的状态目录。 */
    private String stateDir = ".towndemontower/state";

    /** 计划单落盘文件；留空则使用 {@code <state-dir>/plans.log}。 */
    private String planFile = "";

    /**
     * 计划单文件轮转阈值（字节，V1.15）：超过即把每单最后一条快照压成新文件，原文件转存归档。
     * {@code 0}（默认）表示不轮转、保持纯追加。
     */
    private long planMaxBytes = 0L;

    /** 审批单默认 TTL；超时即视为拒绝（默认 1 小时）。 */
    private Duration approvalTtl = Duration.ofHours(1);

    /** 审批单落盘目录；默认落盘，避免"重启后单据全丢"。 */
    private String approvalDir = ".towndemontower/approvals";

    /**
     * 是否暴露 {@code /towndemontower/metrics} 与 {@code /towndemontower/integrity}。
     * 默认关闭：接入方的 API 面不该因为引入治理 starter 而多出两个端点。
     */
    private boolean metricsEndpoint = false;

    /**
     * 观测端点的访问令牌（请求头 {@code X-Governance-Token}）。
     * 打开端点却不配令牌时，strict 模式下拒绝启动——遥测数据同样包含敏感信息，不应裸奔。
     */
    private String metricsToken = "";

    /** 启动自检是否严格：CRITICAL 发现项直接拒绝启动。 */
    private boolean integrityStrict = true;

    /**
     * 单参数最大长度（字符）。
     *
     * <p>默认 1 MiB：这个上限的目的是"给参数一个边界"，而不是判断参数是不是正文。
     * 定得过小（如 8 KiB）会把 {@code writeFile(path, content)} 这类合法的
     * 大文本写入当成攻击拦掉——治理不该改变业务能不能跑。需要更严可下调。</p>
     */
    private int maxArgLength = 1_048_576;

    /** 参数个数上限。 */
    private int maxArgs = 64;

    /** 是否拒绝参数中的控制字符（允许 \n \r \t）。 */
    private boolean rejectControlChars = true;

    /** 是否拒绝路径类参数中的 {@code ..} 段。 */
    private boolean rejectPathTraversal = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public boolean isTainted() {
        return tainted;
    }

    public void setTainted(boolean tainted) {
        this.tainted = tainted;
    }

    public boolean isAutoGrantDeclaredPerms() {
        return autoGrantDeclaredPerms;
    }

    public void setAutoGrantDeclaredPerms(boolean autoGrantDeclaredPerms) {
        this.autoGrantDeclaredPerms = autoGrantDeclaredPerms;
    }

    public String getStateStorage() {
        return stateStorage;
    }

    public void setStateStorage(String stateStorage) {
        this.stateStorage = stateStorage;
    }

    public String getStateDir() {
        return stateDir;
    }

    public void setStateDir(String stateDir) {
        this.stateDir = stateDir;
    }

    public String getPlanFile() {
        return planFile;
    }

    public void setPlanFile(String planFile) {
        this.planFile = planFile;
    }

    public long getPlanMaxBytes() {
        return planMaxBytes;
    }

    public void setPlanMaxBytes(long planMaxBytes) {
        this.planMaxBytes = planMaxBytes;
    }

    public Duration getApprovalTtl() {
        return approvalTtl;
    }

    public void setApprovalTtl(Duration approvalTtl) {
        this.approvalTtl = approvalTtl;
    }

    public String getApprovalDir() {
        return approvalDir;
    }

    public void setApprovalDir(String approvalDir) {
        this.approvalDir = approvalDir;
    }

    public boolean isAdminApiEnabled() {
        return adminApiEnabled;
    }

    public void setAdminApiEnabled(boolean adminApiEnabled) {
        this.adminApiEnabled = adminApiEnabled;
    }

    public boolean isMetricsEndpoint() {
        return metricsEndpoint;
    }

    public void setMetricsEndpoint(boolean metricsEndpoint) {
        this.metricsEndpoint = metricsEndpoint;
    }

    public List<String> getPermissionsBootstrap() {
        return permissionsBootstrap;
    }

    public void setPermissionsBootstrap(List<String> permissionsBootstrap) {
        this.permissionsBootstrap = permissionsBootstrap == null ? new ArrayList<>() : permissionsBootstrap;
    }

    public String getDisabledBehavior() {
        return disabledBehavior;
    }

    public void setDisabledBehavior(String disabledBehavior) {
        this.disabledBehavior = disabledBehavior;
    }

    public boolean isLegacyGrace() {
        return legacyGrace;
    }

    public void setLegacyGrace(boolean legacyGrace) {
        this.legacyGrace = legacyGrace;
    }

    public String getAuditStorage() {
        return auditStorage;
    }

    public void setAuditStorage(String auditStorage) {
        this.auditStorage = auditStorage;
    }

    public String getAuditFile() {
        return auditFile;
    }

    public void setAuditFile(String auditFile) {
        this.auditFile = auditFile;
    }

    public int getAuditAnchorBatch() {
        return auditAnchorBatch;
    }

    /** {@code <= 1} 均按"每条追平"处理，不因写错成 0 或负数而静默关掉锚点。 */
    public void setAuditAnchorBatch(int auditAnchorBatch) {
        this.auditAnchorBatch = Math.max(1, auditAnchorBatch);
    }

    public long getAuditAnchorIntervalMs() {
        return auditAnchorIntervalMs;
    }

    /** 负数按 0（不按时间追平）处理。 */
    public void setAuditAnchorIntervalMs(long auditAnchorIntervalMs) {
        this.auditAnchorIntervalMs = Math.max(0L, auditAnchorIntervalMs);
    }

    public String getAuditAnchorReplicas() {
        return auditAnchorReplicas;
    }

    public void setAuditAnchorReplicas(String auditAnchorReplicas) {
        this.auditAnchorReplicas = auditAnchorReplicas == null ? "" : auditAnchorReplicas.trim();
    }

    public boolean isAuditAnchorReplicaRequired() {
        return auditAnchorReplicaRequired;
    }

    public void setAuditAnchorReplicaRequired(boolean auditAnchorReplicaRequired) {
        this.auditAnchorReplicaRequired = auditAnchorReplicaRequired;
    }

    public boolean isAuditSyncSpoolEnabled() {
        return auditSyncSpoolEnabled;
    }

    public void setAuditSyncSpoolEnabled(boolean auditSyncSpoolEnabled) {
        this.auditSyncSpoolEnabled = auditSyncSpoolEnabled;
    }

    public String getAuditSyncSpoolDir() {
        return auditSyncSpoolDir;
    }

    public void setAuditSyncSpoolDir(String auditSyncSpoolDir) {
        this.auditSyncSpoolDir = auditSyncSpoolDir;
    }

    public long getAuditSyncRetentionHours() {
        return auditSyncRetentionHours;
    }

    public void setAuditSyncRetentionHours(long auditSyncRetentionHours) {
        this.auditSyncRetentionHours = Math.max(0L, auditSyncRetentionHours);
    }

    public long getAuditSyncMaxBytes() {
        return auditSyncMaxBytes;
    }

    public void setAuditSyncMaxBytes(long auditSyncMaxBytes) {
        this.auditSyncMaxBytes = Math.max(0L, auditSyncMaxBytes);
    }

    public int getAuditSyncMaxAttempts() {
        return auditSyncMaxAttempts;
    }

    public void setAuditSyncMaxAttempts(int auditSyncMaxAttempts) {
        this.auditSyncMaxAttempts = Math.max(1, auditSyncMaxAttempts);
    }

    public long getAuditSyncRetryIntervalMs() {
        return auditSyncRetryIntervalMs;
    }

    public void setAuditSyncRetryIntervalMs(long auditSyncRetryIntervalMs) {
        this.auditSyncRetryIntervalMs = Math.max(1000L, auditSyncRetryIntervalMs);
    }

    public String getMetricsToken() {
        return metricsToken;
    }

    public void setMetricsToken(String metricsToken) {
        this.metricsToken = metricsToken;
    }

    public boolean isIntegrityStrict() {
        return integrityStrict;
    }

    public void setIntegrityStrict(boolean integrityStrict) {
        this.integrityStrict = integrityStrict;
    }

    public int getMaxArgLength() {
        return maxArgLength;
    }

    public void setMaxArgLength(int maxArgLength) {
        this.maxArgLength = maxArgLength;
    }

    public int getMaxArgs() {
        return maxArgs;
    }

    public void setMaxArgs(int maxArgs) {
        this.maxArgs = maxArgs;
    }

    public boolean isRejectControlChars() {
        return rejectControlChars;
    }

    public void setRejectControlChars(boolean rejectControlChars) {
        this.rejectControlChars = rejectControlChars;
    }

    public boolean isRejectPathTraversal() {
        return rejectPathTraversal;
    }

    public void setRejectPathTraversal(boolean rejectPathTraversal) {
        this.rejectPathTraversal = rejectPathTraversal;
    }

    /** 把配置的字符串模式解析为 core 的 {@link com.towndemontower.core.mode.AgentMode}。 */
    public com.towndemontower.core.mode.AgentMode getModeEnum() {
        return com.towndemontower.core.mode.AgentMode.valueOf(mode.trim());
    }
}
