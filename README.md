# Town Demon-Subduing Tower — 端到端实现链路（PM → Arch → Dev → QA → Ops）

> **第一次来？** 直接看 **[docs/00-快速上手.md](docs/00-快速上手.md)**：10 分钟跑起两个看板，并把治理接进自己的项目。
> 产品定位见 [docs/16-产品介绍.md](docs/16-产品介绍.md)，流程边界（哪些固定、哪些可配）见 [docs/14-流程设计说明.md](docs/14-流程设计说明.md)。

本仓库按 **PM → Arch → Dev → QA → Ops** 五阶段顺序推进，每段交付物作为下一段输入，形成一条**完整可追溯的端到端实现链路**。
当前已推进到 **v1.17**：治理内核（core）+ Spring 薄适配层（starter）+ 交付进度看板（delivery）+ Agent 注册表（agents），
全仓 **1575 项测试通过**（47 个自运行器，全 rc=0）：core 774（682 + 审计写入吞吐压测 8 + 计划单轮转 29 + 同步暂存 55）、starter 341（237 + R4 用例 25 + 轮转 8 + 暂存 16 + 治理管理接口 50）、delivery 219、agents 241；全仓静态扫描 `files=263 violations=0 warnings=0`。v1.11 起"经过治理链"是**前置条件**而不是约定（`GovernancePass` 一次性令牌），
"绕过注解直调"在**构建期**即失败（`BypassScanMain`，零依赖）；v1.12 起 shell 类工具可以声明
**命令模板**（`commandTemplate`），形状不符直接拒绝，不再靠黑名单猜危险；v1.13 起需要"先批计划再动手"的工具
可以声明 `requiresPlanApproval`，未批准直接返回 `PLAN_APPROVAL_REQUIRED`，审批动作本身进审计链；v1.14 起计划单
默认落盘回放，结构化计划只放行点名的工具；v1.15 起时间来源收敛为 `TimeProvider` 契约，
Controller 不再为取 `now()` 注入业务 Service（构建期 `CP10-R4` 归零）；计划单文件支持
按大小轮转归档（`towndemontower.plan-max-bytes`，默认关闭）；v1.16 起锚点副本推送失败的记录
先落本地暂存区（`<audit-file>.spool`），由定时任务按"7 天 + 64 MiB"双阈值清理并向远端补传，
成功即删、超次转 `dead/` 保留，完整数据不走暂存区。v1.17 起提供一组**只读 + 人工决策**的治理管理接口
（`towndemontower.admin-api-enabled=true` 打开，复用 `metrics-token` 做 `X-Governance-Token` 鉴权）：
审批单与执行计划可查可批、审计可查可校验、暂存区可观测可补传可清理；模式切换/权限授予/放行 R3
等高风险能力**刻意不开放**（见 [docs/33-治理管理接口.md](docs/33-治理管理接口.md)）。

## 链路总览

| 阶段 | 角色 | 交付物 | 状态 |
|------|------|--------|------|
| 0 | PM | [docs/01-PM-需求说明.md](docs/01-PM-需求说明.md) | ✅ |
| 1 | Arch | [docs/02-Arch-架构方案.md](docs/02-Arch-架构方案.md) | ✅ |
| 2 | Dev | `towndemontower-core/src/...` 纯 Java 实现 + `DemoRunner` | ✅ |
| 3 | QA | [docs/04-QA-测试用例与结果.md](docs/04-QA-测试用例与结果.md)（34/34 通过） | ✅ |
| 4 | Ops | [docs/05-Ops-部署与运维上线.md](docs/05-Ops-部署与运维上线.md) + `towndemontower-core/pom.xml` | ✅ |

## 设计基线（README v1.3 收敛）

