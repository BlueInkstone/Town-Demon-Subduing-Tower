# Phase 1.4 · Agent 注册表（v1.4）

> **一句话**：把治理链里的 `agentId` 从裸字符串变成有档案的注册对象——
> 有 ID、角色、技能、权限、状态；身份即权限主体，状态变更即权限授予/回收。
> **本期只做单个 Agent 的注册与管理**：不做团队、不做汇报关系、不做编排；
> **只提供 REST 接口，不提供页面**。

- 交付模块：`towndemontower-agents`（包 `com.towndemontower.agents`）
- 接口根路径：`/api/agents/**`
- 接口文档：`http://127.0.0.1:8081/swagger-ui.html`

---

## 1. PM（需求）

### 1.1 背景：治理内核已经有"链"，但缺"主体"

前三个版本把治理链做到了能跑：装饰器链、RBAC、风险闸门、审计哈希链、沙箱、事务一致性。
但链上的主体身份 `ExecutionContext.agentId` 一直是一个**裸字符串**——
交付看板里写死 `delivery-board-agent`，starter 里由调用方随便传。

带来三个实际问题：

1. **谁在干活说不清**：`agentId` 是 `"agent-1"` 还是 `"delivery-board-agent"`，
   没人知道它对应什么角色、能调哪些工具。
2. **权限没有权威来源**：`PermissionEngine.grant()` 散落在各处装配代码里，
   没有"这个主体应该有哪些权限"的唯一答案。
3. **只授不撤**：人员或主体下线后，旧 ID 仍然能通过鉴权。

### 1.2 边界

| 做 | 不做 |
|---|---|
| Agent 档案：ID、名称、角色、技能、权限、模型档位、注册时间、备注 | 团队 / 部门 / 汇报关系 |
| 状态流转：ACTIVE ⇄ PAUSED ⇄ SUSPENDED → RETIRED | 多 Agent 协作、任务派发、编排引擎 |
| 技能配置（必须存在于 core 工具注册表） | 技能市场 / 技能编排 |
| 权限配置，且与 core RBAC 双向对账 | 权限审批流（沿用 R1 直接放行） |
| 汇总：数量、状态分布、角色分布、技能覆盖、权限覆盖率 | 绩效考核、工时统计 |
| 注册 / 改档 / 状态流转 / 注销全部走治理链 | **任何页面或可视化界面** |

> **范围声明**：本模块只暴露 REST 接口。早期版本曾附带一个单页看板，
> 但那不在需求范围内，已移除（`AgentWebTest` 有回归断言守住这一点）。

### 1.3 用户故事

| 编号 | 故事 |
|---|---|
| US-1 | 注册一个 Agent 时，系统按角色自动配好默认技能与权限，不用一项项勾。 |
| US-2 | 我想看到每个 Agent 当前**真正生效**的权限，而不只是档案里写的配置。 |
| US-3 | 某个 Agent 停用时，权限立刻失效，而不是等我手工去删。 |
| US-4 | Agent 改档后，旧角色的权限被清掉，不要越攒越多。 |
| US-5 | 我想按状态 / 角色 / 关键字筛，并看到整体分布与权限覆盖率。 |
| US-6 | 每一次档案变更与状态变更都要能在审计哈希链里查到。 |

### 1.4 验收标准

| 编号 | 验收项 | 判定方式 |
|---|---|---|
| AC-1 | 注册返回 ID `AGT-0001` 起，未传技能/权限时按角色模板自动配置 | `AgentWebTest` |
| AC-2 | 档案权限会真实授予 core RBAC，接口返回 `effectivePermissions` | `AgentAccessTest` |
| AC-3 | SUSPENDED / RETIRED 后 RBAC 权限被**整员回收**，档案配置保留 | `AgentAccessTest` |
| AC-4 | 改档为覆盖式授权，旧角色权限不残留 | `AgentAccessTest` |
| AC-5 | 技能必须存在于 core 工具注册表，拼错立即 400 | `AgentDomainTest` / `AgentWebTest` |
| AC-6 | RETIRED 为终态不可恢复；删除仅允许对 RETIRED | `AgentWebTest` |
| AC-7 | 所有写操作进入共享审计哈希链且链可校验 | `AgentAccessTest` / `AgentWebTest` |
| AC-8 | 注册表工具不污染 starter 主链的模式白名单 | `AgentWebTest` |
| AC-9 | **不提供页面**：`/agents/index.html` 与 `/` 均返回 404 | `AgentWebTest` |

