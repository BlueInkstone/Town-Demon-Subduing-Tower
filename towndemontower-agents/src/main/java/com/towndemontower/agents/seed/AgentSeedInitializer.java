package com.towndemontower.agents.seed;

import com.towndemontower.agents.config.AgentsProperties;
import com.towndemontower.agents.domain.AgentRepository;
import com.towndemontower.agents.domain.AgentRole;
import com.towndemontower.agents.domain.AgentStatus;
import com.towndemontower.agents.governance.AgentGovernanceFacade;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.mode.AgentMode;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * [agents/seed] 演示数据播种器。
 *
 * <p>仅在 {@code towndemontower.agents.demo-data=true} 时生效（默认关闭）。
 * 播种走治理门面，因此权限同步与审计留痕都与正常调用一致。</p>
 *
 * <p>两种模式：{@code default}（默认，覆盖四状态五角色的演示数据）/
 * {@code sample}（四种状态各一的精简样例）。</p>
 */
@Configuration
public class AgentSeedInitializer {

    public static final String SEED_MODE_DEFAULT = "default";
    public static final String SEED_MODE_SAMPLE = "sample";

    @Bean
    @ConditionalOnProperty(prefix = "towndemontower.agents", name = "demo-data",
            havingValue = "true")
    public ApplicationRunner agentSeedRunner(AgentGovernanceFacade facade,
                                             AgentRepository repository,
                                             AgentsProperties props) {
        return args -> {
            if (repository.count() > 0) {
                return; // 已有数据则不播种（file 模式重启时保留）
            }

            ExecutionContext ctx = new ExecutionContext(
                    "agents-seed", "agents-seed", "tenant-default",
                    props.getAgentId(), "agents-seed-owner",
                    false, AgentMode.DEV_EXECUTION);

            if (SEED_MODE_SAMPLE.equalsIgnoreCase(props.getSeedMode())) {
                seedSample(facade, ctx);
                System.out.println("[agents] 演示数据已播种：精简样例 4 个"
                        + "（ACTIVE / PAUSED / SUSPENDED / RETIRED 各 1）");
            } else {
                DefaultAgents.seed(facade, ctx);
                System.out.println("[agents] 演示数据已播种："
                        + DefaultAgents.expectedTotal() + " 个"
                        + "（ACTIVE " + DefaultAgents.expectedActive()
                        + " / PAUSED " + DefaultAgents.expectedPaused()
                        + " / SUSPENDED " + DefaultAgents.expectedSuspended()
                        + " / RETIRED " + DefaultAgents.expectedRetired() + "）");
            }
        };
    }

    /** 精简样例：四种状态各一个。 */
    private static void seedSample(AgentGovernanceFacade facade, ExecutionContext ctx) {
        LocalDateTime base = LocalDateTime.now().minusDays(30).withHour(9).withMinute(0);

        facade.register(ctx, "sample-pm", AgentRole.PM, null, null, null, base, "需求与验收");
        var dev = facade.register(ctx, "sample-dev", AgentRole.DEVELOPER, null, null, null, base, "功能开发");
        var qa = facade.register(ctx, "sample-qa", AgentRole.QA, null, null, null, base, "回归测试");
        var ops = facade.register(ctx, "sample-ops", AgentRole.OPS, null, null, null, base, "部署与监控");

        facade.changeStatus(ctx, dev.id(), AgentStatus.PAUSED);
        facade.changeStatus(ctx, qa.id(), AgentStatus.SUSPENDED);
        facade.changeStatus(ctx, ops.id(), AgentStatus.RETIRED);
    }
}
