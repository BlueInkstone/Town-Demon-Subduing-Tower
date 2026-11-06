package com.towndemontower.agents.web;

import com.towndemontower.agents.domain.Agent;
import com.towndemontower.agents.domain.AgentRole;
import com.towndemontower.agents.domain.AgentStatus;
import com.towndemontower.agents.domain.ModelTier;
import com.towndemontower.agents.domain.SkillCatalog;
import com.towndemontower.agents.governance.AgentGovernanceFacade;
import com.towndemontower.agents.governance.CallerBindingGuard;
import com.towndemontower.agents.service.AgentNotFoundException;
import com.towndemontower.agents.service.AgentValidationException;
import com.towndemontower.core.auth.CallerContext;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.TimeProvider;
import com.towndemontower.core.mode.AgentMode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [agents/web] Agent 注册表 REST 接口。
 *
 * <p>接口清单：
 * <ul>
 *   <li>{@code GET    /api/agents} —— 列表；支持 {@code status} / {@code role} / {@code keyword} 过滤；</li>
 *   <li>{@code GET    /api/agents/{id}} —— 查询单个 Agent（含实然权限）；</li>
 *   <li>{@code POST   /api/agents} —— 注册；</li>
 *   <li>{@code PUT    /api/agents/{id}} —— 改档（部分更新）；</li>
 *   <li>{@code PUT    /api/agents/{id}/status} —— 状态流转；</li>
 *   <li>{@code DELETE /api/agents/{id}} —— 注销并删除档案（仅限 RETIRED）；</li>
 *   <li>{@code GET    /api/agents/summary} —— 汇总；</li>
 *   <li>{@code GET    /api/agents/meta} —— 角色/状态/档位枚举与可选技能清单。</li>
 * </ul>
 *
 * <p>写操作经 {@link AgentGovernanceFacade} 走 core 装饰器链，并被 RBAC、风险闸门、
 * 污点校验、快照与审计哈希链覆盖；授权同步由门面在链路成功后完成。</p>
 */
@RestController
@RequestMapping("/api/agents")
@Tag(name = "Agent 注册表", description = "Agent 的注册、改档、状态流转、注销与汇总")
public class AgentController {

    private final AgentGovernanceFacade facade;
    private final CallerBindingGuard bindingGuard;
    private final SkillCatalog skillCatalog;
    /**
     * 时间来源（v1.15 / CP10-R4）：Controller 不再为了 {@code now()} 而注入业务 Service。
     */
    private final TimeProvider time;

    /** 装配入口：时间来源默认走系统时钟。 */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentController(AgentGovernanceFacade facade,
                           CallerBindingGuard bindingGuard,
                           SkillCatalog skillCatalog) {
        this(facade, bindingGuard, skillCatalog, TimeProvider.system());
    }

    /** 允许测试替换时间来源。 */
    public AgentController(AgentGovernanceFacade facade,
                           CallerBindingGuard bindingGuard,
                           SkillCatalog skillCatalog,
                           TimeProvider time) {
        this.facade = facade;
        this.bindingGuard = bindingGuard;
        this.skillCatalog = skillCatalog;
        this.time = time == null ? TimeProvider.system() : time;
    }