- **模型只提议，闸门硬裁决**：模型输出意图对象，风险分级/审批/执行/回滚由外部闸门承担。
- **三层 AND**：业务态允许（状态机） + 动作允许（安全闸门 R0-R3） + 工具允许（AgentMode 白名单）。
- **core 纯 Java、零 Spring**：`core` 内禁止 `import org.springframework.*`；契约语言中立（JSON、禁 Optional/CompletableFuture/Java 序列化）。
- **R0 放行 / R1 轻确认 / R2 人工 / R3 默认拒绝**（带 --force 也不放行）。
- **可审计、可快照、可回滚、可急停**：写前快照三样，审计追加写 + SHA-256 哈希链，急停取消在途 Tool 调用。

## 最小链路（已运行验证）

```
IntentObject
  → ModeToolPolicy（模式白名单）
  → IntentFormat（格式 + 置信度）
  → Schema / Capability
  → Permission（RBAC）
  → RiskGate（R0-R3 + AST + 复合取最高）
  → ApprovalRequired（R2 挂人工）
  → TaintCheck（污点写阻断）
  → Snapshot（写前快照）
  → SandboxedTool（沙箱执行）
  → Audit（哈希链，任何结果都记录）
```

## 快速验证

```bash
cd towndemontower-core
# 用 JDK 21 直接编译运行（零 Maven 亦可）
/c/Program\ Files/Java/jdk-21.0.12/bin/javac -d out $(find src/main/java src/test/java -name "*.java")
/c/Program\ Files/Java/jdk-21.0.12/bin/java  -cp out com.towndemontower.DemoRunner     # 演示最小链路
/c/Program\ Files/Java/jdk-21.0.12/bin/java  -cp out com.towndemontower.core.CoreTest  # 验收 34/34

# 或用 Maven
mvn -q -pl towndemontower-core test
```

## 模块结构

```
towndemontower-core/src/main/java/com/towndemontower/core/
├── contract/   IntentObject, Executor, SkillDescriptor, RiskLevel, ExecutionContext, ErrorCodes, ApprovalTicket
├── statemachine/  TaskStatus/TaskEvent/TaskStateMachine
├── mode/       AgentMode, ModeToolPolicy, ModeStateMachine（转换表）
├── policy/     RiskGate, DefaultRiskGate, ShellAstAnalyzer（AST 静态分析）, CommandTemplate（命令模板）
├── decorator/  装饰器链（ModeToolPolicy→...→Audit）+ GovernancePass（出口令牌，链尾发牌）
├── audit/      AuditStore, HashChainAuditStore（SHA-256 哈希链）
├── context/    PipelineState, PipelineStateStore（落盘/恢复）
├── gate/       GateScript, GateScriptRunner（签名校验 + 重试）
├── approval/   ApprovalPolicy, HumanApprovalPolicy, GuardianPrecheck（Guardian 仅预审）
├── routing/    ModelRouter, SimpleModelRouter（快慢分离）
├── executor/   ExecutorFactory, SkillRegistry, PermissionEngine, SnapshotService
└── bypass/     CP-10 静态扫描（JavaSourceLexer / BypassScanner / BypassScanMain，零依赖）

towndemontower-spring-starter/src/main/java/com/towndemontower/spring/
├── AgentSkill.java            注解（切点定义）
├── AgentSkillAspect.java      AOP 切面（桥接 Spring 方法 ↔ core 治理链）
├── DelegatingSkillInvoker.java 链尾透传通道
├── TownDemontowerAutoConfiguration.java  自动配置
└── tx/                        事务一致性（Outbox + WS 推送）

towndemontower-delivery/src/main/java/com/towndemontower/delivery/   （v1.3 交付进度看板）
├── domain/     DeliveryItem, DeliveryStatus, DeliveryStatusResolver, DeliverySummary(+Calculator), Repository×3
├── service/    DeliveryProgressService（纯 Java 领域服务，时钟可注入）
├── governance/ DeliveryGovernanceFacade, DeliveryModeToolPolicy, DeliverySkillDescriptors
├── web/        DeliveryController, DeliveryBoardPageController, View/Request/ExceptionHandler
├── config/     DeliveryAutoConfiguration, DeliveryProperties
└── DeliveryBoardApplication.java  可独立启动的演示入口

towndemontower-agents/src/main/java/com/towndemontower/agents/       （v1.4 Agent 注册表）
├── domain/     Agent, AgentRole, AgentStatus, ModelTier, RoleTemplate,
│               AgentSummary(+Calculator), Repository×3, SkillCatalog, AccessProvisioner
├── service/    AgentService（注册/改档/状态流转/删除/筛选/汇总，纯 Java）
├── governance/ AgentGovernanceFacade（装饰器链 + 权限同步）,
│               AgentsModeToolPolicy, AgentSkillDescriptors,
│               CorePermissionProvisioner, RegistrySkillCatalog
├── seed/       DefaultAgents（7 个演示 Agent）
├── web/        AgentController, View/Request/SummaryView/ExceptionHandler（仅 REST，无页面）
├── config/     AgentsAutoConfiguration, AgentsOpenApiConfiguration, AgentsProperties
└── AgentsApplication.java         可独立启动的演示入口（8081）
```

