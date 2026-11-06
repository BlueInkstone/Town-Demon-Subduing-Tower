package com.towndemontower.delivery.web;

import com.towndemontower.core.auth.CallerContext;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.TimeProvider;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.delivery.config.DeliveryProperties;
import com.towndemontower.delivery.domain.DeliveryItem;
import com.towndemontower.delivery.domain.DeliveryStatus;
import com.towndemontower.delivery.governance.DeliveryGovernanceFacade;
import com.towndemontower.delivery.service.DeliveryNotFoundException;
import com.towndemontower.delivery.service.DeliveryValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * [delivery/web] 交付进度看板 REST 接口。
 *
 * <p>接口清单：
 * <ul>
 *   <li>{@code GET    /api/delivery/items} —— 列出交付项；带 {@code ?status=} 按状态筛选；</li>
 *   <li>{@code GET    /api/delivery/items/{id}} —— 查询单个交付项；</li>
 *   <li>{@code POST   /api/delivery/items} —— 新增交付项；</li>
 *   <li>{@code PUT    /api/delivery/items/{id}} —— 更新交付项（部分更新）；</li>
 *   <li>{@code DELETE /api/delivery/items/{id}} —— 删除交付项；</li>
 *   <li>{@code GET    /api/delivery/summary} —— 整体交付进度汇总；</li>
 *   <li>{@code GET    /api/delivery/meta} —— 状态枚举与中文标签（供前端渲染筛选器）。</li>
 * </ul>
 *
 * <p>写操作（POST/PUT/DELETE）经 {@link DeliveryGovernanceFacade} 走 core 装饰器链，
 * 因此会被模式白名单、Schema、RBAC、风险闸门、污点校验、快照与<b>审计哈希链</b>覆盖。</p>
 */
@RestController
@RequestMapping("/api/delivery")
@Tag(name = "交付看板", description = "交付项的增删改查、状态筛选与整体进度汇总")
public class DeliveryController {

    private final DeliveryGovernanceFacade facade;
    private final DeliveryProperties props;
    /**
     * 时间来源（v1.15 / CP10-R4）：Controller 不再为了 {@code now()} 而注入业务 Service。
     */
    private final TimeProvider time;

    /** 装配入口：时间来源默认走系统时钟。 */
    @org.springframework.beans.factory.annotation.Autowired
    public DeliveryController(DeliveryGovernanceFacade facade,
                              DeliveryProperties props) {
        this(facade, props, TimeProvider.system());
    }

    /** 允许测试替换时间来源。 */
    public DeliveryController(DeliveryGovernanceFacade facade,
                              DeliveryProperties props,
                              TimeProvider time) {
        this.facade = facade;
        this.props = props;
        this.time = time == null ? TimeProvider.system() : time;
    }

