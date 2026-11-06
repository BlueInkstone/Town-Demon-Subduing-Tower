package com.towndemontower.delivery.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.servers.Server;

import java.util.List;

/**
 * [delivery] OpenAPI 3 契约元信息配置。
 *
 * <p>产出三类接口文档产物（均由 springdoc 自动生成，无需手写 YAML）：</p>
 * <ul>
 *   <li>{@code /v3/api-docs} —— OpenAPI 3 JSON 契约（机器可读，可导入 Postman / Apifox / 客户端生成器）；</li>
 *   <li>{@code /v3/api-docs.yaml} —— 同一契约的 YAML 形式；</li>
 *   <li>{@code /swagger-ui.html} —— Swagger UI 可交互文档（Try it out 可直接发起真实请求）。</li>
 * </ul>
 *
 * <p><b>为什么放在 delivery 模块而不是 starter</b>：starter 是纯治理适配层，
 * 不含任何 HTTP 端点，生成 OpenAPI 契约没有内容可生成；
 * 交付看板是本项目目前唯一暴露 REST 接口的模块，因此契约文档挂在这里。
 * 未来其他模块若暴露接口，把各自的 {@code OpenAPI} Bean 合并进去即可
 * （springdoc 支持多个 {@code OpenAPI} Bean 的分组合并）。</p>
 *
 * <p><b>条件装配</b>：仅当 classpath 上存在 {@code io.swagger.v3.oas.models.OpenAPI}
 * （即引入了 springdoc）时才装配，因此本配置类不会给未引入 springdoc 的消费方带来任何影响。</p>
 */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
@ConditionalOnProperty(prefix = "towndemontower.delivery.openapi",
        name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryOpenApiConfiguration {

    /**
     * OpenAPI 契约根定义：标题、版本、描述、服务端点、标签分组。
     */
    @Bean
    public OpenAPI deliveryOpenApi(DeliveryProperties props) {
        return new OpenAPI()
                .info(new Info()
                        .title("Town Demon-Subduing Tower · 交付进度看板 API")
                        .version("v1.3")
                        .description("""
                                交付进度看板的 REST 接口契约。

                                **核心概念**

                                - **交付项（DeliveryItem）**：一个交付阶段，字段包含阶段名称、负责人、
                                  计划开始时间、计划完成时间、实际完成时间、当前进度百分比。
                                - **状态（status）**：**推导字段**，不落库，由服务端结合当前时间实时计算，
                                  取值为 `NOT_STARTED`（未开始）/ `IN_PROGRESS`（进行中）/
                                  `DELAYED`（已延期）/ `COMPLETED`（已完成）。
                                  推导规则（优先级从高到低）：
                                  人工指定的状态 > 已完成（进度 100 或已填实际完成时间）>
                                  已延期（当前时间晚于计划完成时间）> 进行中（进度 > 0 或已到计划开始时间）> 未开始。
                                - **人工覆盖（statusOverride）**：可在请求体中用 `status` 字段显式指定状态；
                                  传 `"AUTO"` 表示清除人工覆盖、恢复自动推导。

                                **治理说明**：所有写操作（POST / PUT / DELETE）都会经过项目治理内核
                                （风险闸门 + 权限校验 + 审计哈希链），因此响应中可能返回
                                `202`（需人工审批，携带审批单号）或 `403`（治理拒绝）。

                                **错误响应格式**：统一为 `{ "code": "...", "message": "...", "field": "..." }`。
                                """)
                        .contact(new Contact().name("Town Demon-Subduing Tower"))
                        .license(new License().name("Internal Use")))
                .servers(List.of(new Server()
                        .url("/")
                        .description("当前服务实例")))
                .components(new Components()
                        .addSecuritySchemes("delivery-agent",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Agent-Id")
                                        .description("看板 RBAC 主体标识；对应配置项 towndemontower.delivery.agent-id")))
                .addSecurityItem(new SecurityRequirement().addList("delivery-agent"));
    }

    /**
     * OpenAPI 标签分组：让 Swagger UI 按业务语义把端点归类，而不是平铺。
     */
    @Bean
    public io.swagger.v3.oas.models.tags.Tag deliveryApiTag() {
        return new io.swagger.v3.oas.models.tags.Tag()
                .name("交付看板")
                .description("交付项的增删改查、状态筛选与整体进度汇总");
    }
}