## 排期与进度

- **v1.0 ✅** Phase 0 core 骨架 + v1.3 契约最小链路（纯 Java，34/34 单测）。见 `docs/01~05`。
- **v1.1 ✅** Phase 1 Spring 薄适配层（@AgentSkill + AOP + 自动配置，5/5 验证）。见 `docs/06-Phase1-适配层设计.md`、`docs/07-Phase1-伪代码实现.md`。
- **v1.2 ✅（首批）** Runtime & Infra 增强：取消令牌、文件状态持久化、凭据代理、AST 管道/子 shell 增强、进程级沙箱策略、门禁重试验证。22/22 功能测试通过，34/34 无回归。见 `docs/09-Phase1.2-实现.md`。
- **v1.2.1 ✅（首批）** Docker 容器级沙箱：`DockerSandboxStrategy`/`DockerSandboxExecutor`（替换 `ProcessSandboxStrategy`，装饰器链零改动，`buildDockerArgs` 隔离基线校验 + 不可用优雅降级）。14/14 单测通过，22/22 与 34/34 无回归。见 `docs/10-Phase1.2.1-Docker沙箱实现.md`。
- **v1.2.1 ✅（首批续）** starter 事务一致性：`@Transactional` 治理决策落库 + Outbox 表 + 提交后事件经 WS 总线推送，定时轮询兜底崩溃恢复/重试。新增 `tx` 包与 `TownDemontowerTxTest` 14/14 通过，`StarterDemo` 5/5 无回归。见 `docs/11-Phase1.2.1-事务一致性实现.md`。
- **v1.2.1 📋** 仍排后：审批网关真实接入（用户/IM/CLI/转人工终端）。
- **v1.3 ✅（首批）** 交付进度看板模块 `towndemontower-delivery`：交付项全字段管理（阶段/负责人/计划起止/实际完成/进度百分比/状态）、新增更新删除、按状态筛选、整体进度自动汇总；**状态为推导字段**（到期即自动"已延期"，无需定时任务）+ 人工覆盖通道；写操作接入 core 治理链（共享审计哈希链）；**看板默认呈现本产品自身的 13 条真实交付里程碑**（时间取自仓库真实时间戳）；**集成 OpenAPI 3 + Swagger UI 在线接口文档**（零 CDN，离线可用）。领域层 79/79 + 路线图 43/43 + Web 层 56/56 + OpenAPI 契约 41/41，全仓回归 308/308。见 `docs/12-Phase1.3-交付进度看板.md`。
- **v1.3 📋** 仍排后：War Room 面板、审计看板、质量指标看板、急停控制台、配置档位。
- **v1.6 ✅** 默认配置即安全：**权限不再自动授予**（`auto-grant-declared-perms` 默认 `false`，改用 `permissions-bootstrap` 或注册表显式下发）；**关闭治理 = 全部拒绝**（`RejectAllExecutor`，不再"不装配即放行"）；**审计默认落盘**（`FileAuditStore`：追加写、分段、启动逐段校验，被篡改则拒绝启动）；**审批与状态默认落盘**；**启动自检**（`GovernanceIntegrityChecker`：CRITICAL 配置拒绝启动，WARN 记入审计）；**输入校验**（长度/控制字符/路径穿越/参数个数，`INPUT_REJECTED`）；**错误信息脱敏**（内部错误不回显细节）；**观测端点需令牌**（`X-Governance-Token`）。core 143 + starter 71 + delivery 219 + agents 201 = **634/634**。见 `docs/20-默认安全配置说明.md`。
- **v1.5 ✅** 设施层补齐（把 `docs/19` 指出的五个"类写了但没接上"的断点接上）：**模式只能由状态机推进**（`GovernedModeSession`，切面不再接受调用方指定的 mode，改配置只留痕 `MODE_OVERRIDE_IGNORED`）；**流水线状态真实落盘**（`AuditDecorator` 每次裁决后写 `PipelineState`，内存/文件可切换）；**写前快照改为真副本 + 可恢复**（`SnapshotService.restore()`：原文件还原，原不存在的文件删除）；**门禁签名改 HMAC-SHA256**（密钥走环境变量，内置开发密钥会在自检里被标为 `GATE_DEV_KEY`）；**审批中心**（`ApprovalCenter`：可查询、禁自批、超时即拒、可落盘，R2 的批准权在机制层钉住，只有显式实现 `TrustedApprovalPolicy` 才能自动批准）；**指标层**（`GovernanceMetrics` + 默认关闭的 `/towndemontower/metrics`、`/towndemontower/integrity`）；**上下文与提示词**（`ContextBuilder` + `ModePrompts`：前缀稳定可缓存、工具清单即模式白名单、不写免责声明）。core 123 + starter 50 + delivery 219 + agents 201 = **593/593**。
- **v1.4 ✅** Agent 注册表模块 `towndemontower-agents`：把治理链里的 `agentId` 从**裸字符串**变成**有档案的注册对象**——ID（`AGT-0001`，即 `agentId`）、名称、角色（PM/ARCH/DEV/QA/OPS）、注册时间、备注、可用技能、权限、模型档位；状态 `ACTIVE/PAUSED/SUSPENDED/RETIRED` 决定"能否执行"与"是否保留权限"。**档案是 RBAC 的权威来源**：注册/改档授予（覆盖式）、停用/注销立即整员回收，接口同时返回应然 `permissions` 与实然 `effectivePermissions`；技能必须在 core 工具注册表中存在；角色带默认技能/权限模板。**本期不做团队、不提供页面**（仅 REST + OpenAPI）。领域 78 + 权限 50 + 端到端 73 = 201。见 `docs/13-Phase1.4-Agent注册表.md`。快速上手见 `docs/00-快速上手.md`。
- **v1.4 📋** 仍排后：团队 / 部门与权限继承、任务派发与多 Agent 协作、审批网关真实接入。
- **v1.7 ✅** 过渡宽限与门禁密钥：存量代码有显式宽限窗口（每次放行留痕），门禁签名密钥不再内置。见 `docs/21-过渡宽限与门禁密钥.md`。
- **v1.8 ✅** REST 调用者认证：`ApiAuthFilter`，未认证请求进不了治理链。见 `docs/22-REST调用者认证.md`。
- **v1.9 ✅** 授权收口与审计加固：权限改为显式下发 + 审计记录 HMAC 防篡改。见 `docs/23-授权收口与审计加固.md`。
- **v1.9.1 ✅** 凭据与 Agent 绑定：调用凭据必须绑定到 `ACTIVE` 的 Agent，停用即失效。见 `docs/24-凭据与Agent绑定.md`。
- **v1.9.2 ✅** 审计写入收权与锚点追平：只有审计器能写审计，锚点与日志逐段对齐。见 `docs/25-审计写入收权与锚点追平.md`。
- **v1.10 ✅** 锚点副本与回滚检出：锚点另存副本，日志被截断/回滚可被检出。全仓回归 **1180/1180**。见 `docs/26-锚点副本与回滚检出.md`。
- **v1.11 ✅** 绕过治理令牌与静态扫描：`GovernancePass` 一次性令牌（默认 deny，`UNGOVERNED_INVOCATION` 恒应为 0）+ 零依赖 `BypassScanMain`（规则 R1-R4，可输出 SonarQube 通用问题报告）。全仓回归 **1249/1249**。见 `docs/29-绕过治理令牌与静态扫描.md`。
- **v1.12 ✅** 命令模板化（I5）：工具用 `commandTemplate` 声明"只允许这种形状的命令"——字面量位置固定、参数只能落在 `{...}` 槽位、不允许多余词、参数值禁控制符、单值槽位禁 `-` 开头；不符即 `COMMAND_TEMPLATE_MISMATCH`。**AST 仍作第二道**（宽模板 + `rm -rf /` 照旧升 R3）。全仓回归 **1298/1298**。见 `docs/30-命令模板化.md`。
- **v1.12.1 ✅** 扫描器补 `CP10-R5`（模板过宽）：`commandTemplate` 里没有任何固定词（如 `{cmd*}`）时告警——否则"我配了模板"会变成自我安慰。WARN 级、可显式豁免、`--strict` 下阻断。全仓回归 **1304/1304**。
- **v1.13 ✅** Plan-and-Execute 入口：`@AgentSkill(requiresPlanApproval = true)` → `PlanApprovalService` 按 (agent, task) 提交/审批计划，闸门前置在风险分级之前；禁自批、否决不可补批、未装配服务 fail-closed；`PLAN_REQUESTED/APPROVED/REJECTED` 进同一审计哈希链（摘要不入链）。全仓回归 **1336/1336**。见 `docs/31-执行计划审批.md`。
- **v1.14 ✅** 计划可回放 + 结构化 PlanArtifact：计划单默认追加落盘到 `<state-dir>/plans.log`，重启后回放，单号序号接着历史继续；`PlanStep` 让计划点名工具，闸门收窄为"只放行计划覆盖的工具"（旧格式计划仍按任务级放行）；文件损坏拒绝启动；扩大范围走显式 `revise(...)` 重新审批。全仓回归 **1379/1379**。见 `docs/32-计划回放与结构化.md`。
- **v1.17 ✅** 治理管理接口（后端）：14 个端点覆盖 R3 人工审批、执行计划审批、审计查询/校验、同步暂存区运维；
  开关默认关闭，复用 `metrics-token` 做 `X-Governance-Token` 鉴权；模式切换/权限授予/放行 R3 等**不开放**。
  全仓回归 **1575/1575**，扫描 `files=263 violations=0 warnings=0`。见 `docs/33-治理管理接口.md`。
