# Phase 1 — Spring 薄适配层设计（towndemontower-spring-starter）

> 承接 Phase 0（core 骨架 + v1.3 契约）。本阶段在 **不改动 core 一行代码** 的前提下，用一层 Spring
> 适配层把 core 的治理内核"接进"Spring 应用：AOP 拦截业务方法 → 翻译为 core 的 `IntentObject` +
> `ExecutionContext` → 走 core 装饰器链默认策略 → 结果映射回方法返回值 / 治理异常。
>
> 验证事实：离线 `mvn -o test-compile` 通过；`StarterDemo` 端到端 **5/5 通过**（R1 放行 / R3 拒绝 /
> R2 待审批带单号 / 模式白名单拦截 / 审计 4 条落链）。

---

## 1. 职责边界（薄适配层能做什么、不做什么）

适配层只做 **"翻译 + 装配 + 暴露"**，不实现任何治理规则。

| 适配层负责（薄） | 治理规则（在 core，不在此层） |
|---|---|
| 把 `@AgentSkill` 方法调用 → `IntentObject`（工具名/参数/置信度） | 模式白名单判定（ModeToolPolicy） |
| 把属性配置 → `ExecutionContext`（mode/agentId/tenantId…） | 意图格式 / Schema / 能力校验 |
| 懒注册 `SkillDescriptor`、默认自动授权声明权限给 agent | RBAC 裁决（PermissionEngine） |
| 经 `DelegatingSkillInvoker` 把 `pjp.proceed()` 注入链尾 | 风险分级与闸门（RiskGate / R0-R3） |
| 把 `SkillResult` → 返回值 / `GovernanceDeniedException` | 审批网关（ApprovalPolicy） |
| Spring 自动配置装配全部默认策略 Bean + 注册切面 | 快照 / 沙箱 / 审计哈希链 |
| 暴露 `Executor` Bean 供业务/测试直接使用 | 上述全部 |

**刻意不做**：不引入新的风险等级、不重写门禁、不做凭据代理、不做容器沙箱——这些机制仍由 core
硬裁决，适配层只负责"把流量导进 core 默认策略"。这也是 Phase 0→1 的接口契约演进方式。

---

## 2. 切点定义（Pointcut）

```java
@Aspect
public class AgentSkillAspect {
    @Around("@annotation(agentSkill)")
    public Object govern(ProceedingJoinPoint pjp, AgentSkill agentSkill) throws Throwable { ... }
}
```

- **切点**：所有被 `@AgentSkill` 标注的 **方法**（`@annotation(com.towndemontower.spring.AgentSkill)`）。
- **拦截位置**：方法执行前进入 core 链；只有链全部放行，`pjp.proceed()` 才在链尾
  `SandboxedToolExecutor` 中被调用（即真实方法仅在治理通过后才执行 → 天然 human-in-the-loop）。
- **参数映射**：实参按 **形参名** 映射为 `IntentObject.args`（依赖编译器 `-parameters`，已在 pom 开启）；
  形参名同时作为 Schema 校验的必需参数键（与注解 `requiredArgs` 对应）。
- **结果映射**：
  - `SkillResult.success` → 返回 `output`（即真实方法返回值）；
  - 链路返回 `denied` / `pendingApproval` → 抛 `GovernanceDeniedException(code, message, approvalTicketId)`，
    调用方可据 `getCode()` 区分 `R3_DENIED` / `PENDING_APPROVAL` / `MODE_TOOL_NOT_ALLOWED` /
    `PERMISSION_DENIED` 等，用 `getApprovalTicketId()` 取审批单号。

### `@AgentSkill` 注解字段（↔ core `SkillDescriptor`）

