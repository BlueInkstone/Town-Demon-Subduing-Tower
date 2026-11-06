# Town Demon-Subduing Tower — Phase 1.3 实现交付（交付进度看板模块）

> 上游输入：`docs/08-版本任务清单.md` 中 v1.3 分组（面板族首批）。
> 本阶段角色推进：**PM 定位 → Arch 接口划分 → Dev 实现 → QA 验证 → Ops 说明**。
> 新增模块：`towndemontower-delivery`（独立 Maven 模块，依赖 `towndemontower-spring-starter`）。
> 约束延续：core 纯 Java 零 Spring 依赖；领域层零外部依赖；看板写操作接入 core 治理链。

---

## 1. PM 定位（做什么 / 为什么）

### 1.1 需求原文

> 编写一个用于管理和展示产品交付进度的功能模块，包含交付阶段名称、负责人、计划开始与完成时间、
> 实际完成时间、当前进度百分比及状态（未开始/进行中/已延期/已完成）等字段，
> 并支持新增或更新交付项、按状态筛选，以及自动计算整体交付进度汇总。

### 1.2 需求拆解与验收口径

| 需求要素 | 落地方式 | 验收口径 |
|---------|---------|---------|
| 交付阶段名称 | `phaseName`（必填） | 空值 → 400 |
| 负责人 | `owner`（必填） | 空值 → 400 |
| 计划开始时间 | `plannedStart`（必填） | 缺失 → 400 |
| 计划完成时间 | `plannedEnd`（必填） | 早于开始时间 → 400 |
| 实际完成时间 | `actualEnd`（可空） | 非空 → 直接判定"已完成" |
| 当前进度百分比 | `progressPercent`（0~100） | 越界 → 400 |
| 状态（4 态枚举） | `DeliveryStatus`：未开始/进行中/已延期/已完成 | 自动推导 + 人工覆盖 |
| 新增交付项 | `POST /api/delivery/items` | 返回 201 + 自动生成 `DLV-xxxx` |
| 更新交付项 | `PUT /api/delivery/items/{id}` | 部分更新（null 字段保持原值） |
| 按状态筛选 | `GET /api/delivery/items?status=` | 未知状态 → 400 |
| 自动计算整体汇总 | `GET /api/delivery/summary` | 整体进度 = 各项进度均值（向下取整） |

### 1.3 关键设计判断（本次最核心的取舍）

**状态是"推导字段"，不是"存储字段"。**

若把状态存进数据库，就会出现经典问题：一条计划在 11-30 到期的任务，到了 12-01 数据库里还写着"进行中"，
必须靠定时任务刷新，而定时任务挂掉 / 延迟时看板就会撒谎。

本模块的做法是：`DeliveryStatusResolver.resolve(item, now)` 是**纯函数**，
由"当前时间 + 计划完成时间 + 进度 + 实际完成时间"实时推导：

```
优先级（从高到低）：
1. 人工覆盖 statusOverride  → 直接采用（支持用 "AUTO" 清除，回到自动推导）
2. progress >= 100 或 actualEnd != null  → 已完成
3. now > plannedEnd          → 已延期
4. progress > 0 或 now >= plannedStart    → 进行中
5. 其余                      → 未开始
```

**收益**：到期那一刻起看板自动呈现"已延期"，无需任何写操作或定时任务；
且因为推导函数是纯函数，可以用**注入的固定时钟**做精确断言（见 QA 章节的"时钟推进"用例）。

**同时保留人工覆盖通道**：真实项目里"已延期但对外宣称进行中"是常见诉求，
所以允许显式指定状态，且支持随时清除覆盖回到自动推导。

**不在本批**：多租户隔离（当前 `tenantId` 恒为 `tenant-default`）、
状态变更历史留痕（当前只有工具调用审计，无字段级变更明细）、
看板的权限分角色（当前读、写权限统一授予 `delivery-board-agent` 一个主体）。

---

## 2. Arch 接口与划分

### 2.1 三层结构

