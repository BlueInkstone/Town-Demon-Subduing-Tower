# Town Demon-Subduing Tower — Phase 1.2.1 实现交付（starter 事务一致性）

> 上游输入：`docs/08-版本任务清单.md` 中 v1.2.1 分组（starter 事务一致性：`@Transactional` + Outbox + WS）。
> 本阶段角色推进：**PM 定位 → Arch 接口划分 → Dev 实现 → QA 验证 → Ops 说明**。
> 约束延续：core 纯 Java、零 Spring 依赖；新增均在 starter 侧；有 DataSource 才启用，无 DataSource 自动退回旧行为。

---

## 1. PM 定位（做什么 / 为什么）

治理决策（尤其是 R2 待审批）一旦产生，需要**两件事最终都发生且一致**：① 决策结果被持久化（审批单/审计可查）；② 审批者/Ops 面板**实时收到通知**以便处理。若只用"先落库再发 WS"，中途进程崩溃会导致"库里有待审批单、但审批者永远不知道"——不一致。

本批用**事务发件箱（Transactional Outbox）**模式解决：
- 决策落库与"待发事件"写入**同一数据库事务**，原子提交；
- 提交后由中继把事件推到 WS 总线；
- WS 不可用时事件留在发件箱，由定时轮询兜底补发（崩溃恢复 / 失败重试）。

**不在本批**（仍排后）：`ApprovalPolicy` 真实审批网关（用户/IM/CLI/转人工终端）。

---

## 2. Arch 接口与划分

- **GovernanceDecisionRecorder**（接口）+ `TransactionalGovernanceDecisionRecorder`（`@Transactional`）：在单事务内执行 core 装饰器链、落库 `governance_decision`、写 `outbox_message`（PENDING）、发布提交后事件。R2 返回 `PENDING_APPROVAL` 且**不执行真实工具**，R3 直接拒绝，R0/R1 放行并执行。
- **GovernanceDecisionRepository / OutboxRepository**：JDBC 直写（零 ORM 依赖），`OutboxRepository` 负责 PENDING 查询、`markPublished`、`incrementRetry`。
- **OutboxRelay**：两条触发路径互为兜底——
  - `@TransactionalEventListener(AFTER_COMMIT)`：业务事务提交后立即发布（常见路径）；
  - `@Scheduled(fixedDelay=5s)`：周期轮询，覆盖"提交后崩溃 / 首次发布失败"等场景；超过 `MAX_RETRY=5` 停止自动重试，交告警/人工。
- **ApprovalEventPublisher**（接口）+ `InMemoryWebSocketBroker`（默认实现）：WS 推送抽象。默认为进程内发布/订阅总线（等价演示 WS 语义）；生产环境替换为基于 `spring-websocket`(STOMP) 的实现即可，**中继与发件箱逻辑不变**。
- **Aspect 可选接入**：`AgentSkillAspect` 经 `ObjectProvider<GovernanceDecisionRecorder>` 接入——存在 DataSource 时走事务落库，否则退回旧的纯内存执行（轻量场景兼容，**StarterDemo 5/5 不受影响**）。

> 设计取舍：离线环境缺 `spring-websocket` 与 `jackson`，故 WS 用内存总线等价实现、序列化用手工字段落库而非 JSON，保证**可编译 + 逻辑正确 + 单测**；真实 STOMP WS 仅替换 `ApprovalEventPublisher` 实现类。

---

## 3. Dev 实现清单（代码成果）

新增文件（均在 `towndemontower-spring-starter/src/.../spring/tx/`）：

| 文件 | 类型 | 说明 |
|------|------|------|
| `ApprovalEvent.java` | 数据 | 推送给审批者的治理事件（decisionId/tool/risk/code/status/ticket/eventType） |
| `GovernanceDecisionCommittedEvent.java` | 事件 | 事务提交后事件，携带 decisionId 触发中继 |
| `ApprovalEventPublisher.java` | 接口 | WS 推送抽象 |
| `InMemoryWebSocketBroker.java` | 默认实现 | 进程内发布/订阅总线（等价 WS） |
| `GovernanceDecisionRecorder.java` | 接口 | 事务一致性记录器抽象 |
| `TransactionalGovernanceDecisionRecorder.java` | 实现 | `@Transactional` 落库 + 写 Outbox + 发提交后事件 |
| `GovernanceDecisionRepository.java` | JDBC | 决策落库 |
| `OutboxRepository.java` | JDBC | Outbox 读写/状态更新 |
| `OutboxMessage.java` | 数据 | Outbox 行映射 |
| `OutboxRelay.java` | 中继 | AFTER_COMMIT 发布 + 定时轮询兜底 |

