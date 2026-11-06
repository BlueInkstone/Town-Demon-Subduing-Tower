# Town Demon-Subduing Tower —— Phase 1 适配层伪代码实现

> 适用模块：`towndemontower-spring-starter`（基于 AOP 的薄适配层，把 Spring 方法调用接入 `towndemontower-core` 治理链）。
> 本文档用伪代码描述**核心逻辑、主要处理流程、关键步骤**，与真实源码一一对应（源码见 `towndemontower-spring-starter/src/main/java/com/towndemontower/spring/`）。

---

## 0. 可用性结论

- 模块已可正常使用：`mvn -o test-compile` 通过；`StarterDemo` 端到端 5/5 通过（R1 放行 / R3 拒绝 / R2 待审批 / 模式拦截 / 审计 4 条）。
- 适配层**只做胶水**，治理逻辑（风险分级、门禁、审批、审计、状态机）全部由 core 负责；core 升级不影响本层。

---

## 1. 总体处理流程（序列）

```
业务方法调用
   │  @AgentSkill 标注的方法
   ▼
[AgentSkillAspect @Around 切点命中]
   │
   ├─(1) 懒注册 SkillDescriptor ──► SkillRegistry
   ├─(2) 读取属性：mode / agentId / sessionId / 默认置信度
   ├─(3) 构造 IntentObject（工具名、参数、风险、置信度）
   ├─(4) 构造 ExecutionContext（agent 身份、模式、上下文）
   ├─(5) delegatingInvoker.bind(pjp.proceed)   // 绑定真实业务执行
   ├─(6) executor.execute(intent, context)      // 进入 core 装饰器链
   │         │
   │         ▼  （见 §4 装饰器链）
   │
   ├─ 成功 ──► 返回 result.output() 给调用方
   └─ 拒绝/待审批 ──► 抛 GovernanceDeniedException(code, message, ticketId)
```

---

## 2. 注解与配置属性

```
// 2.1 方法级治理标记
ANNOTATION AgentSkill:
    name           : 工具名（默认取方法名）
    risk           : "R0"|"R1"|"R2"|"R3"      // 风险分级
    requiredPerms  : 字符串[]                  // 所需权限
    capabilities   : 字符串[]                  // 所需能力
    requiredArgs   : 字符串[]                  // 必需参数键
    sideEffect     : 布尔                      // 是否副作用
    sandbox        : 字符串                    // 沙箱策略
    confidence     : 浮点                      // 置信度（默认用属性值）

// 2.2 自动配置属性（spring 配置）
PROPERTIES TownDemontowerProperties:
    enabled                 : 布尔 = true     // 总开关（matchIfMissing）
    mode                    : 字符串 = "DEV_EXECUTION"  // 业务模式
    agentId                 : 字符串 = "agent-default"
    sessionId / taskId      : 字符串
    ownerUserId             : 字符串
    tainted                 : 布尔 = false
    defaultConfidence       : 浮点 = 0.6
    autoGrantDeclaredPerms  : 布尔 = true     // 自动把 @AgentSkill.perms 授权给 agent
```

---

## 3. 自动配置（条件装配 + 暴露 core 默认策略）

```
CLASS TownDemontowerAutoConfiguration:

    @ConditionalOnProperty(towndemontower.enabled=true, matchIfMissing=true)
    @ConditionalOnClass(Executor, AspectJ)         // 仅当 core + AOP 在 classpath
    @EnableConfigurationProperties(TownDemontowerProperties)

    BEAN skillRegistry          = new SkillRegistry()
    BEAN astAnalyzer            = new SimpleShellAstAnalyzer()
    BEAN riskGate               = new DefaultRiskGate(astAnalyzer)   // 仅 @ConditionalOnMissingBean
    BEAN permissionEngine       = new PermissionEngine()
    BEAN snapshotService        = new SnapshotService()
    BEAN auditStore             = new HashChainAuditStore()
    BEAN approvalPolicy         = new HumanApprovalPolicy()
    BEAN modeToolPolicy         = new DefaultModeToolPolicy()

    // 关键：用 core 工厂把默认策略串成完整装饰器链，暴露为 Executor Bean
    BEAN executor = ExecutorFactory.buildDefault(
                        skillRegistry, modeToolPolicy, riskGate,
                        permissionEngine, snapshotService,
                        auditStore, approvalPolicy, delegatingInvoker)

    BEAN agentSkillAspect = new AgentSkillAspect(executor, skillRegistry,
                                                 permissionEngine, properties)
```

> 条件规则：`@ConditionalOnMissingBean` 保证**用户自定义优先**——应用中自己定义一个 `Executor`/`RiskGate` Bean，适配层自动让位。

---

## 4. core 装饰器链（职责核心，外→内）

```
FUNCTION executor.execute(intent, context):
    RETURN Audit(                                   // 最外层：任何结果/异常都审计
      ModeToolPolicy(                               // 模式白名单：工具是否允许当前模式
        IntentFormat(                               // 意图格式/参数校验
          Schema(                                   // requiredArgs 完整性
            Capability(                             // 能力校验（按需）
              Permission(                           // 权限校验：assertAllowedByAgent
                RiskGate(                           // 风险分级：R0/R1 放行, R3 拒绝, R2 待审批
                  TaintCheck(                       // 污染检查
                    Snapshot(                       // 执行前快照
                      Sandbox(                      // 沙箱执行（调用真实业务）
                        invoker.invoke(intent, context)
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
    ).execute(intent, context)
```

每个装饰器 `D` 的通用骨架：