- **v2.0 📋** Python/JS SDK、多 Agent 编排、多模态预处理。

完整任务清单见 `docs/08-版本任务清单.md`。

## 构建期自检（v1.11）

检查"绕过注解直调、注解标错位置、方法体偷偷做副作用、Controller 直调 Service、命令模板过宽"五类问题。
零依赖、纯 JDK，可直接当门禁用（本仓库不含 CI 配置，接入方式自定）：

```bash
cd towndemontower-core
javac -encoding UTF-8 -d out $(find src/main/java src/test/java -name "*.java")
java -cp out com.towndemontower.core.bypass.BypassScanMain --root ..            # 0=通过 1=违规 2=用法/IO 错
java -cp out com.towndemontower.core.bypass.BypassScanMain --root .. --strict --report target/bypass.json
```

`--report` 产出的 JSON 可直接挂到 SonarQube 的 `sonar.externalIssuesReportPaths`（无需插件）。
豁免写在方法上方注释行首：`// towndemontower:allow CP10-R2 reason=<非空理由>`，缺理由本身算违规。

## 快速体验交付看板（v1.3）

```bash
# 1) 先安装依赖模块到本地仓库（离线环境）
cd towndemontower-core && mvn -o -Dmaven.test.skip=true install
cd ../towndemontower-spring-starter && mvn -o -Dmaven.test.skip=true install

# 2) 启动看板（demo profile：8080 端口 + 自动播种本产品真实路线图）
cd ../towndemontower-delivery
mvn -o spring-boot:run -Dspring-boot.run.profiles=demo
#   或打包后运行
mvn -o -DskipTests package
java -jar target/towndemontower-delivery-0.1.0.jar --spring.profiles.active=demo
```

