package com.towndemontower.delivery.config;

import com.towndemontower.core.approval.ApprovalPolicy;
import com.towndemontower.core.approval.HumanApprovalPolicy;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.auth.ApiCallerRegistry;
import com.towndemontower.core.auth.AuthMode;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.executor.SnapshotService;
import com.towndemontower.core.policy.DefaultRiskGate;
import com.towndemontower.core.policy.RiskGate;
import com.towndemontower.core.policy.SimpleShellAstAnalyzer;
import com.towndemontower.delivery.domain.DeliveryRepository;
import com.towndemontower.delivery.domain.FileDeliveryRepository;
import com.towndemontower.delivery.domain.InMemoryDeliveryRepository;
import com.towndemontower.delivery.governance.DeliveryGovernanceFacade;
import com.towndemontower.delivery.governance.DeliveryModeToolPolicy;
import com.towndemontower.delivery.service.DeliveryProgressService;
import com.towndemontower.spring.web.ApiAuthFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * [delivery] 交付看板自动配置。
 *
 * <p>条件装配规则：
 * <ul>
 *   <li>{@code @ConditionalOnProperty(prefix="towndemontower.delivery", name="enabled",
 *       havingValue="true", matchIfMissing=true)} —— 默认开启，设为 false 不装配；</li>
 *   <li>{@code @ConditionalOnMissingBean} —— 仓储 / 服务 / 门面均可被业务方自定义实现覆盖。</li>
 *   <li>{@code @AutoConfigureAfter(TownDemontowerAutoConfiguration.class)} ——
 *       排在 starter 之后装配，从而优先复用 starter 已有的治理组件。</li>
 * </ul>
 *
 * <p><b>治理组件复用策略（重要）</b>：本模块<b>不</b>把 {@code SkillRegistry} 与
 * {@code ModeToolPolicy} 注册成 Spring Bean——starter 也注册了同类型 Bean，
 * 若本模块再注册一份，容器里就会出现两个同类型 Bean，
 * 导致 starter 装配主链 Executor 时按类型注入产生歧义；
 * 更糟的是若本模块的 Bean 先被创建，starter 的
 * {@code @ConditionalOnMissingBean} 会误判而放弃注册自己的白名单，
 * 业务主链的白名单将被交付看板的工具污染。</p>
 *
 * <p>因此本模块的做法是：
 * <ul>
 *   <li>{@code SkillRegistry} —— <b>直接复用</b>容器里已有的实例（starter 装配的那个），
 *       交付看板的工具注册进去，同一条治理链共享工具元数据；</li>
 *   <li>模式白名单 —— 交付看板使用<b>独立实例</b>（{@link DeliveryModeToolPolicy}），
 *       但它<b>不是</b> Spring Bean（装配门面时以普通对象传入），
 *       因此既不与 starter 的 {@code ModeToolPolicy} Bean 冲突，
 *       也不会让交付看板工具进入业务主链的白名单。</li>
 *   <li>其余治理组件（{@link AuditStore} / {@link PermissionEngine} / {@link RiskGate} /
 *       {@link SnapshotService} / {@link ApprovalPolicy}）——<b>共享</b>实例：
 *       容器里已有（来自 starter）就复用，没有就自建兜底。
 *       于是看板写操作与业务操作写入<b>同一条审计哈希链</b>，治理规则也只有一套。</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "towndemontower.delivery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter(name = "com.towndemontower.spring.TownDemontowerAutoConfiguration")
@EnableConfigurationProperties(DeliveryProperties.class)
public class DeliveryAutoConfiguration {

    private static final Logger LOG = Logger.getLogger(DeliveryAutoConfiguration.class.getName());

    /** 交付看板的仓储：memory（默认）或 file。 */
    @Bean
    @ConditionalOnMissingBean(DeliveryRepository.class)
    public DeliveryRepository deliveryRepository(DeliveryProperties props) {
        if ("file".equalsIgnoreCase(props.getStorage())) {
            Path path = Paths.get(props.getDataFile());
            try {
                return new FileDeliveryRepository(path);
            } catch (IOException e) {
                throw new IllegalStateException("init file delivery repository failed: " + path, e);
            }
        }
        return new InMemoryDeliveryRepository();
    }