```
DECORATOR D(next):
    FUNCTION execute(intent, context):
        pre = before(intent, context)              // 本层校验（不通过→返回 denied/pending，不调 next）
        IF pre 不通过:
            RETURN SkillResult(code=拒绝码, message=原因)
        result = next.execute(intent, context)     // 下沉到下一层
        RETURN after(result)                       // 本层后置处理
```

各层关键判定（伪代码）：

```
ModeToolPolicy.before:
    IF NOT modeToolPolicy.allow(mode, intent.toolName):
        RETURN denied("MODE_TOOL_NOT_ALLOWED", mode + " 不允许工具 " + toolName)

RiskGate.before:
    level = descriptor.risk == null ? R2 : parse(descriptor.risk)
    IF level == R3:
        RETURN denied("R3_DENIED", "R3 命令一票否决")
    IF level == R2:
        ticket = approvalPolicy.createTicket(intent, context)   // 生成审批单
        RETURN pendingApproval(ticket.id)                        // 不直接执行
    // R0/R1 继续下沉

Permission.before:
    TRY permissionEngine.assertAllowedByAgent(agentId, descriptor)
    CATCH PermissionDeniedException:
        THROW PermissionDeniedException  // 由 Audit 捕获并记为 denied

Sandbox.execute（链尾）:
    output = invoker.invoke(intent, context)        // = pjp.proceed() 真实业务
    RETURN success(output)
```

---

## 5. 桥接器（把 Spring 业务方法注入链尾）

```
CLASS DelegatingSkillInvoker implements SkillInvoker:
    ThreadLocal<Function<IntentObject, Object>> bound = new ThreadLocal<>()

    FUNCTION bind(realCall): bound.set(realCall)     // AOP 调用前绑定 pjp.proceed
    FUNCTION unbind():        bound.remove()
    FUNCTION invoke(intent, context):
        IF bound.get() == null: THROW "未绑定业务执行"
        RETURN bound.get().apply(intent)            // 实际执行被标注的方法体
```

> 关键点：`SandboxedToolExecutor` 把 `invoker.invoke(...)` 的返回值作为 `SkillResult.output` 回传，因此 core **一行不改**即可接入 Spring 方法。

---

## 6. AOP 切面核心逻辑

```
CLASS AgentSkillAspect:

    @Around("@annotation(agentSkill)")
    FUNCTION around(pjp, agentSkill) -> Object:
        // 1) 懒注册工具描述符（仅首次）
        descriptor = skillRegistry.lookup(agentSkill.name)
        IF descriptor == null:
            descriptor = buildDescriptor(agentSkill)   // 由注解构造
            skillRegistry.register(descriptor)
            IF properties.autoGrantDeclaredPerms:
                permissionEngine.grant(agentId, agentSkill.requiredPerms)

        // 2) 构造意图与上下文
        params = resolveParamNames(pjp)             // 依赖 -parameters 编译
        intent = IntentObject(
                     toolName   = agentSkill.name,
                     args       = mapArgs(pjp.args, params),
                     risk       = agentSkill.risk,
                     confidence = agentSkill.confidence ?: properties.defaultConfidence,
                     suggested  = agentSkill.suggestedText ?: null)
        ctx = ExecutionContext(
                     agentId   = properties.agentId,
                     mode      = parseMode(properties.mode),
                     sessionId = properties.sessionId,
                     taskId    = properties.taskId,
                     tainted   = properties.tainted)

        // 3) 执行治理链
        delegatingInvoker.bind(pjp::proceed)        // 桥接真实业务
        TRY:
            result = executor.execute(intent, ctx)
            IF result.type == DENIED:
                THROW GovernanceDeniedException(result.code, result.message, null)
            IF result.type == PENDING_APPROVAL:
                THROW GovernanceDeniedException("PENDING_APPROVAL", "待人工审批", result.approvalTicketId)
            RETURN result.output()                 // 成功：透传业务返回值
        FINALLY:
            delegatingInvoker.unbind()
```

---

## 7. 结果映射与异常语义

```
result.type = SUCCESS         → 返回 result.output() 给调用方
result.type = DENIED          → GovernanceDeniedException(code, message, null)
result.type = PENDING_APPROVAL→ GovernanceDeniedException("PENDING_APPROVAL", msg, ticketId)
                                  // 上层可据此跳转审批流程

异常统一经 Audit 装饰器落盘（哈希链），无论放行/拒绝均有审计记录可追。
```

---

## 8. 关键步骤小结（按执行顺序）

1. **装配**：Spring 启动时，自动配置按条件把 core 默认策略 Bean 化，并经 `ExecutorFactory.buildDefault` 串成 `Executor`。
2. **命中切点**：业务方法被 `@AgentSkill` 标注，AOP 拦截。
3. **注册工具**：首次调用时懒注册 `SkillDescriptor`，并按属性自动授权所需权限。
4. **翻译意图**：注解 + 方法参数 → `IntentObject` + `ExecutionContext`。
5. **桥接业务**：`DelegatingSkillInvoker.bind(pjp.proceed)`，使链尾沙箱执行即调用真实方法。
6. **治理链判定**：模式 → 格式 → Schema → 能力 → 权限 → 风险（R3 一票否决 / R2 进审批 / R0·R1 放行）。
7. **结果映射**：成功透传返回值；拒绝/待审批抛 `GovernanceDeniedException`；审计全程落盘。

---

## 9. 复现（与源码一致）

```bash
# core 已离线落库；starter 端到端验证：
cd towndemontower-spring-starter
mvn -o -Dmaven.test.skip=true package        # 构建 jar
# 运行验证器（需先生成 cp.txt：mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt）
java -cp "target/classes;target/test-classes;$(cat cp.txt)" \
     com.towndemontower.spring.StarterDemo
# 预期：pass=5 fail=0
```