打开 <http://127.0.0.1:8080/delivery/> 即可看到交付进度看板；
REST 接口在 `/api/delivery/**`（`items` / `summary` / `meta`）。

**接口文档（启动后可直接访问）**

| 地址 | 内容 |
|---|---|
| <http://127.0.0.1:8080/swagger-ui.html> | Swagger UI 可交互文档（Try it out 可直接发真实请求） |
| <http://127.0.0.1:8080/v3/api-docs> | OpenAPI 3 JSON 契约（可导入 Postman / Apifox / 客户端生成器） |
| <http://127.0.0.1:8080/v3/api-docs.yaml> | 同一契约的 YAML 形式 |
| `docs/12-Phase1.3-交付进度看板.md` §3.3 | Markdown 版的 REST 契约表（离线可读） |

Swagger UI 的静态资源由本地 `webjar` 提供，**零 CDN 依赖**，完全离线可用。

**看板呈现的是本产品自己的制作进度**：13 条真实里程碑
（6 已完成 / 1 已延期 / 1 进行中 / 5 未开始），整体进度 49%、完成率 46%，
每个版本里程碑的时间都取自仓库真实时间戳。
需要通用样例数据时加 `--towndemontower.delivery.seed-mode=sample`。

> 演示配置放在 `application-demo.properties`，**不会污染接入方应用自己的 `application.properties`**；
> 不激活 profile 时模块以"空看板"启动（`demo-data=false`，不占用 8080 默认端口以外的行为）。