---

## 2. Arch（架构）

### 2.1 模块位置

```
towndemontower-core                治理内核
        ▲
towndemontower-spring-starter      Spring 薄适配层（复用其治理组件 Bean）
        ▲
towndemontower-agents              ★ 本期：Agent 注册表
        │  （与 towndemontower-delivery 平级，互不依赖）
```

### 2.2 领域模型

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String | `AGT-0001`，**同时是治理链的 `agentId`**；创建后不可改 |
| `name` | String | 显示名，注册表内唯一（忽略大小写），1~32 字符 |
| `role` | enum | `PM` / `ARCHITECT` / `DEVELOPER` / `QA` / `OPS` |
| `status` | enum | `ACTIVE` / `PAUSED` / `SUSPENDED` / `RETIRED` |
| `skills` | List\<String\> | 可用工具名，必须是 core `SkillRegistry` 中真实存在的 key |
| `permissions` | List\<String\> | RBAC 权限，格式 `域:动作`，如 `delivery:write` |
| `modelTier` | enum | `STRONG` / `FAST` / `SMALL` / `GUARDIAN`，与 core `ModelProfile.capability` 对齐 |
| `registeredAt` | LocalDateTime | 注册时间 |
| `note` | String | 备注，≤120 字符 |
| `createdAt` / `updatedAt` | Instant | 创建与最后变更时刻 |

**角色模板**（`RoleTemplate`）：每个角色一份"恰好够用"的默认技能与权限。
若注册时每次都要人工勾十几个权限，最后大家都会全选，最小权限原则反而失效。

| 角色 | 默认技能 | 默认权限 | 默认档位 |
|---|---|---|---|
| PM | splitTask, writeDesign | plan:write, delivery:read, delivery:write, agents:read | FAST |
| ARCHITECT | writeDesign, splitTask, readDiff | plan:write, review:read, agents:read | **STRONG** |
| DEVELOPER | writeFile, runCompile, readLog | fs:write, build:run, log:read, agents:read | FAST |
| QA | runRuleScan, readTestReport, readDiff | review:read, log:read, agents:read | FAST |
| OPS | restartService, deployService, readLog | ops:restart, ops:deploy, log:read, agents:read | FAST |

> 模板引用的技能必须真实存在，`AgentDomainTest` 有一条用例遍历五个角色做校验。

### 2.3 状态机

```
       ┌──────── 恢复 ────────┐
       ▼                      │
   ┌────────┐   暂停   ┌──────────┐
   │ ACTIVE │ ───────► │  PAUSED  │
   └────────┘ ◄─────── └──────────┘
       │ 停用             │
       ▼                  ▼
   ┌───────────┐ ◄────────┘
   │ SUSPENDED │
   └───────────┘
       │ 注销（终态，不可逆）
       ▼
   ┌───────────┐
   │  RETIRED  │ ──► 可删除档案
   └───────────┘
```

| 状态 | 能否执行（`canAct`） | 是否保留权限（`retainsPermissions`） |
|---|---|---|
| ACTIVE | ✅ | ✅ |
| PAUSED | ❌ | ✅（恢复时无需重新授权） |
| SUSPENDED | ❌ | ❌（立即回收） |
| RETIRED | ❌ | ❌（永久回收） |

### 2.4 权限闭环：应然 vs 实然

```
      Agent 档案（应然：应该有哪些权限）
            │
            │  AgentGovernanceFacade 在每次成功变更后同步
            ▼
    AccessProvisioner ──► CorePermissionProvisioner
            │                          │
            ▼                          ▼
    core PermissionEngine（实然：现在真正生效什么）
                                       │
                                       ▼
                            装饰器链 PermissionDecorator 的判定依据
```

- **档案保存"应然"**：SUSPENDED / RETIRED 后**不清空**，便于恢复，也保留审计线索。
- **RBAC 是"实然"**：SUSPENDED / RETIRED **立即整员回收**。
- 接口同时返回两者，`permissionsInSync` 标出账实是否一致
  （"应然保留、实然为空"对这两种状态是**正常**的）。

同步规则：

| 操作 | 状态 | 动作 |
|---|---|---|
| 注册 / 改档 / 状态变更 | 保留权限（ACTIVE / PAUSED） | `grant()` 覆盖式授予（先回收再授） |
| 同上 | 不保留权限（SUSPENDED / RETIRED） | `revokeAll()` 整员回收 |
| 删除档案 | — | `revokeAll()`，再删 |