```
┌─────────────────────────────────────────────────────────────┐
│ Web 层（Spring MVC）                                          │
│   DeliveryController / DeliveryBoardPageController             │
│   DeliveryItemView / DeliverySummaryView / DeliveryItemRequest│
│   DeliveryExceptionHandler（400/404/202/403 映射）              │
└──────────────────────┬──────────────────────────────────────┘
                       │ 写操作走门面；读操作走门面（R0）
┌──────────────────────▼──────────────────────────────────────┐
│ 治理接入层                                                     │
│   DeliveryGovernanceFacade（自建 core 装饰器链 + ThreadLocal invoker）│
│   DeliveryModeToolPolicy（专属白名单）                          │
│   DeliverySkillDescriptors（工具元数据：R1 写 / R0 读）          │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│ 领域层（纯 Java，零外部依赖）                                    │
│   DeliveryItem（不可变值对象）                                  │
│   DeliveryStatusResolver（状态推导，纯函数）                     │
│   DeliverySummaryCalculator（汇总计算，纯函数）                 │
│   DeliveryProgressService（CRUD + 校验 + 筛选 + 汇总）           │
│   DeliveryRepository / InMemory / File（仓储抽象 + 两种实现）    │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 关键架构决策

**决策 1：模块独立，不塞进 core。**
core 的定位是"AI Agent 治理内核"，交付看板是"产品交付进度管理"，职责不同。
新建 `towndemontower-delivery` 模块，依赖 starter 复用治理能力，core 零改动。

**决策 2：写操作真正接入 core 装饰器链。**
若看板写入绕过治理链，则"新增/更新交付项"这类有副作用的操作就不会进审计哈希链，
与项目"一切副作用操作可审计"的主张相悖。因此 `create/update/delete` 均走：

```
Audit → ModeToolPolicy → IntentFormat → Schema → Capability
      → Permission → RiskGate → TaintCheck → Snapshot → Sandbox
