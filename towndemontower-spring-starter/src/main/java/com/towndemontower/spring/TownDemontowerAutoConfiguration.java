package com.towndemontower.spring;

import com.towndemontower.core.approval.ApprovalCenter;
import com.towndemontower.core.approval.ApprovalPolicy;
import com.towndemontower.core.approval.FilePlanApprovalStore;
import com.towndemontower.core.approval.PlanApprovalService;
import com.towndemontower.core.approval.HumanApprovalPolicy;
import com.towndemontower.core.audit.AnchorReplica;
import com.towndemontower.core.audit.AuditSyncSpool;
import com.towndemontower.core.contract.TimeProvider;
import com.towndemontower.core.audit.AuditCrypto;
import com.towndemontower.core.audit.AuditReplicas;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.context.ContextBuilder;
import com.towndemontower.core.context.FilePipelineStateStore;
import com.towndemontower.core.context.InMemoryPipelineStateStore;
import com.towndemontower.core.context.PipelineStateStore;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.executor.ExecutorFactory;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;
import com.towndemontower.core.executor.RejectAllExecutor;
import com.towndemontower.core.executor.SkillInvoker;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.executor.SnapshotService;
import com.towndemontower.core.credential.CredentialBroker;
import com.towndemontower.core.metrics.GovernanceMetrics;
import com.towndemontower.core.mode.DefaultModeToolPolicy;
import com.towndemontower.core.mode.ModeStateMachine;
import com.towndemontower.core.mode.ModeToolPolicy;
import com.towndemontower.core.policy.DefaultRiskGate;
import com.towndemontower.core.policy.RiskGate;
import com.towndemontower.core.policy.ShellAstAnalyzer;
import com.towndemontower.core.policy.SimpleShellAstAnalyzer;
import com.towndemontower.core.routing.ModelRouter;
import com.towndemontower.core.routing.SimpleModelRouter;
import com.towndemontower.spring.admin.AdminExceptionHandler;
import com.towndemontower.spring.admin.ApprovalAdminController;
import com.towndemontower.spring.admin.AuditAdminController;
import com.towndemontower.spring.admin.AuditSyncAdminController;
import com.towndemontower.spring.admin.PlanAdminController;
import com.towndemontower.spring.admin.PlanAdminOperations;
import com.towndemontower.spring.tx.ApprovalEventPublisher;
import com.towndemontower.spring.tx.GovernanceDecisionCommittedEvent;
import com.towndemontower.spring.tx.GovernanceDecisionRecorder;
import com.towndemontower.spring.tx.GovernanceDecisionRepository;
import com.towndemontower.spring.tx.InMemoryWebSocketBroker;
import com.towndemontower.spring.tx.OutboxRelay;
import com.towndemontower.spring.tx.OutboxRepository;
import com.towndemontower.spring.tx.TransactionalGovernanceDecisionRecorder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * [starter] Spring 自动配置：把 core 的默认治理策略一次性装配成 Spring Bean，再用
 * {@link ExecutorFactory#buildDefault} 组装出装饰器链 {@link Executor} 暴露给业务。
 *
 * <p><b>条件装配规则</b>
 * <ul>
 *   <li>{@code @ConditionalOnClass}：classpath 上必须存在 core 的 {@link Executor} 与 AOP 支撑类；
 *       否则整个适配层不生效（避免无 core 时装配失败）。</li>
 *   <li>{@code @ConditionalOnProperty(prefix="towndemontower", name="enabled",
 *       havingValue="true", matchIfMissing=true)}：默认开启，设为 false 全量不装配。</li>
 *   <li>{@code @ConditionalOnMissingBean}：每个 core 组件都允许用户用自定义实现覆盖——
 *       只要提供一个同名类型的 Bean，适配层即接入用户的治理策略（如换 RiskGate / ApprovalPolicy）。</li>
 * </ul>
 *
 * <p><b>职责边界</b>：本类只做"装配与暴露"，不引入任何治理规则；规则全部来自 core 默认实现，
 * 因此 YAML/属性改动只影响开关与上下文，不会绕过 core 的硬性裁决（模式白名单 / 风险闸门 / 审批）。</p>
 */
@AutoConfiguration
@ConditionalOnClass({Executor.class})
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(TownDemontowerProperties.class)
@EnableScheduling
public class TownDemontowerAutoConfiguration {

    private static final Logger LOG = Logger.getLogger(TownDemontowerAutoConfiguration.class.getName());

    /* ---------- core 默认策略组件（均可被用户 Bean 覆盖） ---------- */

    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry townDemontowerSkillRegistry() {
        return new SkillRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(ShellAstAnalyzer.class)
    public ShellAstAnalyzer townDemontowerShellAstAnalyzer() {
        return new SimpleShellAstAnalyzer();
    }

    @Bean
    @ConditionalOnMissingBean(RiskGate.class)
    public RiskGate townDemontowerRiskGate(ShellAstAnalyzer analyzer) {
        return new DefaultRiskGate(analyzer);
    }

    @Bean
    @ConditionalOnMissingBean
    public PermissionEngine townDemontowerPermissionEngine() {
        return new PermissionEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public SnapshotService townDemontowerSnapshotService(CredentialBroker broker) {
        // 快照清单会记录参数与回滚提示，必须走脱敏，避免密钥/令牌被写进磁盘
        return new SnapshotService(
                Path.of(System.getProperty("java.io.tmpdir"), "towndemontower-snapshots"),
                broker::redact);
    }

    /**
     * 审计存储：默认落盘（{@code audit-storage=file}），启动时校验既有链；
     * 显式配置 {@code memory} 会被自检记为 CRITICAL（证据重启即丢）。
     *
     * <p>{@code destroyMethod="flushAnchor"}：批量追平模式下退出前把锚点追到最新一条。
     * 不这么做，进程正常退出时最后那几条审计永远停在锚点之外——被删掉也没人发现，
     * 而这恰恰是"正常运维动作"最容易造成的情形（滚动重启、缩容）。</p>
     *
     * <p><b>V1.10 / I4-c</b>：{@code audit-anchor-replicas} 非空时把锚点副本一并装上。
     * 解析失败直接抛（启动失败）——拼错的副本地址被当成"没配"，会让运维以为异地证据
     * 已经在跑，这比启动失败危险得多。</p>
     */
    @Bean(destroyMethod = "flushAnchor")
    @ConditionalOnMissingBean(AuditStore.class)
    public AuditStore townDemontowerAuditStore(TownDemontowerProperties props) {
        if ("file".equalsIgnoreCase(props.getAuditStorage())) {
            byte[] key = AuditCrypto.envKey();
            List<AnchorReplica> replicas;
            try {
                replicas = AuditReplicas.parse(props.getAuditAnchorReplicas(), key);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("towndemontower.audit-anchor-replicas 配置有误："
                        + e.getMessage(), e);
            }
            if (!replicas.isEmpty()) {
                long offHost = replicas.stream().filter(AnchorReplica::offHost).count();
                LOG.info("[governance] 审计锚点副本 " + replicas.size() + " 个（跨主机 "
                        + offHost + " 个）：" + replicas.stream().map(AnchorReplica::name).toList()
                        + "；required=" + props.isAuditAnchorReplicaRequired());
            }
            AuditSyncSpool spool = null;
            if (props.isAuditSyncSpoolEnabled() && !replicas.isEmpty()) {
                Path auditFile = Path.of(props.getAuditFile());
                Path spoolDir = props.getAuditSyncSpoolDir() == null
                        || props.getAuditSyncSpoolDir().isBlank()
                        ? auditFile.resolveSibling(auditFile.getFileName() + ".spool")
                        : Path.of(props.getAuditSyncSpoolDir());
                spool = new AuditSyncSpool(spoolDir, replicas,
                        props.getAuditSyncRetentionHours() * 3600_000L,
                        props.getAuditSyncMaxBytes(), props.getAuditSyncMaxAttempts(),
                        TimeProvider.system());
                LOG.info("[governance] 审计同步暂存区已启用：" + spoolDir
                        + "（保留 " + props.getAuditSyncRetentionHours() + " 小时，上限 "
                        + props.getAuditSyncMaxBytes() + " 字节，最多补传 "
                        + props.getAuditSyncMaxAttempts() + " 次）");
            }
            return new FileAuditStore(Path.of(props.getAuditFile()), key,
                    props.getAuditAnchorBatch(), props.getAuditAnchorIntervalMs(),
                    replicas, props.isAuditAnchorReplicaRequired(), spool);
        }
        return new HashChainAuditStore();
    }

    /**
     * 审计同步暂存区的定时补传器（V1.16）。
     *
     * <p>{@code AuditStore} 不是文件实现（内存模式 / 调用方自定义）或未配置锚点副本时，
     * spool 为 {@code null}，本 bean 每轮空转，不产生任何 IO。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditSyncRelay townDemontowerAuditSyncRelay(AuditStore auditStore) {
        AuditSyncSpool spool = auditStore instanceof FileAuditStore f ? f.syncSpool() : null;
        return new AuditSyncRelay(spool);
    }

    /** 凭据代理：用于快照清单与日志的脱敏（不落明文）。 */
    @Bean
    @ConditionalOnMissingBean
    public CredentialBroker townDemontowerCredentialBroker() {
        return new CredentialBroker();
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalPolicy.class)
    public ApprovalPolicy townDemontowerApprovalPolicy() {
        return new HumanApprovalPolicy();
    }

    @Bean
    @ConditionalOnMissingBean(ModeToolPolicy.class)
    public ModeToolPolicy townDemontowerModeToolPolicy() {
        return new DefaultModeToolPolicy();
    }

    /** 链尾透传通道：把 AOP 拦截到的真实方法执行注入 core 装饰器链。 */
    @Bean
    @ConditionalOnMissingBean(SkillInvoker.class)
    public DelegatingSkillInvoker townDemontowerSkillInvoker() {
        return new DelegatingSkillInvoker();
    }

    /* ---------- 组装装饰器链 Executor（暴露 core 默认策略的核心入口） ---------- */

    @Bean
    @ConditionalOnMissingBean
    public GovernanceMetrics townDemontowerMetrics() {
        return new GovernanceMetrics();
    }

    @Bean
    @ConditionalOnMissingBean
    public PipelineStateStore townDemontowerStateStore(TownDemontowerProperties props) {
        if ("file".equalsIgnoreCase(props.getStateStorage())) {
            try {
                return new FilePipelineStateStore(Path.of(props.getStateDir()));
            } catch (IOException e) {
                throw new IllegalStateException("pipeline state store init failed: " + props.getStateDir(), e);
            }
        }
        return new InMemoryPipelineStateStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalCenter townDemontowerApprovalCenter(TownDemontowerProperties props) {
        String dir = props.getApprovalDir();
        Path path = (dir == null || dir.isBlank()) ? null : Path.of(dir);
        return new ApprovalCenter(path, props.getApprovalTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelRouter townDemontowerModelRouter() {
        return new SimpleModelRouter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextBuilder townDemontowerContextBuilder(ModelRouter router, GovernanceMetrics metrics) {
        return new ContextBuilder(router, metrics);
    }

    /** 模式会话：模式由状态机推进，切面不再接受外部指定（V1.5）。 */
    @Bean
    @ConditionalOnMissingBean
    public GovernedModeSession townDemontowerModeSession(AuditStore audit, GovernanceMetrics metrics,
                                                         TownDemontowerProperties props) {
        return new GovernedModeSession(new ModeStateMachine(), props.getModeEnum(), audit, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public Executor townDemontowerExecutor(SkillRegistry registry,
                                           ModeToolPolicy modePolicy,
                                           RiskGate riskGate,
                                           PermissionEngine permissionEngine,
                                           SnapshotService snapshotService,
                                           SkillInvoker invoker,
                                           AuditStore audit,
                                           ApprovalPolicy approvalPolicy,
                                           ApprovalCenter approvalCenter,
                                           GovernanceMetrics metrics,
                                           PipelineStateStore stateStore,
                                           PlanApprovalService planApprovals,
                                           TownDemontowerProperties props) {
        // 关闭治理时默认"全部拒绝"（fail-closed）。选 allow 是显式弃权，自检会报 CRITICAL。
        if (!props.isEnabled() && !"allow".equalsIgnoreCase(props.getDisabledBehavior())) {
            return new RejectAllExecutor(audit);
        }
        return ExecutorFactory.buildDefault(registry, modePolicy, riskGate,
                permissionEngine, snapshotService, invoker, audit, approvalPolicy,
                approvalCenter, metrics, stateStore, planApprovals);
    }

    /**
     * 计划审批服务（V1.13 / Plan-and-Execute）。
     *
     * <p>暴露成 Bean 是刻意的：审批动作来自人（CLI / IM / 工单系统），
     * 不来自被治理的 agent 自己。接入方拿这个 Bean 去对接自己的审批入口即可，
     * 不需要改核心代码。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public PlanApprovalService townDemontowerPlanApprovalService(
            AuditStore audit, TownDemontowerProperties props) {
        // 计划提交/批准/否决进同一条审计哈希链：审批动作本身也是治理事件。
        // V1.14：默认落盘到 <state-dir>/plans.log，重启后回放；显式配 plan-file 可覆盖。
        String planFile = props.getPlanFile();
        if (planFile == null || planFile.isBlank()) {
            planFile = Path.of(props.getStateDir()).resolve("plans.log").toString();
        }
        return new PlanApprovalService(
                new FilePlanApprovalStore(Path.of(planFile), props.getPlanMaxBytes()), audit);
    }

    /**
     * 授权端口（V1.9 / I3）：{@code PermissionEngine} 自本版起只读，
     * 所有授权变更必须经本端口，并因此写审计 {@code PERMISSION_*}。
     *
     * <p>这是本 starter 内部<b>唯一</b>能拿到的写权限入口；接入方业务代码
     * 若直接调 {@code engine.grant(...)} 会编译失败。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public PermissionProvisioner townDemontowerPermissionProvisioner(PermissionEngine permissionEngine,
                                                                     AuditStore audit) {
        return PermissionProvisioner.of(permissionEngine, audit, "starter-wiring");
    }

    /** 安全自检：落地显式授权 + CRITICAL 配置拒绝启动。 */
    @Bean
    @ConditionalOnMissingBean
    public GovernanceIntegrityChecker townDemontowerIntegrityChecker(TownDemontowerProperties props,
                                                                     AuditStore audit,
                                                                     PermissionEngine permissionEngine,
                                                                     ApprovalCenter approvalCenter,
                                                                     PermissionProvisioner provisioner) {
        return new GovernanceIntegrityChecker(props, audit, permissionEngine, approvalCenter,
                provisioner);
    }

    /* ---------- 切面：桥接 Spring 方法与 core 治理链 ---------- */

    @Bean
    @ConditionalOnMissingBean
    public AgentSkillAspect agentSkillAspect(Executor executor,
                                             SkillRegistry registry,
                                             PermissionEngine permissionEngine,
                                             DelegatingSkillInvoker invoker,
                                             TownDemontowerProperties props,
                                             GovernedModeSession modeSession,
                                             AuditStore audit,
                                             GovernanceMetrics metrics,
                                             ObjectProvider<GovernanceDecisionRecorder> recorderProvider,
                                             PermissionProvisioner provisioner) {
        AgentSkillAspect aspect = new AgentSkillAspect(executor, registry, permissionEngine,
                invoker, props, modeSession, audit, metrics, recorderProvider);
        aspect.setPermissionProvisioner(provisioner);
        return aspect;
    }

    /* ---------- 观测端点（默认关闭；接入方需要时显式打开） ---------- */

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnProperty(prefix = "towndemontower", name = "metrics-endpoint", havingValue = "true")
    static class MetricsEndpointConfiguration {

        @Bean
        @ConditionalOnMissingBean
        GovernanceMetricsController governanceMetricsController(GovernanceMetrics metrics,
                                                                ApprovalCenter approvalCenter,
                                                                AuditStore auditStore,
                                                                TownDemontowerProperties props,
                                                                GovernanceIntegrityChecker checker) {
            return new GovernanceMetricsController(metrics, approvalCenter, auditStore, props, checker);
        }
    }

    /* ---------- 治理管理接口（V1.17，默认关闭；需要人工审批/运维处置时显式打开） ---------- */

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnProperty(prefix = "towndemontower", name = "admin-api-enabled", havingValue = "true")
    static class AdminApiConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ApprovalAdminController approvalAdminController(ApprovalCenter approvalCenter,
                                                        TownDemontowerProperties props) {
            return new ApprovalAdminController(approvalCenter, props);
        }

        @Bean
        @ConditionalOnMissingBean
        PlanAdminOperations planAdminOperations(PlanApprovalService planApprovals) {
            return new PlanAdminOperations(planApprovals);
        }

        @Bean
        @ConditionalOnMissingBean
        PlanAdminController planAdminController(PlanAdminOperations planAdminOps,
                                                TownDemontowerProperties props) {
            return new PlanAdminController(planAdminOps, props);
        }

        @Bean
        @ConditionalOnMissingBean
        AuditAdminController auditAdminController(AuditStore auditStore,
                                                  TownDemontowerProperties props) {
            return new AuditAdminController(auditStore, props);
        }

        @Bean
        @ConditionalOnMissingBean
        AuditSyncAdminController auditSyncAdminController(AuditStore auditStore,
                                                          TownDemontowerProperties props) {
            return new AuditSyncAdminController(auditStore, props);
        }

        /**
         * 异常映射只作用于 admin 包内的控制器（见类上的 {@code basePackages}），
         * 不会改写接入方自己的 API 契约。
         */
        @Bean
        @ConditionalOnMissingBean
        AdminExceptionHandler adminExceptionHandler() {
            return new AdminExceptionHandler();
        }
    }

    /* ---------- 事务一致性（V1.2.1）：Outbox + WS 推送 ----------
     * 仅在存在 DataSource 且未关闭 towndemontower.tx.enabled 时启用；
     * 无 DataSource 的轻量场景（如 StarterDemo）自动跳过，保持旧行为不变。
     */

    @Bean
    @ConditionalOnMissingBean
    public ApprovalEventPublisher approvalEventPublisher() {
        // 默认内存总线（等价 WS 推送）；生产可替换为 STOMP 实现，本接口不变
        return new InMemoryWebSocketBroker();
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "towndemontower.tx", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public GovernanceDecisionRepository townDemontowerDecisionRepository(DataSource dataSource) {
        return new GovernanceDecisionRepository(dataSource);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "towndemontower.tx", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public OutboxRepository townDemontowerOutboxRepository(DataSource dataSource) {
        return new OutboxRepository(dataSource);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "towndemontower.tx", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public GovernanceDecisionRecorder townDemontowerDecisionRecorder(
            GovernanceDecisionRepository decisionRepository,
            OutboxRepository outboxRepository,
            org.springframework.context.ApplicationEventPublisher eventPublisher) {
        return new TransactionalGovernanceDecisionRecorder(decisionRepository, outboxRepository, eventPublisher);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "towndemontower.tx", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public OutboxRelay townDemontowerOutboxRelay(OutboxRepository outboxRepository,
                                                  ApprovalEventPublisher publisher) {
        return new OutboxRelay(outboxRepository, publisher);
    }
}