### 2.5 关键决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 只做 Agent，不做团队 | `role` 用枚举而非 `teamId` | 团队会引出权限继承、审批路由、配额、任务交接，成本远超本期 |
| 角色枚举 vs 自由文本 | 枚举 + 中文标签 + 短代码 | 自由文本会让"配什么权限"失去确定答案 |
| ID 可否修改 | 不可改 | ID 是 RBAC 主体键，改号等于换身份，旧审计记录会失去归属 |
| 注销可否逆 | 不可逆（终态） | 复用 ID 会与"同一个 ID 两段历史"混淆，重新启用请发新 ID |
| 权限以档案为准 | 是（端口预留 IAM 适配） | 接 LDAP/飞书/企业微信只需换 `AccessProvisioner` 实现 |
| 删除约束 | 必须先 RETIRED | 直接删可用状态的 Agent 会抹掉"曾有哪些权限"的线索 |
| 授权同步放哪层 | 治理门面（唯一必经入口） | 放领域服务会绑死 core；放 Controller 会被 SDK / 定时任务绕过 |
| 危险动作风险级 | 全部 R1 | 演示要能走通；生产建议注销升 R2 + 双人复核 |
| 是否提供页面 | **不提供** | 需求只要求 Agent 对象的管理能力；界面属于额外发挥，已移除 |

---

## 3. Dev（实现）

### 3.1 文件清单

```
towndemontower-agents/src/main/java/com/towndemontower/agents/
├── AgentsApplication.java                 可独立启动的演示入口（demo 8081）
├── domain/
│   ├── Agent.java                         档案（不可变值对象）
│   ├── AgentRole.java                     角色枚举（PM/ARCH/DEV/QA/OPS）
│   ├── AgentStatus.java                   状态枚举（canAct / retainsPermissions）
│   ├── ModelTier.java                     模型档位（对齐 ModelProfile.capability）
│   ├── RoleTemplate.java                  角色默认技能 / 权限 / 档位
│   ├── AgentRepository.java               仓储抽象
│   ├── InMemoryAgentRepository.java       内存实现（ID 自增，跳过已占用号）
│   ├── FileAgentRepository.java           文件实现（纯文本行存储）
│   ├── AgentSummary.java                  汇总模型
│   ├── AgentSummaryCalculator.java        汇总计算（纯函数）
│   ├── SkillCatalog.java                  技能目录端口
│   └── AccessProvisioner.java             权限开通端口
├── service/
│   ├── AgentService.java                  注册 / 改档 / 状态流转 / 删除 / 筛选 / 汇总
│   ├── AgentValidationException.java      字段校验失败（400）
│   ├── AgentNotFoundException.java        不存在（404）
│   └── AgentInactiveException.java        状态不允许执行（409）
├── governance/
│   ├── AgentGovernanceFacade.java         治理门面（装饰器链 + 权限同步）
│   ├── AgentsModeToolPolicy.java          专属模式白名单（非 Bean）
│   ├── AgentSkillDescriptors.java         5 个工具的风险/权限/必填参数声明
│   ├── AgentGovernanceException.java      治理拒绝（403 / 202）
│   ├── CorePermissionProvisioner.java     权限端口 → core PermissionEngine
│   └── RegistrySkillCatalog.java          技能目录端口 → core SkillRegistry
├── seed/
│   ├── DefaultAgents.java                 演示用 Agent 基线（7 个）
│   └── AgentSeedInitializer.java          播种器（仅 demo-data=true）
├── web/
│   ├── AgentController.java               REST 接口 + OpenAPI 注解
│   ├── AgentRequest.java / AgentView.java / AgentSummaryView.java
│   └── AgentExceptionHandler.java         400 / 404 / 409 / 403 / 202 映射
└── config/
    ├── AgentsAutoConfiguration.java       条件装配
    ├── AgentsOpenApiConfiguration.java    OpenAPI 元信息
    └── AgentsProperties.java              配置属性
```

**没有** `static/` 目录，**没有**页面控制器。

### 3.2 REST 契约