```

**决策 3：共享治理组件 + 独立注册表/白名单（关键的 Bean 冲突规避）。**

| 组件 | 处理方式 | 原因 |
|------|---------|------|
| `AuditStore` | **共享**实例 | 看板写入与业务写入进**同一条**审计哈希链 |
| `PermissionEngine` / `RiskGate` / `SnapshotService` / `ApprovalPolicy` | **共享**实例 | 治理规则只有一套 |
| `SkillRegistry` | **共享**实例（复用 starter 的 Bean） | 同一条治理链共享工具元数据 |
| `ModeToolPolicy` | **独立实例**，且**不注册为 Spring Bean** | 见下方说明 |

> **踩过的坑（已修复）**：`DeliveryAutoConfiguration` 最初也把 `SkillRegistry` 和
> `DeliveryModeToolPolicy` 注册成 Bean。结果容器里出现两个同类型 Bean，
> starter 装配主链 `Executor` 时按类型注入产生歧义；
> 更糟的是若交付看板的 Bean 先被创建，starter 的 `@ConditionalOnMissingBean` 会误判
> "用户已提供"，从而放弃注册自己的白名单 —— **业务主链的白名单会被交付看板的工具污染**。
> 现方案：交付看板**不注册**这两个同类型 Bean，白名单以普通对象传入门面。

**决策 4：门面内部自建 Executor，破循环依赖。**
`DeliveryGovernanceFacade` 需要 `Executor`，而 `Executor` 的链尾 invoker 又需要门面提供。
解法：门面**自己**调用 `ExecutorFactory.buildDefault(...)` 建链，链尾 invoker 由门面私有方法提供，
用 `ThreadLocal` 做 per-call 绑定（与 starter 的 `DelegatingSkillInvoker` 同构，但无代理织入）。

**决策 5：业务异常穿透治理链。**
core 的 `AuditDecorator` 会把链内所有 `RuntimeException` 统一转成 `INTERNAL_ERROR`。
但"字段校验失败 / 交付项不存在"是**正常业务语义**（应映射 400 / 404），不能被降级成治理拒绝。
解法：门面在链尾把这两类异常包装成 `BusinessError` 哨兵穿出链外，再还原成原异常抛出 ——
既保证审计留痕（调用确实发生过），又不丢失 400/404 语义。

**决策 6：时钟注入。**
`DeliveryProgressService` 用 `Supplier<Instant>` 注入时钟，测试可注入固定时钟，
"到期即延期"这类时间规则得以精确断言，不受真实时间影响。

**决策 7：汇总口径。**
- 整体进度 = 各交付项进度百分比之和 ÷ 总数（等权平均，**向下取整**）
- 完成率 = 已完成数 ÷ 总数（向下取整）
- 最近交付节点 = 未完成项中最早的 `plannedEnd`

---

## 3. Dev 实现清单（代码成果）

### 3.1 新增模块 `towndemontower-delivery`

| 文件 | 类型 | 说明 |
|------|------|------|
| `domain/DeliveryStatus.java` | 枚举 | 4 态：NOT_STARTED/IN_PROGRESS/DELAYED/COMPLETED，含中文标签与宽松解析 |
| `domain/DeliveryItem.java` | 值对象 | 不可变；`with(...)` 返回新实例；`withStatusOverride(null)` 清除人工覆盖 |
| `domain/DeliveryStatusResolver.java` | 纯函数 | 状态推导（人工覆盖 → 已完成 → 已延期 → 进行中 → 未开始） |
| `domain/DeliverySummary.java` | 值对象 | 汇总模型（总数/各态计数/整体进度/完成率/最近节点/**下一个交付节点**） |
| `domain/DeliverySummaryCalculator.java` | 纯函数 | 汇总计算 |
| `domain/DeliveryRepository.java` | 接口 | 仓储抽象（save/findById/findAll/deleteById/count） |
| `domain/InMemoryDeliveryRepository.java` | 实现 | `ConcurrentHashMap`，id 形如 `DLV-0001` |
| `domain/FileDeliveryRepository.java` | 实现 | `\|` 分隔纯文本行，零依赖落盘 |
| `service/DeliveryProgressService.java` | 领域服务 | CRUD + 字段校验 + 筛选 + 汇总；时钟可注入 |
| `service/DeliveryValidationException.java` | 异常 | → HTTP 400 |
| `service/DeliveryNotFoundException.java` | 异常 | → HTTP 404 |
| `governance/DeliveryModeToolPolicy.java` | 白名单 | 专属白名单（4 个工具） |
| `governance/DeliverySkillDescriptors.java` | 元数据 | create/update/delete → R1；query → R0 |
| `governance/DeliveryGovernanceFacade.java` | 门面 | 自建治理链 + ThreadLocal invoker + 业务异常穿透 |
| `governance/DeliveryGovernanceException.java` | 异常 | 携带 core 拒绝码 + 审批单号；→ HTTP 403 / 202 |
| `web/DeliveryController.java` | REST | 7 个端点（带完整 OpenAPI 注解） |
| `web/DeliveryBoardPageController.java` | 页面 | `/`、`/delivery`、`/delivery/` → 看板页重定向 |
| `web/DeliveryItemView.java` | DTO | 直接给出推导后的 `status` + `statusLabel` + `manualStatus` + `statusOverride` |
| `web/DeliverySummaryView.java` | DTO | 汇总视图 |
| `web/DeliveryItemRequest.java` | DTO | 请求体；`status="AUTO"` 表示清除人工覆盖 |
| `web/DeliveryExceptionHandler.java` | 异常映射 | 400 / 404 / 202 / 403 |
| `config/DeliveryProperties.java` | 配置 | 前缀 `towndemontower.delivery` |
| `config/DeliveryAutoConfiguration.java` | 自动配置 | 条件装配 + 治理组件复用 |
| `config/DeliveryOpenApiConfiguration.java` | **OpenAPI 契约** | **生成 Swagger UI + OpenAPI JSON/YAML 契约** |
| `DeliveryBoardApplication.java` | 启动类 | **可独立启动的演示入口** |
| `seed/ProjectRoadmap.java` | **路线图基线** | **本产品 13 条真实交付里程碑**（时间取自仓库真实时间戳） |
| `seed/DeliverySeedInitializer.java` | 播种器 | 首次启动播种；`seed-mode` 选 project / sample |
| `resources/static/delivery/index.html` | 看板前端 | 509 行原生 JS，**零 CDN 依赖** |
| `resources/application-demo.properties` | 演示配置 | profile 形式，避免污染接入方 `application.properties` |
| `tools/mark-local-artifacts.py` | 离线工具 | 把本地 m2 中已缓存的 artifact 标记为 `=local`，解决离线解析的仓库 ID 不匹配 |

> **路线图基线（`ProjectRoadmap`）**：看板不是通用样例的陈列架——
> 它默认呈现**本产品（Town Demon-Subduing Tower）自身的真实交付进度**，
> 打开即知各版本里程碑做到哪一步。13 条里程碑与仓库真实时间戳一一对应：
>
> | 里程碑 | 状态 | 进度 | 时间来源 |
> |--------|------|------|---------|
> | v1.0 Phase 0 · 治理内核与 v1.3 契约 | 已完成 | 100% | 契约层源码 11:23:22、PM 文档 11:27:53 |
> | v1.1 Phase 1 · Spring Boot 薄适配层 | 已完成 | 100% | starter 首个源文件 11:51:23、适配层文档 12:10:31 |
> | v1.2 · 运行时与基础设施增强（首批） | 已完成 | 100% | CredentialBroker 等 14:24:58、实现文档 14:35:14 |
> | v1.2.1 · Docker 容器级沙箱 | 已完成 | 100% | DockerSandboxStrategy 14:51:02、实现文档 14:58:38 |
> | v1.2.1 · starter 事务一致性（Outbox/WS） | 已完成 | 100% | tx 包 15:18:32、实现文档 15:40:23 |
> | v1.3 · 交付进度看板模块 | 已完成 | 100% | 模块首源文件 16:24:43、交付文档 17:28:45 |
> | v1.2 · ApprovalPolicy 真实审批网关接入 | **已延期** | 30% | 计划窗口已过，真实网关未接入 |
> | v1.3 · 交付看板增强（多租户/变更历史/分角色） | 进行中 | 10% | 刚立项排期 |
> | v1.3 · War Room / 审计看板 / 急停控制台 / 运维文档 | 未开始 | 0% | 4 条，尚未承诺日期 |
> | v2.0 · Python / JS SDK 与多 Agent 编排引擎 | 未开始 | 0% | 尚未承诺日期 |
>
> **时间口径**：已完成项用**绝对事实**（仓库真实 mtime）；
> 未交付项用**相对启动时刻**（已延期落过去、进行中横跨当下、未开始在未来），
> 保证看板任何时候打开状态分布都稳定且语义正确。
> 需要通用样例（与本产品无关的虚构阶段）时设 `seed-mode=sample`。

### 3.2 修改文件

- `towndemontower-spring-starter/pom.xml`：
  `spring-boot-starter-jdbc` 由**强制传递依赖**改为 `<optional>true</optional>`（另在 test scope 保留一份供 starter 自身 tx 用例）。
  **原因**：强制传递 JDBC 会让不含数据库驱动的消费方在启动时被 `DataSourceAutoConfiguration` 拦下
  （`Failed to determine a suitable driver class`），而 starter 的治理核心（core 装饰器链 / AOP）根本不需要数据库。
  需要 tx 能力的消费方自行引入 JDBC + 驱动即可。

### 3.3 REST 契约

> **在线文档**：启动后可直接访问 Swagger UI（见 §3.3.1）。下表是同一份契约的 Markdown 摘要，
> 便于离线阅读与代码评审。

| 方法 | 路径 | 说明 | 治理 |
|------|------|------|------|
| GET | `/api/delivery/items` | 列出全部（按 `plannedEnd` 升序） | R0 |
| GET | `/api/delivery/items?status=CODE` | 按状态筛选；未知状态 → 400 | R0 |
| GET | `/api/delivery/items/{id}` | 查询单个；不存在 → 404 | R0 |
| POST | `/api/delivery/items` | 新增 → 201 | R1 |
| PUT | `/api/delivery/items/{id}` | 部分更新；`status="AUTO"` 清除人工覆盖 | R1 |
| DELETE | `/api/delivery/items/{id}` | 删除 | R1 |
| GET | `/api/delivery/summary` | 整体汇总 | R0 |
| GET | `/api/delivery/meta` | 状态枚举 + 中文标签 + 服务器时间 | — |
| GET | `/`、`/delivery/`、`/delivery/index.html` | 看板页面 | — |

#### 3.3.1 在线接口文档（OpenAPI 3 + Swagger UI）

引入 `springdoc-openapi-starter-webmvc-ui` 2.3.0（与 Spring Boot 3.2.x 匹配）后，
契约由框架**自动生成**（不手写 YAML，避免与代码漂移），产出三类产物：

| 地址 | 类型 | 用途 |
|---|---|---|
| `/swagger-ui.html` | Swagger UI | 可交互文档；Try it out 可直接对运行中的服务发真实请求 |
| `/v3/api-docs` | OpenAPI 3 JSON | 机器可读契约；可导入 Postman / Apifox / 客户端生成器 |
| `/v3/api-docs.yaml` | OpenAPI 3 YAML | 同一契约的 YAML 形式，便于版本化 diff 与代码评审 |

**离线可用性**：Swagger UI 的静态资源（`swagger-ui.css` / `swagger-ui-bundle.js` 等）
由本地 `org.webjars:swagger-ui` 提供，页面**零 CDN 外链**，在完全离线的内网环境可直接使用。

**契约中包含的语义信息**（不只是端点列表）：

- 端点摘要与详细描述（含"状态是推导字段"的规则说明、人工覆盖优先级、治理链行为）；
- 请求/响应 schema：`DeliveryItemRequest` / `DeliveryItemView` / `DeliverySummaryView`，
  字段带中文说明与取值范围（如 `progressPercent` 标注 0~100）；
- 状态枚举的可选值（`NOT_STARTED` / `IN_PROGRESS` / `DELAYED` / `COMPLETED` / `AUTO`）；
- 错误响应码语义（400 校验失败 / 403 治理拒绝 / 404 不存在 / 202 待审批）。

**装配位置说明**：契约配置放在 **delivery 模块**而非 starter —— starter 是纯治理适配层，
不含任何 HTTP 端点，生成 OpenAPI 没有内容可生成；交付看板是本项目当前唯一暴露
REST 接口的模块。未来其他模块暴露接口时，各自注册一个 `OpenAPI` Bean 即可
（springdoc 支持多个 `OpenAPI` Bean 的分组合并）。

**开关**：`towndemontower.delivery.openapi.enabled=false` 可关闭契约生成
（仅当 classpath 存在 springdoc 时才装配，未引入的接入方完全不受影响）。

### 3.4 配置项

```properties
towndemontower.delivery.enabled=true          # 模块总开关（默认开）
towndemontower.delivery.governed=true         # 写操作接入治理链（默认开）
towndemontower.delivery.agent-id=delivery-board-agent   # RBAC 主体
towndemontower.delivery.storage=memory        # memory | file
towndemontower.delivery.data-file=delivery-items.data   # file 模式数据文件
towndemontower.delivery.demo-data=true        # 首次启动播种演示数据
towndemontower.delivery.seed-mode=project     # project（本产品真实路线图，默认）| sample（通用样例）
towndemontower.delivery.openapi.enabled=true  # 是否生成 OpenAPI 契约（默认开）
```

> **离线环境注意**：本仓库的 `settings.xml` 镜像配置为 `<mirrorOf>*</mirrorOf>`（仓库 ID `aliyunmaven`），
> 而本地 m2 中部分 artifact 缓存自 `central`，其 `_remote.repositories` 标记与当前构建上下文不匹配，
> 导致 `-o` 离线构建报 "present, but unavailable"。
> `tools/mark-local-artifacts.py` 用于把已缓存的 artifact 重新标记为 `=local`（本地安装语义），
> 从而**不改用户的 m2 缓存内容、也不需要联网**即可离线解析。
> 新增依赖时若遇到同类报错，执行一次该脚本即可。

---

## 4. QA 验证结果

### 4.1 测试矩阵

| 测试类 | 层次 | 用例数 | 结果 |
|--------|------|-------|------|
| `DeliveryDomainTest` | 纯 Java 领域层 | 79 | ✅ 79/79 |
| `DeliveryRoadmapTest` | 路线图基线（纯 Java） | 43 | ✅ 43/43 |
| `DeliveryWebTest` | Spring 集成（真实 HTTP + 真实 Tomcat） | 56 | ✅ 56/56 |
| `DeliveryOpenApiTest` | OpenAPI 契约产物（真实 HTTP） | 41 | ✅ 41/41 |
| `CoreTest` | core 回归 | 34 | ✅ 34/34 |
| `V12FeatureTest` | core 回归 | 22 | ✅ 22/22 |
| `V121DockerSandboxTest` | core 回归 | 14 | ✅ 14/14 |
| `StarterDemo` | starter 回归 | 5 | ✅ 5/5 |
| `TownDemontowerTxTest` | starter tx 回归 | 14 | ✅ 14/14 |
| **合计** | | **308** | ✅ **308/308** |

### 4.1.1 OpenAPI 契约覆盖要点（`DeliveryOpenApiTest`）

- `/v3/api-docs` 返回 200 且为合法 OpenAPI 3.0.1 契约，标题/版本/描述齐备；
- 描述文本包含"状态是推导字段"这一核心语义（中文）；
- **端点覆盖**：4 条路径（items / items/{id} / summary / meta）全部在契约中，
  且 HTTP 方法齐全（GET+POST / GET+PUT+DELETE / GET / GET）；
- **schema 覆盖**：`DeliveryItemRequest` / `DeliveryItemView` / `DeliverySummaryView` 三个 schema 存在，
  且字段完整（请求 7 字段、视图含 `statusLabel` / `manualStatus` / `statusOverride`）；
- `/v3/api-docs.yaml` 返回 200 且含 `openapi: 3.0.1` 与交付看板路径；
- **Swagger UI 离线可用**：`/swagger-ui/index.html` 200，
  `swagger-ui.css`（152 KB）与 `swagger-ui-bundle.js`（1.4 MB）均由本地 webjar 提供、可正常取回；
- **契约与实现一致**：契约中的路径数与实际端点数一致（防止契约描述不存在的端点）。

### 4.2 领域层覆盖要点

- 状态推导全分支：已完成（进度 100 / 有实际完成时间）、已延期（超期）、进行中（进度 > 0 / 已到计划开始）、未开始；
- **时钟推进用例**：把固定时钟从"到期前"推到"到期后"，验证 `IN_PROGRESS` 归零、`DELAYED` 增加 —— **全程零写操作**；
- 人工覆盖优先于自动推导；`clearStatusOverride` 清除后恢复自动；
- 汇总口径：整体进度均值、完成率、最近交付节点；
- **"下一个交付节点"口径**：与"最早未完成项"区分——已延期项的计划完成时间在过去，
  展示它对"下一个要交付什么"没有意义，故 `nextUpcomingPlannedEnd` 只取**尚未到期**的未完成项；
  全部未完成项均延期时该字段为 null（有用例覆盖）；
- 字段校验：阶段名空、负责人空、计划起止倒挂、进度越界。

### 4.2.1 路线图基线用例（`DeliveryRoadmapTest`）

- 条目总数 13；各状态计数 6 已完成 / 1 已延期 / 1 进行中 / 5 未开始；
- 已完成项**均带实际完成时间**且落在基线日；未完成项**均无**实际完成时间；
- 已完成项进度均为 100；
- 整体进度 = (100×6 + 30 + 10 + 0×5) / 13 = 49；完成率 = 6/13 = 46；
- **无任何人工状态覆盖**（状态全部来自自动推导）；
- 时钟推进到 2026-12-20 后，未开始项归零、已延期项增加（自动延期仍然成立）；
- 关键里程碑命名可追溯（治理内核 / Spring Boot / Docker / 事务一致性 / 交付看板）。

### 4.3 Web 层覆盖要点（真实 HTTP）

- CRUD 全链路；`?status=` 四态筛选各命中 1 条；未知状态 → 400；
- 汇总：总数 4、各态计数、整体进度 35（= (10+100+30+0)/4）、完成率 25；
- 部分更新语义：只传 `progressPercent` 时，阶段名 / 负责人保持原值；
- 人工指定状态 → `manualStatus=true` + `statusOverride` 回显；`status="AUTO"` → 恢复自动推导；
- 校验失败 → 400 且带 `error=VALIDATION_FAILED` + 出错 `field`；
- 不存在 → 404（查询与删除均验证）；
- **审计哈希链**：写操作已落链、哈希链校验通过、链中含 `delivery.` 工具调用；
- **共享注册表 + 白名单不污染**：`delivery.createItem` 已注册、业务工具 `restartService` 仍在；
  容器内 `ModeToolPolicy` Bean **仅 1 个**，且业务主链白名单**不含**交付看板工具。

### 4.4 真实启动验证（手动）

```
Started DeliveryBoardApplication in 2.451 seconds
[delivery] 演示数据已播种：4 条交付项（未开始 / 进行中 / 已延期 / 已完成 各 1 条）
```

实测接口返回：
```json
{"totalCount":4,"completedCount":1,"inProgressCount":1,"delayedCount":1,
 "notStartedCount":1,"overallProgressPercent":47,"completedRatioPercent":25}
