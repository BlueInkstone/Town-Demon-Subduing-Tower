package com.towndemontower.agents.config;

import com.towndemontower.agents.domain.AccessProvisioner;
import com.towndemontower.agents.domain.AgentRepository;
import com.towndemontower.agents.domain.FileAgentRepository;
import com.towndemontower.agents.domain.InMemoryAgentRepository;
import com.towndemontower.agents.domain.SkillCatalog;
import com.towndemontower.agents.governance.AgentGovernanceFacade;
import com.towndemontower.agents.governance.AgentsModeToolPolicy;
import com.towndemontower.agents.governance.BindingReadinessCheck;
import com.towndemontower.agents.governance.CallerBindingGuard;
import com.towndemontower.agents.governance.CorePermissionProvisioner;
import com.towndemontower.agents.governance.RegistrySkillCatalog;
import com.towndemontower.agents.service.AgentService;
import com.towndemontower.core.approval.ApprovalPolicy;
import com.towndemontower.core.approval.HumanApprovalPolicy;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.auth.ApiCallerRegistry;
import com.towndemontower.core.auth.AuthMode;
import com.towndemontower.core.auth.CallerAgentBindings;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.executor.SnapshotService;
import com.towndemontower.core.policy.DefaultRiskGate;
import com.towndemontower.core.policy.RiskGate;
import com.towndemontower.core.policy.SimpleShellAstAnalyzer;
import com.towndemontower.spring.web.ApiAuthFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
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
 * [agents] Agent 注册表自动配置。
 *
 * <p>装配顺序排在 starter 之后，<b>优先复用</b> starter 已有的治理组件
 * （SkillRegistry / PermissionEngine / AuditStore / RiskGate / SnapshotService / ApprovalPolicy），
 * 没有则自建兜底。于是注册表的写操作与业务主链写入<b>同一条审计哈希链</b>，
 * 授权也落到同一个 {@link PermissionEngine} 上——Agent 的权限因此对整条执行链生效，
 * 而不是只在注册表自己的小世界里有效。</p>
 *
 * <p><b>不注册 {@code SkillRegistry} 与 {@code ModeToolPolicy} 类型的冲突 Bean</b>：
 * 注册表的模式白名单用独立实例（{@link AgentsModeToolPolicy}）以普通对象传入门面，
 * 不注册为 Spring Bean，避免污染 starter 的主链白名单。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "towndemontower.agents", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@org.springframework.boot.autoconfigure.AutoConfigureAfter(
        name = "com.towndemontower.spring.TownDemontowerAutoConfiguration")
@EnableConfigurationProperties(AgentsProperties.class)
public class AgentsAutoConfiguration {

    private static final Logger LOG = Logger.getLogger(AgentsAutoConfiguration.class.getName());

    /** Agent 档案仓储：memory（默认）或 file。 */
    @Bean
    @ConditionalOnMissingBean(AgentRepository.class)
    public AgentRepository agentRepository(AgentsProperties props) {
        if ("file".equalsIgnoreCase(props.getStorage())) {
            Path path = Paths.get(props.getDataFile());
            try {
                return new FileAgentRepository(path);
            } catch (IOException e) {
                throw new IllegalStateException("init file agent repository failed: " + path, e);
            }
        }
        return new InMemoryAgentRepository();
    }

    /** 技能目录：校验 Agent 技能必须存在于 core 工具注册表。 */
    @Bean
    @ConditionalOnMissingBean(SkillCatalog.class)
    public SkillCatalog agentSkillCatalog(SkillRegistry registry) {
        return new RegistrySkillCatalog(registry);
    }

    /** 权限开通端口：落到 core RBAC。 */
    @Bean
    @ConditionalOnMissingBean(AccessProvisioner.class)
    public AccessProvisioner agentAccessProvisioner(PermissionEngine engine) {
        return new CorePermissionProvisioner(PermissionProvisioner.of(engine), engine);
    }

    /** 注册表领域服务（纯 Java）。 */
    @Bean
    @ConditionalOnMissingBean
    public AgentService agentDirectoryService(AgentRepository repository,
                                              SkillCatalog skillCatalog) {
        return new AgentService(repository, skillCatalog);
    }