| 方法 | 路径 | 说明 | 成功码 |
|---|---|---|---|
| GET | `/api/agents` | 列表；`?status=` `?role=` `?keyword=` 过滤 | 200 |
| GET | `/api/agents/{id}` | 单个（含实然权限） | 200 / 404 |
| POST | `/api/agents` | 注册（`name`、`role` 必填） | 201 / 400 / 403 / 202 |
| PUT | `/api/agents/{id}` | 改档（部分更新；清单传 `[]` 表示清空） | 200 / 400 / 404 / 403 |
| PUT | `/api/agents/{id}/status` | 状态流转，body `{"status":"SUSPENDED"}` | 200 / 400 / 404 / 403 |
| DELETE | `/api/agents/{id}` | 删除档案（仅限 RETIRED） | 200 / 400 / 404 / 403 |
| GET | `/api/agents/summary` | 汇总 | 200 |
| GET | `/api/agents/meta` | 角色/状态/档位枚举 + 可选技能清单 | 200 |

错误响应：

```json
{ "error": "VALIDATION_FAILED", "field": "name", "message": "名称不能为空" }
{ "error": "NOT_FOUND", "id": "AGT-9999", "message": "Agent 不存在：AGT-9999" }
{ "error": "PERMISSION_DENIED", "message": "..." }
```

### 3.3 清单字段的三态语义

| 请求中的值 | 注册（POST） | 改档（PUT） |
|---|---|---|
| 不传 / `null` | 套用角色模板 | **保持原值** |
| `[]` | 明确不配置 | **清空** |
| `["a","b"]` | 覆盖 | 覆盖 |

### 3.4 校验规则

| 字段 | 规则 | 失败码 |
|---|---|---|
| `name` | 非空、≤32 字符、注册表内唯一（忽略大小写） | 400 |
| `role` | 必填，5 个角色之一 | 400 |
| `skills` | 每项必须存在于 core `SkillRegistry` | 400（列出全部非法项） |
| `permissions` | 匹配 `^[a-z][a-z0-9-]*:[a-z][a-z0-9*-]*$` | 400 |
| `note` | ≤120 字符 | 400 |
| 状态流转 | RETIRED 为终态，不可改回 | 400 |
| 删除 | 仅 RETIRED 可删 | 400 |

### 3.5 治理接入

链路（外→内）：

```
Audit → ModeToolPolicy → IntentFormat → Schema → Capability
      → Permission → RiskGate → TaintCheck → Snapshot → Sandbox
```

| 工具 | 风险 | 所需权限 | 必填参数 |
|---|---|---|---|
| `agents.registerAgent` | R1 | `agents:write` | name, role |
| `agents.updateAgent` | R1 | `agents:write` | id |
| `agents.changeStatus` | R1 | `agents:write` | id, status |
| `agents.deleteAgent` | R1 | `agents:write` | id |
| `agents.query` | R0 | `agents:read` | — |

**白名单隔离**：`AgentsModeToolPolicy` 是模块内独立实例，**不注册为 Spring Bean**。
若注册，容器里会出现两个 `ModeToolPolicy`，starter 按类型注入产生歧义；
更糟的是本模块 Bean 若先创建，starter 的 `@ConditionalOnMissingBean` 会误判而放弃注册，
主链白名单就被注册表工具污染。两条测试用例专门守这个坑。

**业务异常穿透**：`AuditDecorator` 会把链内 `RuntimeException` 统一转成 `INTERNAL_ERROR`。
"字段校验失败 / 不存在"是正常业务语义（400/404），不能降级为治理拒绝。
门面把它们包成 `BusinessError` 哨兵穿出链外再还原——既保证审计留痕，又不丢 400/404。

### 3.6 演示数据

`DefaultAgents` 覆盖四状态五角色，状态**刻意做得不整齐**：

| ID | 名称 | 角色 | 状态 |
|---|---|---|---|
| AGT-0001 | planner | PM | ACTIVE |
| AGT-0002 | architect | ARCHITECT | ACTIVE（强推理档） |
| AGT-0003 | dev-core | DEVELOPER | ACTIVE |
| AGT-0004 | dev-ui | DEVELOPER | **PAUSED**（权限保留、不可执行） |
| AGT-0005 | qa-gate | QA | **SUSPENDED**（档案留权限、RBAC 已回收） |
| AGT-0006 | ops-deploy | OPS | ACTIVE |
| AGT-0007 | ops-ci | OPS | **RETIRED**（ID 封存、权限永久回收） |

播种**走治理门面**：否则权限同步不会发生，汇总里的覆盖率会显示为 0。