```

页面可达性：`/` → 302 → `/delivery/index.html` → 200；
看板页 **21,833 字节，零外链（0 个 CDN 依赖）**，JS 引用的 35 个 DOM id 全部在 HTML 中定义（无悬空引用）。

### 4.5 测试执行方式（离线环境注意）

JDK 21 + surefire 3.2.5 组合存在 `LinkageError`，**不可用 `mvn test`**。统一改为：

```bash
mvn -o -DskipTests test-compile
mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes;target/test-classes;$(cat cp.txt)" com.towndemontower.delivery.DeliveryWebTest
```

> **端口陷阱**：环境变量 `SERVER__PORT` 会被 Spring Boot 松散绑定覆盖 `server.port`，
> 且其优先级**高于** `setDefaultProperties`。测试中必须用**命令行参数** `--server.port=0` 取随机端口，
> 否则会撞上被占用的固定端口。

---

## 5. Ops 部署与运维

### 5.1 最小演示路径

```bash
# 1) 安装依赖到本地仓库（离线环境必需）
cd towndemontower-core        && mvn -o -Dmaven.test.skip=true install
cd ../towndemontower-spring-starter && mvn -o -Dmaven.test.skip=true install

# 2) 启动交付看板（demo profile：8080 端口 + 自动播种本产品真实路线图 13 条）
cd ../towndemontower-delivery
mvn -o spring-boot:run -Dspring-boot.run.profiles=demo