修改文件：
- `pom.xml`：新增 `spring-boot-starter-jdbc`（compile）、`h2`（test，不污染消费方）。
- `TownDemontowerAutoConfiguration.java`：`@EnableScheduling` + `@AutoConfigureAfter(DataSourceAutoConfiguration.class)`；新增 publisher / 两个 repository / recorder / relay 五个 `@Bean`（均 `@ConditionalOnBean(DataSource.class)` + `towndemontower.tx.enabled` 开关）；publisher 为 `@ConditionalOnMissingBean`（可被 STOMP 实现覆盖）。
- `AgentSkillAspect.java`：构造器增加 `ObjectProvider<GovernanceDecisionRecorder>`，有则走事务落库路径，无则退回 `executor.execute`。

Schema（`src/test/resources/schema.sql`，仅 starter 自身测试用；消费方按自己 DB 提供等价表）：
```sql
CREATE TABLE governance_decision (
  id VARCHAR(36) PRIMARY KEY, tool VARCHAR(128) NOT NULL, risk VARCHAR(8),
  code VARCHAR(64), decision_status VARCHAR(32) NOT NULL, approval_ticket_id VARCHAR(64),
  args_json VARCHAR(2000), created_at TIMESTAMP NOT NULL);
CREATE TABLE outbox_message (
  id VARCHAR(36) PRIMARY KEY, decision_id VARCHAR(36) NOT NULL, event_type VARCHAR(64) NOT NULL,
  tool VARCHAR(128), risk VARCHAR(8), code VARCHAR(64), decision_status VARCHAR(32),
  approval_ticket_id VARCHAR(64), publish_status VARCHAR(16) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL);
```

---

## 4. QA 验证结果

- **事务一致性集成测试**：`com.towndemontower.spring.tx.TownDemontowerTxTest`（真实 Spring Boot + 内嵌 H2）→ **pass=14 fail=0**
  - R2 待审批：落库 + Outbox(PENDING) + WS 推送 + 提交后标记 PUBLISHED；
  - R3 拒绝 / R1 放行：同样落库 + 推送；决策总数与 WS 事件数一致；
  - 审批事件内容正确（`APPROVAL_REQUESTED` + 单号 + 工具名）；
  - WS 不可用：Outbox 保持 PENDING 并累加重试、事件未真正送达；恢复后 `relay.poll()` 补发并标记 PUBLISHED。
- **回归**：`StarterDemo` 5/5 无回归（无 DataSource 场景退回旧行为）。
- **编译**：JDK 21 离线 `mvn -o -DskipTests test-compile` rc=0（因 JDK21+surefire 3.2.5 有 LinkageError，测试以 main 方式运行）。

运行命令：
```bash
cd towndemontower-spring-starter
mvn -o -DskipTests test-compile
mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt
"/c/Program Files/Java/jdk-21.0.12/bin/java" -cp "target/classes;target/test-classes;$(cat cp.txt)" com.towndemontower.spring.tx.TownDemontowerTxTest
"/c/Program Files/Java/jdk-21.0.12/bin/java" -cp "target/classes;target/test-classes;$(cat cp.txt)" com.towndemontower.spring.StarterDemo
```

---

## 5. Ops 说明（如何使用 / 遗留）

**启用（有 DataSource 的 Spring Boot 应用）**
- 引入 `towndemontower-spring-starter` + 任意 `DataSource`（H2/Postgres/MySQL…）。存在 DataSource 时事务一致性自动开启；可用 `towndemontower.tx.enabled=false` 关闭。
- 提供 `governance_decision` 与 `outbox_message` 两张表（DDL 见上，按目标库方言微调）。
- 审批者/Ops 面板接入 `ApprovalEventPublisher`：默认 `InMemoryWebSocketBroker`（进程内）；生产替换为 STOMP WS 实现（实现同一接口即可，发件箱与中继不变）。

**一致性保证**
- 决策落库与待发事件同事务：提交即"都有"，回滚即"都无"。
- 提交后事件 → 中继发布 → 成功则标记 PUBLISHED；失败则 `retry_count+1` 保留 PENDING。
- `@Scheduled` 每 5s 轮询 PENDING 行补发，覆盖崩溃/瞬时不可用；超过 `MAX_RETRY` 停止自动重试（应接告警/人工）。

**遗留 / 下一步（v1.2.1 后续）**
1. `ApprovalPolicy` 真实审批网关（用户/IM/CLI/转人工终端）。
2. starter 装配 Docker 沙箱切换开关（把最内层 Executor 换为 `DockerSandboxExecutor`）。
3. 生产 WS 实现：基于 `spring-websocket`(STOMP) 的 `ApprovalEventPublisher`（离线环境缺该 jar，未编译；接口已预留）。