| 注解字段 | 含义 | 映射到 SkillDescriptor |
|---|---|---|
| `name` | 工具名（必填，唯一） | `name` / `IntentObject.tool` |
| `risk` | `"R0".."R3"`，默认 `"R2"` | `risk` |
| `requiredPerms` | 所需权限 | `requiredPerms`（默认自动授权给 agent） |
| `capabilities` | 能力范围 | `capabilities` |
| `requiredArgs` | 必需参数键 | `requiredArgs`（对应形参名） |
| `sideEffect` | 是否副作用 | `sideEffect`（影响快照） |
| `sandbox` | sandbox 策略 | `sandboxPolicy` |
| `confidence` | 置信度 0–1 | `IntentObject.confidence` |
| `reason` / `expectedEffect` / `rollbackHint` | 上下文 | `IntentObject` 对应字段 |

---

## 3. 自动配置类与条件装配规则

配置类：`com.towndemontower.spring.TownDemontowerAutoConfiguration`
注册文件：`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```java
@AutoConfiguration
@ConditionalOnClass({Executor.class})                                  // ①
@ConditionalOnProperty(prefix = "towndemontower", name = "enabled",
        havingValue = "true", matchIfMissing = true)                  // ②
@EnableConfigurationProperties(TownDemontowerProperties.class)        // ③
public class TownDemontowerAutoConfiguration { ... }
```

| 条件 | 作用 |
|---|---|
| **① `@ConditionalOnClass(Executor.class)`** | classpath 必须存在 core 的 `Executor`（即已引入 `towndemontower-core`）。否则整个适配层不装配，避免无 core 时启动失败。 |
| **② `@ConditionalOnProperty(... enabled, matchIfMissing=true)`** | `towndemontower.enabled=false` 时全量不装配；默认开启。业务可一键关停治理。 |
| **③ `@EnableConfigurationProperties`** | 绑定 `towndemontower.*` 配置到 `TownDemontowerProperties`（`mode` / `agentId` / `tenantId` / `autoGrantDeclaredPerms` / `enabled` …）。 |

### Bean 装配（全部 `@ConditionalOnMissingBean` → 可被用户覆盖）

| Bean | 类型 | 默认实现 | 可被覆盖 |
|---|---|---|---|
| `townDemontowerSkillRegistry` | `SkillRegistry` | `new SkillRegistry()`（预注册 11 个工具） | ✅ |
| `townDemontowerShellAstAnalyzer` | `ShellAstAnalyzer` | `SimpleShellAstAnalyzer` | ✅ |
| `townDemontowerRiskGate` | `RiskGate` | `DefaultRiskGate(analyzer)` | ✅ |
| `townDemontowerPermissionEngine` | `PermissionEngine` | `new PermissionEngine()` | ✅ |
| `townDemontowerSnapshotService` | `SnapshotService` | `new SnapshotService()` | ✅ |
| `townDemontowerAuditStore` | `AuditStore` | `HashChainAuditStore` | ✅ |
| `townDemontowerApprovalPolicy` | `ApprovalPolicy` | `HumanApprovalPolicy` | ✅ |
| `townDemontowerModeToolPolicy` | `ModeToolPolicy` | `DefaultModeToolPolicy` | ✅ |
| `townDemontowerSkillInvoker` | `SkillInvoker` | `DelegatingSkillInvoker` | ✅ |
| `townDemontowerExecutor` | `Executor` | `ExecutorFactory.buildDefault(...)` 组装整条装饰器链 | ✅ |
| `agentSkillAspect` | `AgentSkillAspect` | 绑定上述 `Executor` / `Registry` / `PermissionEngine` / `Invoker` / `Properties` | ✅ |

> **关键点**：每个默认策略都带 `@ConditionalOnMissingBean`，因此业务只要提供一个同类型 Bean，
> 适配层即无缝接入**用户的**治理策略（如自定义 `RiskGate` / `ApprovalPolicy` / `ModeToolPolicy`），
> 而无需改任何代码。

---

## 4. 如何衔接并暴露 core 默认策略

```
  业务方法 @AgentSkill                 Spring 容器
  ───────────────────                ─────────────────────────────
  demo.restartService(svc)  ──▶  AgentSkillAspect.@Around
                                    │  ① 懒注册 SkillDescriptor + 自动授权 perms
                                    │  ② 实参 → IntentObject.args（按形参名）
                                    │  ③ 属性 → ExecutionContext（mode=DEV 等）
                                    │  ④ 绑定 pjp.proceed() 到 DelegatingSkillInvoker.CURRENT
                                    ▼
                          executor.execute(intent, ctx)   ← core Executor Bean
                                    │
        ┌─────────────────────────── 装饰器链（buildDefault 装配，顺序外→内）──────────────────────────┐
        │ Audit → ModeToolPolicy → IntentFormat → Schema → Capability → Permission → RiskGate →      │
        │ TaintCheck → Snapshot → SandboxedToolExecutor                                                   │
        └────────────────────────────────────────────────────────────────────────────────────────────┘
                                    │ 链尾调用 invoker.invoke() == DelegatingSkillInvoker
                                    │   → CURRENT 取出本次绑定的 perCall invoker
                                    ▼
                              pjp.proceed()  ← 真实方法体执行（仅当治理通过）
                                    │
                          返回 output → SkillResult.success
                                    ▼
                 AgentSkillAspect：return output   （失败/待审批 → GovernanceDeniedException）