    /**
     * 凭据 → Agent 绑定的解析器（V1.9 / I3-b）。
     *
     * <p>控制器用它在每个请求上决定治理主体：绑定命中就用绑定的 Agent，
     * 未绑定才回落到配置身份。放在这里而不是控制器内部，是为了让"回落规则"
     * 只有一处实现，且可以被单独测试。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public CallerBindingGuard agentCallerBindingGuard(AgentService service,
                                                      AgentsProperties props) {
        return new CallerBindingGuard(service, props.getAgentId());
    }

    /* ---------- 治理组件兜底（starter 缺席时自建；starter 在场时全部让位） ---------- */

    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry agentsFallbackSkillRegistry() {
        return new SkillRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public PermissionEngine agentsFallbackPermissionEngine() {
        return new PermissionEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public SnapshotService agentsFallbackSnapshotService() {
        return new SnapshotService();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditStore agentsFallbackAuditStore() {
        return new HashChainAuditStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public RiskGate agentsFallbackRiskGate() {
        return new DefaultRiskGate(new SimpleShellAstAnalyzer());
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalPolicy agentsFallbackApprovalPolicy() {
        return new HumanApprovalPolicy();
    }

    /** 注册表治理门面：自建专属链，共享业务方治理组件与审计存储。 */
    @Bean
    @ConditionalOnMissingBean
    public AgentGovernanceFacade agentGovernanceFacade(SkillRegistry skillRegistry,
                                                       RiskGate riskGate,
                                                       PermissionEngine permissionEngine,
                                                       SnapshotService snapshotService,
                                                       AuditStore auditStore,
                                                       ApprovalPolicy approvalPolicy,
                                                       AgentService service,
                                                       AccessProvisioner provisioner,
                                                       AgentsProperties props) {
        AgentGovernanceFacade facade = new AgentGovernanceFacade(
                skillRegistry, new AgentsModeToolPolicy(), riskGate, permissionEngine,
                snapshotService, auditStore, approvalPolicy, service, provisioner, props.isGoverned());
        facade.grantPermissions(PermissionProvisioner.of(permissionEngine), props.getAgentId());
        return facade;
    }

    /**
     * 调用令牌表（V1.8 / I6）：<b>只</b>读环境变量 {@code TOWNDEMONTOWER_API_TOKENS}。
     *
     * <p>不合格条目只进日志（打名称与原因，不打令牌内容）；一条都不可用时表为空，
     * {@code token} 模式下即全部拒绝——这是刻意的 fail-closed。</p>
     */
    @Bean
    @ConditionalOnMissingBean(ApiCallerRegistry.class)
    public ApiCallerRegistry agentsApiCallerRegistry() {
        ApiCallerRegistry registry = ApiCallerRegistry.fromEnv();
        if (registry.hasCallers()) {
            LOG.info("[agents] 调用令牌已装配：" + registry.callerNames() + "，状态 " + registry.status());
        }
        for (String r : registry.rejected()) {
            LOG.warning("[agents] 调用令牌条目被拒：" + r);
        }
        if (registry.status() == ApiCallerRegistry.Status.WEAK) {
            LOG.severe("[agents] " + ApiCallerRegistry.TOKENS_ENV
                    + " 已设置但没有一条合格令牌：token 模式下所有 /api/** 请求将被拒绝");
        }
        return registry;
    }

    /**
     * 凭据 → Agent 绑定表（V1.9 / I3-b）：<b>只</b>读环境变量
     * {@code TOWNDEMONTOWER_API_BINDINGS}，格式 {@code 调用者:AGT-0001,...}。
     *
     * <p>空表不启用绑定（回落 V1.8 行为），但启动时必须点名——否则"身份还停在
     * 服务账号层面"这件事永远没人看见。</p>
     */
    @Bean
    @ConditionalOnMissingBean(CallerAgentBindings.class)
    public CallerAgentBindings agentsCallerBindings(AgentsProperties props,
                                                    ApiCallerRegistry registry) {
        CallerAgentBindings bindings = CallerAgentBindings.fromEnv();
        String mode = props.getAuthMode() == null ? "loopback" : props.getAuthMode().trim();

        if (!bindings.strict()) {
            // 级别取决于认证模式：token 模式下"有身份、没 Agent 归属"是真实缺口，记 WARN；
            // loopback / off 下本来就没有调用者身份，记 INFO 即可，别把默认启动刷成一片黄。
            String msg = "[agents] " + CallerAgentBindings.ENV + " 未配置有效绑定（状态 "
                    + bindings.status() + "）：所有调用者共用注册表身份 ["
                    + props.getAgentId() + "]，审计无法区分是哪个 Agent 在操作；"
                    + "需要按 Agent 隔离权限请配置 "
                    + "TOWNDEMONTOWER_API_BINDINGS=调用者:AGT-0001,调用者2:AGT-0002";
            if ("token".equalsIgnoreCase(mode)) {
                LOG.warning(msg);
            } else {
                LOG.info(msg);
            }
            for (String r : bindings.rejected()) {
                LOG.warning("[agents] 绑定条目被拒：" + r);
            }
            return bindings;
        }

        LOG.info("[agents] 凭据→Agent 绑定已启用（严格模式）：" + bindings.size()
                + " 条 " + bindings.asMap() + "，状态 " + bindings.status());
        for (String r : bindings.rejected()) {
            LOG.warning("[agents] 绑定条目被拒：" + r);
        }
        // 两个方向的孤儿都必须点名：拼错名字的绑定永不生效，漏配的令牌必然 403。
        for (String c : bindings.callersWithoutToken(registry)) {
            LOG.warning("[agents] 绑定表里的调用者 [" + c + "] 没有对应令牌："
                    + "该条绑定永不生效（检查 " + ApiCallerRegistry.TOKENS_ENV + " 是否拼错）");
        }
        if ("token".equalsIgnoreCase(mode)) {
            for (String c : bindings.callersWithoutBinding(registry)) {
                LOG.warning("[agents] 令牌调用者 [" + c + "] 未绑定 Agent：严格模式下其请求将被 "
                        + "CALLER_NOT_PROVISIONED 拒绝");
            }
        } else if ("loopback".equalsIgnoreCase(mode)) {
            LOG.warning("[agents] 绑定表已配置但 auth-mode=loopback：loopback 下没有调用者身份，"
                    + "绑定不会生效；要按 Agent 隔离权限请设 auth-mode=token 并配置 "
                    + ApiCallerRegistry.TOKENS_ENV);
        }
        return bindings;
    }

    /**
     * 启动就绪后的绑定体检：把"哪条绑定指向了不存在/非 ACTIVE 的 Agent"提前说清。
     *
     * <p>用 {@code ApplicationReadyEvent} 而不是 Bean 初始化，是因为演示数据由
     * {@code ApplicationRunner} 播种，Bean 构造期看到的是空仓储，会误报。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public BindingReadinessCheck agentBindingReadinessCheck(CallerAgentBindings bindings,
                                                            AgentService service) {
        return new BindingReadinessCheck(bindings, service);
    }

    /**
     * 注册认证过滤器，只拦 {@code /api/*}。
     *
     * <p>用 {@code FilterRegistrationBean} 显式限定路径，而不是让 {@code Filter} 被自动
     * 注册到全站：看板页面、静态资源、OpenAPI 文档都不该被认证逻辑牵连。</p>
     *
     * <p>V1.9 / I3-b 起额外传入绑定表：认证通过后还要回答"代表哪个 Agent"，
     * 未绑定即 403（严格模式）。</p>
     */
    @Bean
    public FilterRegistrationBean<ApiAuthFilter> agentsApiAuthFilter(
            AgentsProperties props, ApiCallerRegistry registry, AuditStore auditStore,
            CallerAgentBindings bindings) {
        AuthMode mode = AuthMode.parse(props.getAuthMode());
        if (mode == null) {
            throw new IllegalStateException("towndemontower.agents.auth-mode 值非法：["
                    + props.getAuthMode() + "]，可用：loopback / token / off");
        }
        if (mode == AuthMode.OFF) {
            LOG.warning("[agents] 认证已关闭（auth-mode=off）：/api/** 任何来源都可调用，"
                    + "仅当外层网关已强制鉴权时才可接受");
        }
        if (mode == AuthMode.LOOPBACK) {
            LOG.info("[agents] 认证模式 loopback：读放行，写操作只允许本机；"
                    + "跨机调用请设 auth-mode=token 并配置 " + ApiCallerRegistry.TOKENS_ENV);
        }
        FilterRegistrationBean<ApiAuthFilter> bean = new FilterRegistrationBean<>(
                new ApiAuthFilter(registry, mode, "agents", auditStore, bindings));
        bean.addUrlPatterns("/api/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        bean.setName("agentsApiAuthFilter");
        return bean;
    }
}