    /** 列出交付项，可按状态筛选。 */
    @GetMapping("/items")
    @Operation(summary = "列出交付项",
            description = "返回全部交付项，按计划完成时间升序排列（null 排最后）。"
                    + "带 `status` 参数时按状态筛选；状态为服务端实时推导的结果，"
                    + "到期项无需任何写操作即自动变为「已延期」。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功",
                    content = @Content(array = @io.swagger.v3.oas.annotations.media.ArraySchema(
                            schema = @Schema(implementation = DeliveryItemView.class)))),
            @ApiResponse(responseCode = "400", description = "未知状态值")
    })
    public List<DeliveryItemView> list(
            @Parameter(description = "按状态筛选：NOT_STARTED / IN_PROGRESS / DELAYED / COMPLETED；不传则返回全部")
            @RequestParam(required = false) String status) {
        DeliveryStatus filter = parseStatus(status);
        if (status != null && !status.trim().isEmpty() && filter == null) {
            throw new DeliveryValidationException("status",
                    "未知状态：" + status + "（可用：NOT_STARTED/IN_PROGRESS/DELAYED/COMPLETED）");
        }
        Instant now = time.now();
        List<DeliveryItemView> views = new ArrayList<>();
        for (DeliveryItem item : facade.list(ctx(), filter)) {
            views.add(DeliveryItemView.from(item, now));
        }
        return views;
    }

    /** 查询单个交付项。 */
    @GetMapping("/items/{id}")
    @Operation(summary = "查询单个交付项",
            description = "按 id 查询交付项，返回推导后的状态。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功",
                    content = @Content(schema = @Schema(implementation = DeliveryItemView.class))),
            @ApiResponse(responseCode = "404", description = "交付项不存在")
    })
    public DeliveryItemView get(
            @Parameter(description = "交付项 id", example = "DLV-0001") @PathVariable String id) {
        DeliveryItem item = facade.findById(ctx(), id)
                .orElseThrow(() -> new DeliveryNotFoundException(id));
        return DeliveryItemView.from(item, time.now());
    }

    /** 新增交付项。 */
    @PostMapping("/items")
    @Operation(summary = "新增交付项",
            description = "创建一个交付阶段。`phaseName`、`owner`、`plannedStart`、`plannedEnd` 为必填；"
                    + "`progressPercent` 取值 0~100。"
                    + "`status` 可选，用于人工指定状态；传 `\"AUTO\"` 或不传则由服务端自动推导。"
                    + "本接口经过治理内核：可能返回 202（需人工审批，响应含 approvalTicketId）"
                    + "或 403（治理拒绝，响应含 core 拒绝码）。")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "创建成功",
                    content = @Content(schema = @Schema(implementation = DeliveryItemView.class))),
            @ApiResponse(responseCode = "400", description = "字段校验失败"),
            @ApiResponse(responseCode = "403", description = "治理链拒绝"),
            @ApiResponse(responseCode = "202", description = "需人工审批")
    })
    public ResponseEntity<DeliveryItemView> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "交付项内容", content = @Content(
                            schema = @Schema(implementation = DeliveryItemRequest.class)))
            @RequestBody DeliveryItemRequest req) {
        DeliveryStatus override = parseStatus(req.status);
        if (req.status != null && !req.status.trim().isEmpty() && override == null) {
            throw new DeliveryValidationException("status", "未知状态：" + req.status);
        }
        DeliveryItem item = facade.create(ctx(),
                req.phaseName, req.owner, req.plannedStart, req.plannedEnd,
                req.actualEnd, req.progressPercent, override);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(DeliveryItemView.from(item, time.now()));
    }

    /** 更新交付项（部分更新：null 字段保持原值）。 */
    @PutMapping("/items/{id}")
    @Operation(summary = "更新交付项",
            description = "部分更新：只传需要变更的字段，未传（null）的字段保持原值。"
                    + "`status` 传具体枚举值即人工指定；传 `\"AUTO\"` 表示清除人工覆盖、恢复自动推导。"
                    + "更新后服务端会重新校验计划起止时间是否倒挂与进度是否越界。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功",
                    content = @Content(schema = @Schema(implementation = DeliveryItemView.class))),
            @ApiResponse(responseCode = "400", description = "字段校验失败"),
            @ApiResponse(responseCode = "404", description = "交付项不存在"),
            @ApiResponse(responseCode = "403", description = "治理链拒绝"),
            @ApiResponse(responseCode = "202", description = "需人工审批")
    })
    public DeliveryItemView update(
            @Parameter(description = "交付项 id", example = "DLV-0001") @PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "待更新字段（null 表示保持原值）", content = @Content(
                            schema = @Schema(implementation = DeliveryItemRequest.class)))
            @RequestBody DeliveryItemRequest req) {
        boolean clearStatus = DeliveryItemRequest.isClearStatusDirective(req.status);
        DeliveryStatus override = clearStatus ? null : parseStatus(req.status);
        if (req.status != null && !req.status.trim().isEmpty() && override == null && !clearStatus) {
            throw new DeliveryValidationException("status", "未知状态：" + req.status);
        }
        DeliveryItem item = facade.update(ctx(), id,
                req.phaseName, req.owner, req.plannedStart, req.plannedEnd,
                req.actualEnd, req.progressPercent, override, clearStatus);
        return DeliveryItemView.from(item, time.now());
    }

    /** 删除交付项。 */
    @DeleteMapping("/items/{id}")
    @Operation(summary = "删除交付项",
            description = "删除指定交付项。该操作同样经过治理内核并记入审计哈希链。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "交付项不存在"),
            @ApiResponse(responseCode = "403", description = "治理链拒绝")
    })
    public ResponseEntity<Map<String, Object>> delete(
            @Parameter(description = "交付项 id", example = "DLV-0001") @PathVariable String id) {
        facade.delete(ctx(), id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", true);
        body.put("id", id);
        return ResponseEntity.ok(body);
    }

    /** 整体交付进度汇总。 */
    @GetMapping("/summary")
    @Operation(summary = "整体交付进度汇总",
            description = "自动汇总全部交付项：各状态计数、整体进度百分比（等权平均，向下取整）、"
                    + "完成率（已完成数 / 总数）、最早应交付节点与下一个未到期节点、汇总生成时刻。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功",
                    content = @Content(schema = @Schema(implementation = DeliverySummaryView.class)))
    })
    public DeliverySummaryView summary() {
        return DeliverySummaryView.from(facade.summary(ctx()));
    }

    /** 状态枚举元数据（供前端渲染筛选器）。 */
    @GetMapping("/meta")
    @Operation(summary = "获取状态枚举元数据",
            description = "返回状态枚举的 code / 中文标签对，以及服务器当前时间。"
                    + "前端据此渲染筛选下拉框，无需硬编码状态列表。")
    public Map<String, Object> meta() {
        List<Map<String, String>> statuses = new ArrayList<>();
        for (DeliveryStatus s : DeliveryStatus.values()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("code", s.name());
            m.put("label", s.label());
            statuses.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("statuses", statuses);
        body.put("now", time.now().toString());
        return body;
    }

    /**
     * 构造本次请求的治理上下文。
     *
     * <p>看板写入走独立身份 {@code towndemontower.delivery.agent-id}，
     * 该身份在自动配置里被授予 {@code delivery:read} / {@code delivery:write} 权限；
     * 因此这里的 agentId 必须与配置一致，否则写操作会被 RBAC 拒绝。</p>
     *
     * <p>V1.8 起 {@code ownerUserId} 取自认证后的调用者（{@link CallerContext}），
     * 未认证时回落 {@code delivery-board-owner}，行为与之前一致。</p>
     */
    private ExecutionContext ctx() {
        return new ExecutionContext(
                "delivery-board", "delivery-board", "tenant-default",
                props.getAgentId(), CallerContext.currentOr("delivery-board-owner"),
                false, AgentMode.DEV_EXECUTION);
    }

    private static DeliveryStatus parseStatus(String raw) {
        return (raw == null || raw.trim().isEmpty()) ? null : DeliveryStatus.parse(raw);
    }
}