```

- **衔接点（桥）**：`DelegatingSkillInvoker` 用 `ThreadLocal<SkillInvoker> CURRENT` 在 AOP 拦截瞬间
  把"本次调用的 `pjp.proceed()`"塞进去，链尾 `SandboxedToolExecutor` 取出执行。**core 零改动**，
  仅由适配层提供这条透传通道。
- **暴露点**：自动配置把组装好的 `Executor` 作为 Spring Bean 暴露，业务代码 `@Autowired Executor` 即可
  脱离 AOP、直接以编程方式提交意图（适配器与编程式入口共用同一套默认策略）。
- **默认即安全**：YAML 不改时，core 的全部硬性裁决（模式白名单 / 风险闸门 / 审批 / 审计）默认生效；
  `autoGrantDeclaredPerms=true` 仅为了方便开箱即用，关闭后由真实 RBAC 数据驱动。

---

## 5. 使用与验证

```yaml
# application.yml
towndemontower:
  enabled: true
  mode: DEV_EXECUTION            # ARCHITECT_PLANNING / DEV_EXECUTION / AUDIT_REVIEWING
  agent-id: agent-default
  auto-grant-declared-perms: true
```

```java
@Component
public class OpsSkills {
    @AgentSkill(name = "restartService", risk = "R1",
                requiredPerms = {"ops:restart"}, requiredArgs = {"service"})
    public String restartService(String service) { return "restarted:" + service; }
}
```

验证命令（离线）：

```bash
cd towndemontower-spring-starter
mvn -o test-compile
# classpath:
mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt
"/c/Program Files/Java/jdk-21.0.12/bin/java" -cp "target/classes;target/test-classes;$(cat cp.txt)" \
    com.towndemontower.spring.StarterDemo
# 期望：RESULT pass=5 fail=0
```

（注：本地 JDK 21 + surefire 3.2.5 有 LinkageError，故用 `StarterDemo` 的 `main` 离线验证；
`TownDemontowerStarterTest` 为等效 JUnit 集成测试，供升级 surefire 后的真实 CI 使用。）

---

## 6. 范围与下一步

**本 Phase 交付**：starter 模块、AOP 切面、`@AgentSkill` 注解、自动配置 + 条件装配、示例 Bean、验证器。
**刻意未做**（属后续 Phase / 真实运行时）：`@AgentSkill` 在 `@Bean` 工厂方法上的拦截、凭据代理、
容器级沙箱、War Room 面板、审批单持久化与超时自动拒绝、多 Agent 编排。这些在 README §8.5/§8.6/§10.6
列为 Phase 1+。