---

## 4. QA（验证）

| 层 | 测试类 | 用例数 | 覆盖 |
|---|---|---|---|
| 领域 + 服务 | `AgentDomainTest` | 78 | 值对象、状态语义、角色模板、汇总口径、注册校验、改档、状态机、删除约束、筛选 |
| 治理 + 权限 | `AgentAccessTest` | 50 | 注册授予、停用回收、恢复、改档覆盖式、注销回收、执行守卫、越权拒绝、业务异常穿透、审计链、关闭治理仍同步、演示数据口径 |
| 端到端 | `AgentWebTest` | 73 | 真实 HTTP：8 个接口、筛选、汇总、账实一致性、校验 400、404、删除约束、审计链、注册表与白名单隔离、**确认无页面** |
| **小计** | | **201** | |

### 全仓回归

| 模块 | 测试 | 结果 |
|---|---|---|
| core | CoreTest / V12FeatureTest / V121DockerSandboxTest | 34 / 30 / 14 = **78** |
| starter | StarterDemo / QuickstartSmokeTest | 5 / 6 = **11** |
| delivery | Domain / Roadmap / OpenApi / Web | 79 / 43 / 41 / 56 = **219** |
| agents | Domain / Access / Web | 78 / 50 / 73 = **201** |
| **合计** | | **509 / 509** |

---

## 5. Ops（运行）

### 5.1 启动

```bash
# 依赖模块装到本地仓库（core 有改动时先重装）
cd towndemontower-core && mvn -o -Dmaven.test.skip=true install
cd ../towndemontower-spring-starter && mvn -o -Dmaven.test.skip=true install

# 启动（demo profile：8081 + 播种演示数据）
cd ../towndemontower-agents
mvn -o spring-boot:run -Dspring-boot.run.profiles=demo
```

> 若 shell 里存在 `SERVER__PORT`，Spring 松散绑定的优先级高于 `application-demo.properties`，
> 需要用命令行覆盖：`--server.port=8081`。

### 5.2 配置项（`towndemontower.agents.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 模块总开关 |
| `governed` | `true` | 是否接入治理链（关闭后仍同步权限，只是不进审计/闸门） |
| `agent-id` | `agents-api-agent` | 注册表自身的 RBAC 主体 |
| `storage` | `memory` | `memory` 或 `file` |
| `data-file` | `agents.data` | `storage=file` 时的路径 |
| `demo-data` | `false` | 是否播种演示数据 |
| `seed-mode` | `default` | `default` / `sample` |

### 5.3 验证路径（仅接口）

```bash
# 汇总：7 个 / ACTIVE 4 / 可执行 4 / 权限覆盖率 100%
curl -s http://127.0.0.1:8081/api/agents/summary

# 停用与已注销的实然权限为空，档案权限仍在
curl -s "http://127.0.0.1:8081/api/agents?status=SUSPENDED"

# 注册一个 Agent，不传 skills/permissions 即套用角色模板
curl -s -X POST http://127.0.0.1:8081/api/agents \
  -H "Content-Type: application/json" \
  -d '{"name":"build-runner-01","role":"DEVELOPER"}'

# 停用后实然权限清零
curl -s -X PUT http://127.0.0.1:8081/api/agents/AGT-0008/status \
  -H "Content-Type: application/json" -d '{"status":"SUSPENDED"}'

# 未置 RETIRED 直接删除 → 400
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE http://127.0.0.1:8081/api/agents/AGT-0008
```

交互式接口文档：`http://127.0.0.1:8081/swagger-ui.html`。

### 5.4 排障

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动报 `Port xxxxx was already in use` | `SERVER__PORT` 覆盖了配置 | 加 `--server.port=8081` |
| 写接口返回 403 `PERMISSION_DENIED` | 调用方身份缺 `agents:write` | 检查 `agent-id` 与 `grantPermissions` |
| 注册返回 400 `skills` | 技能名不在 core 注册表 | 用 `/api/agents/meta` 的 `skills` 取值 |
| `permissionsInSync=false` | 档案改了但同步失败 | 重新 PUT 改档触发覆盖式授予 |
| 重启后数据丢失 | 默认 `storage=memory` | 设 `storage=file` + `data-file` |
| 访问 `/agents/` 得到 404 | **本模块不提供页面** | 用 `/api/agents` 或 `/swagger-ui.html` |