    /** 列表，支持状态 / 角色 / 关键字过滤。 */
    @GetMapping
    @Operation(summary = "列出 Agent",
            description = "返回注册表（按 ID 升序）。`status` 按状态过滤，`role` 按角色过滤，"
                    + "`keyword` 在名称 / ID / 角色 / 技能中模糊匹配。"
                    + "每条记录同时返回档案中的应然权限与 RBAC 中实际生效的实然权限。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功", content = @Content(
                    array = @ArraySchema(schema = @Schema(implementation = AgentView.class)))),
            @ApiResponse(responseCode = "400", description = "未知的状态或角色取值")
    })
    public List<AgentView> list(
            @Parameter(description = "状态：ACTIVE / PAUSED / SUSPENDED / RETIRED；不传返回全部")
            @RequestParam(required = false) String status,
            @Parameter(description = "角色：PM / ARCHITECT / DEVELOPER / QA / OPS；不传返回全部")
            @RequestParam(required = false) String role,
            @Parameter(description = "关键字：匹配名称 / ID / 角色 / 技能")
            @RequestParam(required = false) String keyword) {
        AgentStatus statusFilter = parseStatus(status, false);
        AgentRole roleFilter = parseRole(role, false);

        Instant now = time.now();
        List<AgentView> views = new ArrayList<>();
        for (Agent agent : facade.list(ctx(), statusFilter, roleFilter, keyword)) {
            views.add(AgentView.from(agent, now, facade.effectivePermissions(agent.id())));
        }
        return views;
    }

    /** 查询单个 Agent。 */
    @GetMapping("/{id}")
    @Operation(summary = "查询单个 Agent",
            description = "按 ID 查询，返回档案、实然权限与账实一致性标记。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功",
                    content = @Content(schema = @Schema(implementation = AgentView.class))),
            @ApiResponse(responseCode = "404", description = "Agent 不存在")
    })
    public AgentView get(
            @Parameter(description = "Agent ID", example = "AGT-0001") @PathVariable String id) {
        Agent agent = facade.findById(ctx(), id)
                .orElseThrow(() -> new AgentNotFoundException(id));
        return AgentView.from(agent, time.now(), facade.effectivePermissions(agent.id()));
    }

    /** 注册 Agent。 */
    @PostMapping
    @Operation(summary = "注册 Agent",
            description = "登记一个 Agent 并分配 ID（AGT-0001 起）。`name` 与 `role` 必填。"
                    + "不传 `skills` / `permissions` / `modelTier` 时自动套用角色模板；"
                    + "传空数组表示明确不配。注册成功后会立即把权限授予 core RBAC。"
                    + "本接口经过治理内核：可能返回 202（需人工审批）或 403（治理拒绝）。")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "注册成功",
                    content = @Content(schema = @Schema(implementation = AgentView.class))),
            @ApiResponse(responseCode = "400", description = "字段校验失败（含名称重复、技能不存在、权限格式非法）"),
            @ApiResponse(responseCode = "403", description = "治理链拒绝"),
            @ApiResponse(responseCode = "202", description = "需人工审批")
    })
    public ResponseEntity<AgentView> register(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Agent 档案", content = @Content(schema = @Schema(implementation = AgentRequest.class)))
            @RequestBody AgentRequest req) {
        AgentRole role = parseRole(req.role, true);
        ModelTier tier = parseTier(req.modelTier);
        Agent agent = facade.register(ctx(), req.name, role, req.skills, req.permissions,
                tier, req.registeredAt, req.note);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AgentView.from(agent, time.now(), facade.effectivePermissions(agent.id())));
    }

    /** 改档（部分更新）。 */
    @PutMapping("/{id}")
    @Operation(summary = "改档",
            description = "部分更新：只传需要变更的字段，未传（null）的字段保持原值；"
                    + "清单字段传空数组表示清空。改档成功后按新权限重新同步 RBAC"
                    + "（覆盖式授予，不会残留原角色的权限）。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "改档成功",
                    content = @Content(schema = @Schema(implementation = AgentView.class))),
            @ApiResponse(responseCode = "400", description = "字段校验失败"),
            @ApiResponse(responseCode = "404", description = "Agent 不存在"),
            @ApiResponse(responseCode = "403", description = "治理链拒绝")
    })
    public AgentView update(
            @Parameter(description = "Agent ID", example = "AGT-0001") @PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "待更新字段（null 表示保持原值）",
                    content = @Content(schema = @Schema(implementation = AgentRequest.class)))
            @RequestBody AgentRequest req) {
        AgentRole role = parseRole(req.role, false);
        ModelTier tier = parseTier(req.modelTier);
        Agent agent = facade.update(ctx(), id, req.name, role, req.skills, req.permissions,
                tier, req.registeredAt, req.note);
        return AgentView.from(agent, time.now(), facade.effectivePermissions(agent.id()));
    }

    /** 状态流转。 */
    @PutMapping("/{id}/status")
    @Operation(summary = "变更状态",
            description = "ACTIVE ⇄ PAUSED ⇄ SUSPENDED → RETIRED。`SUSPENDED` / `RETIRED` 会立即整员回收 RBAC 权限，"
                    + "`ACTIVE` 会按档案重新授予。RETIRED 为终态，不能改回；如需重新启用请分配新 ID。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "变更成功",
                    content = @Content(schema = @Schema(implementation = AgentView.class))),
            @ApiResponse(responseCode = "400", description = "状态非法或已注销 Agent 不能变更状态"),
            @ApiResponse(responseCode = "404", description = "Agent 不存在"),
            @ApiResponse(responseCode = "403", description = "治理链拒绝")
    })
    public AgentView changeStatus(
            @Parameter(description = "Agent ID", example = "AGT-0001") @PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "目标状态", content = @Content(schema = @Schema(implementation = AgentRequest.class)))
            @RequestBody AgentRequest req) {
        AgentStatus target = parseStatus(req.status, true);
        Agent agent = facade.changeStatus(ctx(), id, target);
        return AgentView.from(agent, time.now(), facade.effectivePermissions(agent.id()));
    }

    /** 注销并删除档案。 */
    @DeleteMapping("/{id}")
    @Operation(summary = "注销并删除档案",
            description = "仅允许对**已注销（RETIRED）**的 Agent 删除档案，以保留审计线索。"
                    + "直接删除其他状态的 Agent 会被拒绝（400），请先变更状态。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已删除"),
            @ApiResponse(responseCode = "400", description = "Agent 尚未注销，不能删除"),
            @ApiResponse(responseCode = "404", description = "Agent 不存在"),
            @ApiResponse(responseCode = "403", description = "治理链拒绝")
    })
    public ResponseEntity<Map<String, Object>> delete(
            @Parameter(description = "Agent ID", example = "AGT-0007") @PathVariable String id) {
        facade.delete(ctx(), id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", true);
        body.put("id", id);
        return ResponseEntity.ok(body);
    }

    /** 汇总。 */
    @GetMapping("/summary")
    @Operation(summary = "汇总",
            description = "自动汇总：总数与各状态数量、可执行数量、未注销 Agent 的角色分布、"
                    + "技能覆盖（每项技能多少未注销 Agent 掌握）、平均技能/权限数、权限覆盖率。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功",
                    content = @Content(schema = @Schema(implementation = AgentSummaryView.class)))
    })
    public AgentSummaryView summary() {
        return AgentSummaryView.from(facade.summary(ctx()));
    }

    /** 枚举与技能清单元数据。 */
    @GetMapping("/meta")
    @Operation(summary = "获取枚举与技能清单",
            description = "返回角色、状态、模型档位的 code/label 对，以及系统当前可用的工具清单"
                    + "（来自 core 工具注册表）。")
    public Map<String, Object> meta() {
        List<Map<String, Object>> roles = new ArrayList<>();
        for (AgentRole r : AgentRole.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.name());
            m.put("short", r.code());
            m.put("label", r.label());
            m.put("duty", r.duty());
            roles.add(m);
        }
        List<Map<String, Object>> statuses = new ArrayList<>();
        for (AgentStatus s : AgentStatus.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", s.name());
            m.put("label", s.label());
            m.put("canAct", s.canAct());
            statuses.add(m);
        }
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (ModelTier t : ModelTier.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", t.name());
            m.put("label", t.label());
            tiers.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roles", roles);
        body.put("statuses", statuses);
        body.put("modelTiers", tiers);
        body.put("skills", new ArrayList<>(skillCatalog.all()));
        body.put("now", time.now().toString());
        return body;
    }

    /**
     * 构造本次请求的治理上下文。
     *
     * <p><b>agentId（治理主体）</b>：V1.9 / I3-b 起取自调用者的绑定
     * （{@link CallerBindingGuard}）。绑定命中则用绑定的 Agent——RBAC 按它裁决、
     * 审计按它归属；未绑定（loopback / auth-mode=off / 空绑定表）才回落到配置身份
     * {@code towndemontower.agents.agent-id}，该身份在自动配置里被授予
     * {@code agents:read} / {@code agents:write}。</p>
     *
     * <p><b>ownerUserId（调用者）</b>：V1.8 起取自认证后的调用者（{@link CallerContext}），
     * 未认证时回落 {@code agents-api-owner}。</p>
     *
     * <p>两者分工：{@code agentId} 决定"能做什么"，{@code ownerUserId} 记录"谁在调用"。
     * 绑定生效后，一个持令牌的服务账号不再自动拥有注册表身份的全部权限——它只有自己
     * 那个 Agent 的权限。这是 I3-b 真正想关掉的口子。</p>
     */
    private ExecutionContext ctx() {
        return new ExecutionContext(
                "agents-api", "agents-api", "tenant-default",
                bindingGuard.resolveAgentId(), CallerContext.currentOr("agents-api-owner"),
                false, AgentMode.ARCHITECT_PLANNING);
    }

    /** 解析状态；required=true 时不允许为空，非法值统一抛 400。 */
    private static AgentStatus parseStatus(String raw, boolean required) {
        if (raw == null || raw.trim().isEmpty()) {
            if (required) throw new AgentValidationException("status", "状态不能为空");
            return null;
        }
        AgentStatus st = AgentStatus.parse(raw);
        if (st == null) {
            throw new AgentValidationException("status",
                    "未知状态：" + raw + "（可用：ACTIVE/PAUSED/SUSPENDED/RETIRED）");
        }
        return st;
    }

    /** 解析角色；required=true 时不允许为空。 */
    private static AgentRole parseRole(String raw, boolean required) {
        if (raw == null || raw.trim().isEmpty()) {
            if (required) throw new AgentValidationException("role", "角色不能为空");
            return null;
        }
        AgentRole role = AgentRole.parse(raw);
        if (role == null) {
            throw new AgentValidationException("role",
                    "未知角色：" + raw + "（可用：PM/ARCHITECT/DEVELOPER/QA/OPS）");
        }
        return role;
    }

    /** 解析模型档位（可空）。 */
    private static ModelTier parseTier(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        ModelTier tier = ModelTier.parse(raw);
        if (tier == null) {
            throw new AgentValidationException("modelTier",
                    "未知模型档位：" + raw + "（可用：STRONG/FAST/SMALL/GUARDIAN）");
        }
        return tier;
    }
}