## 快速体验 Agent 注册表（v1.4）

```bash
# 1) 依赖模块（core 有改动时务必重装）
cd towndemontower-core && mvn -o -Dmaven.test.skip=true install
cd ../towndemontower-spring-starter && mvn -o -Dmaven.test.skip=true install

# 2) 启动注册表（demo profile：8081 + 播种 7 个演示 Agent）
cd ../towndemontower-agents
mvn -o spring-boot:run -Dspring-boot.run.profiles=demo
#   或
java -jar target/towndemontower-agents-0.1.0.jar --spring.profiles.active=demo
```

**本模块只提供 REST 接口，不提供页面**：

- 接口：`/api/agents`（列表/注册）、`/api/agents/{id}`（详情/改档/删除）、
  `/api/agents/{id}/status`（状态流转）、`/api/agents/summary`、`/api/agents/meta`
- 接口文档：<http://127.0.0.1:8081/swagger-ui.html> ｜ `/v3/api-docs`

```bash
# 汇总：7 个 / ACTIVE 4 / 可执行 4 / 权限覆盖率 100%
curl -s http://127.0.0.1:8081/api/agents/summary

# 停用与已注销的实然权限为空、档案权限仍在（应然 vs 实然）
curl -s "http://127.0.0.1:8081/api/agents?status=SUSPENDED"
```

**核心闭环**：注册/改档 → 权限按档案授予 core RBAC；停用/注销 → 立即整员回收。
所有写操作经治理链（风险闸门 + RBAC + 快照 + 审计哈希链）。

> 端口被 `SERVER__PORT` 环境变量占用时，用 `--server.port=8081` 显式覆盖。
> 完整交付说明（PM→Arch→Dev→QA→Ops、REST 契约、状态机、权限闭环）见
> `docs/13-Phase1.4-Agent注册表.md`。
