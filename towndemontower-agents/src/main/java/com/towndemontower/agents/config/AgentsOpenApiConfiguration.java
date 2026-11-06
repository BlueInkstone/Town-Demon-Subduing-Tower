package com.towndemontower.agents.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * [agents] OpenAPI 3 契约元信息。
 *
 * <p>产出 {@code /v3/api-docs}（JSON）、{@code /v3/api-docs.yaml}、{@code /swagger-ui.html}，
 * 全部由 springdoc 在运行时根据 Controller 与 {@code @Schema} 注解生成，无需手写契约文件。</p>
 */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
@ConditionalOnProperty(prefix = "towndemontower.agents.openapi",
        name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentsOpenApiConfiguration {

    @Bean
    public OpenAPI agentsOpenApi(AgentsProperties props) {
        return new OpenAPI()
                .info(new Info()
                        .title("Town Demon-Subduing Tower · Agent 注册表 API")
                        .version("v1.4")
                        .description("""
                                Agent 注册表的 REST 接口契约。

                                **核心概念**

                                - **Agent**：一个受治理的执行主体，登记有 ID（`AGT-0001`）、名称、角色、
                                  技能、权限与状态。ID 同时是治理链中的 `agentId`。
                                - **角色（role）**：`PM` / `ARCHITECT` / `DEVELOPER` / `QA` / `OPS`，
                                  与产品交付流程一致。注册时不传 `skills` / `permissions` 即自动套用角色模板。
                                - **状态（status）**：`ACTIVE`（可用，可执行）/
                                  `PAUSED`（暂停，不可执行但保留权限）/
                                  `SUSPENDED`（停用，不可执行且回收权限）/
                                  `RETIRED`（已注销，终态，权限永久回收）。

                                **权限闭环**：Agent 档案是 RBAC 的权威来源。注册与改档会把档案中的权限
                                授予 core `PermissionEngine`；停用或注销会**立即整员回收**。
                                档案里的 `permissions` 是「应然」，接口返回的 `effectivePermissions` 是「实然」，
                                两者不一致说明授权同步出了问题。

                                **治理说明**：所有写操作都会经过风险闸门 + 权限校验 + 审计哈希链，
                                因此可能返回 `202`（需人工审批）或 `403`（治理拒绝）。

                                本模块只提供 REST 接口，不包含页面。
                                """)
                        .contact(new Contact().name("Town Demon-Subduing Tower"))
                        .license(new License().name("Internal Use")))
                .servers(List.of(new Server().url("/").description("当前服务实例")))
                .components(new Components()
                        .addSecuritySchemes("agents-api",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Agent-Id")
                                        .description("注册表 RBAC 主体标识；对应配置项 towndemontower.agents.agent-id")))
                .addSecurityItem(new SecurityRequirement().addList("agents-api"));
    }

    @Bean
    public io.swagger.v3.oas.models.tags.Tag agentsApiTag() {
        return new io.swagger.v3.oas.models.tags.Tag()
                .name("Agent 注册表")
                .description("Agent 的注册、改档、状态流转、注销与汇总");
    }
}