    /** 交付进度服务（纯 Java 领域服务）。 */
    @Bean
    @ConditionalOnMissingBean
    public DeliveryProgressService deliveryProgressService(DeliveryRepository repository) {
        return new DeliveryProgressService(repository);
    }

    /* ---------- 治理组件兜底（starter 缺席时自建；starter 在场时全部让位） ---------- */

    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry deliveryFallbackSkillRegistry() {
        return new SkillRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public PermissionEngine deliveryFallbackPermissionEngine() {
        return new PermissionEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public SnapshotService deliveryFallbackSnapshotService() {
        return new SnapshotService();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditStore deliveryFallbackAuditStore() {
        return new HashChainAuditStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public RiskGate deliveryFallbackRiskGate() {
        return new DefaultRiskGate(new SimpleShellAstAnalyzer());
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalPolicy deliveryFallbackApprovalPolicy() {
        return new HumanApprovalPolicy();
    }

    /**
     * 治理门面：内部自建交付看板专用治理链，并共享业务方的治理组件与审计存储。
     *
     * <p>{@code SkillRegistry} 直接复用容器里已有的实例（starter 装配的那个），
     * 交付看板的工具注册进去——不会新增第二个同类型 Bean。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public DeliveryGovernanceFacade deliveryGovernanceFacade(SkillRegistry skillRegistry,
                                                             RiskGate riskGate,
                                                             PermissionEngine permissionEngine,
                                                             SnapshotService snapshotService,
                                                             AuditStore auditStore,
                                                             ApprovalPolicy approvalPolicy,
                                                             DeliveryProgressService service,
                                                             DeliveryProperties props) {
        DeliveryGovernanceFacade facade = new DeliveryGovernanceFacade(
                skillRegistry, new DeliveryModeToolPolicy(), riskGate, permissionEngine,
                snapshotService, auditStore, approvalPolicy, service, props.isGoverned());
        facade.grantPermissions(PermissionProvisioner.of(permissionEngine), props.getAgentId());
        return facade;
    }

    /** 调用令牌表（V1.8 / I6）：只读环境变量，未配置即空表（token 模式下等于全拒）。 */
    @Bean
    @ConditionalOnMissingBean(ApiCallerRegistry.class)
    public ApiCallerRegistry deliveryApiCallerRegistry() {
        ApiCallerRegistry registry = ApiCallerRegistry.fromEnv();
        if (registry.hasCallers()) {
            LOG.info("[delivery] 调用令牌已装配：" + registry.callerNames() + "，状态 " + registry.status());
        }
        for (String r : registry.rejected()) {
            LOG.warning("[delivery] 调用令牌条目被拒：" + r);
        }
        if (registry.status() == ApiCallerRegistry.Status.WEAK) {
            LOG.severe("[delivery] " + ApiCallerRegistry.TOKENS_ENV
                    + " 已设置但没有一条合格令牌：token 模式下所有 /api/** 请求将被拒绝");
        }
        return registry;
    }

    /** 注册认证过滤器：只拦 {@code /api/*}，看板页面与静态资源不受影响。 */
    @Bean
    public FilterRegistrationBean<ApiAuthFilter> deliveryApiAuthFilter(
            DeliveryProperties props, ApiCallerRegistry registry, AuditStore auditStore) {
        AuthMode mode = AuthMode.parse(props.getAuthMode());
        if (mode == null) {
            throw new IllegalStateException("towndemontower.delivery.auth-mode 值非法：["
                    + props.getAuthMode() + "]，可用：loopback / token / off");
        }
        if (mode == AuthMode.OFF) {
            LOG.warning("[delivery] 认证已关闭（auth-mode=off）：/api/** 任何来源都可调用，"
                    + "仅当外层网关已强制鉴权时才可接受");
        }
        if (mode == AuthMode.LOOPBACK) {
            LOG.info("[delivery] 认证模式 loopback：读放行，写操作只允许本机；"
                    + "跨机调用请设 auth-mode=token 并配置 " + ApiCallerRegistry.TOKENS_ENV);
        }
        FilterRegistrationBean<ApiAuthFilter> bean = new FilterRegistrationBean<>(
                new ApiAuthFilter(registry, mode, "delivery", auditStore));
        bean.addUrlPatterns("/api/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        bean.setName("deliveryApiAuthFilter");
        return bean;
    }
}
