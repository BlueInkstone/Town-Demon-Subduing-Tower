package com.towndemontower.agents.governance;

import com.towndemontower.agents.domain.Agent;
import com.towndemontower.agents.service.AgentService;
import com.towndemontower.core.auth.CallerAgentBindings;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * [agents] 启动就绪后的绑定体检（V1.9 / I3-b）。
 *
 * <p>绑定表是静态的、只在启动时读一次；如果它指向一个不存在或非 ACTIVE 的 Agent，
 * 那么<b>每一个</b>请求都会 403，而运维在日志里只能看到一串 `CALLER_AGENT_UNAVAILABLE`。
 * 这个监听器把"哪一条绑定是坏的"提前到启动期一次说清。</p>
 *
 * <p><b>为什么用 {@link ApplicationReadyEvent} 而不是 Bean 初始化</b>：演示数据由
 * {@code ApplicationRunner} 播种，而 Runner 在所有单例创建完之后才跑。在 Bean 构造期做体检，
 * {@code demo-data=true} 时会把"绑定指向 AGT-0001、而 AGT-0001 还没播种"误报成坏绑定。
 * 就绪事件在所有 Runner 之后触发，此时仓储才是最终状态。</p>
 *
 * <p>只告警、不阻止启动：Agent 可能随后由运维补齐，且"启动失败"会让本可以自我修复的
 * 部署彻底起不来。真正的强制点仍在请求路径上（{@link CallerBindingGuard}）。</p>
 */
public class BindingReadinessCheck implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOG = Logger.getLogger(BindingReadinessCheck.class.getName());

    private final CallerAgentBindings bindings;
    private final AgentService service;

    public BindingReadinessCheck(CallerAgentBindings bindings, AgentService service) {
        this.bindings = bindings != null ? bindings : CallerAgentBindings.none();
        this.service = service;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!bindings.strict()) return;
        List<String> bad = unusable();
        if (bad.isEmpty()) {
            LOG.info("[agents] 绑定体检通过：" + bindings.size() + " 条绑定均指向可用（ACTIVE）的 Agent");
            return;
        }
        LOG.severe("[agents] 绑定体检发现 " + bad.size() + " 条不可用绑定，相关调用者将一律 "
                + "403 CALLER_AGENT_UNAVAILABLE：" + bad);
    }

    /**
     * 逐条检查绑定是否指向可用 Agent。
     *
     * @return 不可用项摘要（{@code 调用者→AGT-xxxx(原因)}）；全部可用时为空清单
     */
    public List<String> unusable() {
        List<String> out = new ArrayList<>();
        for (String caller : bindings.callers()) {
            String agentId = bindings.agentFor(caller);
            Optional<Agent> found = service.findById(agentId);
            if (found.isEmpty()) {
                out.add(caller + "→" + agentId + "(Agent 不存在或已注销删除)");
                continue;
            }
            Agent agent = found.get();
            if (!agent.status().canAct()) {
                out.add(caller + "→" + agentId + "(状态 " + agent.status().name() + "，需 ACTIVE)");
            }
        }
        return out;
    }
}