#   或打包后独立运行
mvn -o -DskipTests package
java -jar target/towndemontower-delivery-0.1.0.jar --spring.profiles.active=demo
#   → http://127.0.0.1:8080/delivery/
```

> **启动后应当看到什么**：看板呈现的是**本产品自己的制作进度**——
> 13 条真实里程碑（6 已完成 / 1 已延期 / 1 进行中 / 5 未开始），整体进度 49%、完成率 46%，
> "下一个交付节点"指向尚未到期的最近一项。
> 这是这个看板的用途：**看 Town Demon-Subduing Tower 做到哪一步了**，
> 而不是一份通用的样例数据展示。

> **为什么用 `application-demo.properties` 而不是 `application.properties`**：
> 模块根目录的 `application.properties` 会被**消费方应用**的 classpath 一并加载
> （Spring Boot 会把 classpath 上所有同名文件合并），从而把演示端口、演示数据开关等
> 强加给接入方。改用 profile 后，演示配置只在激活 `demo` profile 时生效。
> 实测：不激活 profile 时模块以"空看板、默认端口"启动，行为符合作为 starter 的预期。

### 5.2 配置项与默认值

见 §3.4。生产环境建议：
- `towndemontower.delivery.storage=file` + 指定 `data-file`（内存模式重启即丢）；
- `towndemontower.delivery.demo-data=false`（关闭演示数据播种）；
- `towndemontower.delivery.agent-id` 改为实际业务主体。

> **关于路线图基线**：接入方若把本模块当通用交付看板用，应把 `seed-mode` 设为 `sample`
> 或直接关闭 `demo-data`，然后用 `POST /api/delivery/items` 录入自己的交付项；
> `seed-mode=project` 只适用于本项目自身演示。

### 5.3 已知限制与后续排期

| 项 | 现状 | 计划 |
|----|------|------|
| 多租户隔离 | `tenantId` 恒为 `tenant-default` | v1.3 面板族第二批 |
| 字段级变更历史 | 仅工具调用审计，无字段 diff | v1.3 审计看板 |
| 看板读权限未分角色 | 读/写权限授予同一主体 | v1.3 |
| 审批网关 | R2 只返回审批单号，无真实审批入口 | 仍排后（v1.2.1） |
| 看板未做移动端适配 | 仅桌面布局 | v1.3 |
| 汇总口径为等权平均 | 未支持按工作量加权 | v1.3 质量指标看板 |
| 路线图基线为静态快照 | 13 条里程碑在代码里固化，不会随仓库进展自动更新 | 需人工维护或改接 `docs/08` 解析 |

---

## 6. 交付物索引

| 交付物 | 位置 |
|--------|------|
| 模块源码 | `towndemontower-delivery/src/main/java/com/towndemontower/delivery/` |
| 看板前端 | `towndemontower-delivery/src/main/resources/static/delivery/index.html` |
| 领域层测试 | `towndemontower-delivery/src/test/java/com/towndemontower/delivery/DeliveryDomainTest.java` |
| Web 集成测试 | `towndemontower-delivery/src/test/java/com/towndemontower/delivery/DeliveryWebTest.java` |
| 演示配置 | `towndemontower-delivery/src/main/resources/application.properties` |
| 本文档 | `docs/12-Phase1.3-交付进度看板.md` |
