# Arch 阶段交付物：Town Demon-Subduing Tower 架构方案

> 本阶段角色：**Arch（架构师）** —— 只回答「系统怎么拆、数据怎么流、接口怎么定」，不写业务代码、不碰 Spring 配置。
> 上游输入：`docs/01-PM-需求说明.md`（构建目标 / FR / AC）
> 下游输出（作为 Dev 的输入）：本文档的**包结构、契约 POJO、状态机转换表、装饰器链、接口签名**

---

## 1. 分层与依赖方向（硬约束）

```
towndemontower-core（纯 Java，零外部依赖，可独立单测）
├── contract/      IntentObject、Executor、SkillDescriptor、RiskLevel、ExecutionContext、错误码
├── statemachine/  转换表 + Guard 接口 + TransitionResult（不依赖 Spring StateMachine）
├── policy/        RiskGate 分级器（R0-R3、复合取最高、未知 R2、重定向、AST 降级）
├── decorator/     Permission/Audit/Snapshot/RiskGate 装饰器（纯接口组合）
├── audit/         哈希链接口 + 追加写接口（内存实现）
├── mode/          AgentMode、ModeToolPolicy、模式转换表
├── context/       ContextSelector 接口、PipelineState、PipelineStateStore 接口
├── gate/          GateScript 接口、GateResult、门禁重试策略
├── approval/      ApprovalPolicy 接口、ApprovalDecision、GuardianPrecheck 接口
├── routing/       ModelRouter 接口、ModelProfile
└── executor/      Executor 接口 + 最小链路 execute(IntentObject)

towndemontower-spring-starter（薄适配层，本次仅定义接口约定，不实现）
└── 注解/AOP/AutoConfiguration/Transactional/Outbox/WS + PipelineStateStore 文件实现
```

**归属判据（Dev 自检）：** 任何 `import org.springframework.*` 的类属于 starter；否则属于 core。core 单测不启动 Spring。

## 2. 契约 POJO（语言中立，JSON 序列化，禁 Optional/CompletableFuture/Java 序列化）

```java
// contract/IntentObject —— 模型唯一合法输出
class IntentObject {
    String tool;            // 工具名，如 restartService
    Map<String,Object> args;
    double confidence;      // 0.0–1.0
    String reason;          // 理由
    String expectedEffect;  // 预期效果
    String rollbackHint;    // 回滚提示
}

// contract/SkillDescriptor —— 工具元数据
class SkillDescriptor {
    String name;
    String risk;            // "R0".."R3"
    String[] requiredPerms;
    String[] capabilities;
    boolean sideEffect;
    String sandboxPolicy;
}

// contract/ExecutionContext —— 执行上下文
class ExecutionContext {
    String sessionId, taskId, tenantId, agentId, ownerUserId;
    boolean tainted;        // 是否读取过不可信来源
    AgentMode mode;         // 当前模式（第三层白名单来源）
}

// contract/RiskLevel —— R0–R3，值为字符串
enum RiskLevel { R0, R1, R2, R3 }

// contract 错误码常量
class ErrorCodes {
    static final String INTENT_FORMAT_INVALID="INTENT_FORMAT_INVALID";
    static final String NEED_MORE_INFO="NEED_MORE_INFO";
    static final String R3_DENIED="R3_DENIED";
    static final String TAINTED_WRITE_BLOCKED="TAINTED_WRITE_BLOCKED";
    static final String MODE_TOOL_NOT_ALLOWED="MODE_TOOL_NOT_ALLOWED";
    static final String MODE_TRANSITION_INVALID="MODE_TRANSITION_INVALID";
    static final String PLAN_APPROVAL_REQUIRED="PLAN_APPROVAL_REQUIRED";
    static final String GATE_SCRIPT_FAILED="GATE_SCRIPT_FAILED";
    static final String AST_ANALYSIS_FALLBACK_R2="AST_ANALYSIS_FALLBACK_R2";
}
```

## 3. 状态机（core 转换表 + Guard，starter 负责事务/Outbox）

**TaskStatus 转换表（业务态唯一入口）：**

| 当前态 | 事件 | 目标态 | Guard |
|--------|------|--------|-------|
| PENDING | INIT | CODING | always |
| CODING | PLAN_APPROVED | EXECUTING | planArtifactApproved |
| EXECUTING | PAUSE | PAUSED | always |
| PAUSED | RESUME | EXECUTING | cancellationTokenValid |
| * | EMERGENCY_STOP | PAUSED | always（并发布取消令牌）|
| EXECUTING | DONE | DONE | always |

**AgentMode 转换表（执行视图，只由事件触发）：**

| 当前模式 | 事件 | 目标模式 | Guard |
|----------|------|----------|-------|
| ARCHITECT_PLANNING | PLAN_APPROVED | DEV_EXECUTION | planArtifactApproved |
| DEV_EXECUTION | ENTER_AUDIT | AUDIT_REVIEWING | always |
| AUDIT_REVIEWING | REPLAN | ARCHITECT_PLANNING | always |
| *→DEV_EXECUTION | (skip replan) | — | **拒绝 → MODE_TRANSITION_INVALID** |

非法转换（如 AUDIT_REVIEWING 跳过重规划直接进 DEV_EXECUTION）→ 抛 `ModeTransitionInvalidException`。

## 4. 装饰器链（core 纯组合，顺序即安全优先级）

```
ModeToolPolicy → IntentFormat → Schema → Capability → RBAC
  → RiskGate(AST) → ApprovalRequired → TaintCheck → Snapshot
  → SandboxedTool → Audit
```

- **ModeToolPolicy**：`allowedTools(mode)` 取交集第一层；未在白名单 → `MODE_TOOL_NOT_ALLOWED`。
- **IntentFormat**：拒绝建议文本，非意图 JSON → `INTENT_FORMAT_INVALID`。
- **RiskGate(AST)**：Shell 命令先过 AST 静态分析；解析失败 → 默认 R2（记 `AST_ANALYSIS_FALLBACK_R2`），R3 → `R3_DENIED`。
- **ApprovalRequired**：R2 挂 ApprovalGateway（pendingApproval）；R3 不进普通审批。
- **TaintCheck**：tainted 会话的写/外发额外校验 → `TAINTED_WRITE_BLOCKED`。
- **Snapshot**：写操作前落盘三样（命令原文/快照/回滚动作），失败 → `SNAPSHOT_FAILED`。
- **Audit**：**无论链路是否拒绝都追加写 + 哈希链**（满足 FR-12）。

## 5. 关键接口签名（Dev 据此实现）

```java
// executor/Executor.java
interface Executor {
    SkillResult execute(IntentObject intent, ExecutionContext ctx);
}

// policy/RiskGate.java  [core]
interface RiskGate { RiskLevel classify(SkillDescriptor d, IntentObject intent); }

// mode/ModeToolPolicy.java  [core]
interface ModeToolPolicy { Set<String> allowedTools(AgentMode mode); }

// context/PipelineStateStore.java  [core 接口 / starter 实现]
interface PipelineStateStore {
    PipelineState load(String taskId);
    void save(String taskId, PipelineState state);
}

// gate/GateScript.java  [core]
interface GateScript {
    String name(); String command(); String signedHash();
    Duration timeout(); int maxRetry();
}

// approval/ApprovalPolicy.java  [core]
interface ApprovalPolicy {
    ApprovalDecision decide(ApprovalTicket ticket, ExecutionContext ctx);
}
enum ApprovalDecision { APPROVE, DENY, ESCALATE_TO_HUMAN, REFER_MANUAL_TERMINAL }

// routing/ModelRouter.java  [core 接口]
interface ModelRouter { ModelProfile route(AgentMode mode, RiskLevel risk, String taskType); }
```

## 6. 最小链路时序（Executor.execute）

```
调用方
  → ModeToolPolicy.allowedTools(ctx.mode).contains(intent.tool)? 否→MODE_TOOL_NOT_ALLOWED
  → IntentFormat.assertIntentObject(intent)? 否→INTENT_FORMAT_INVALID
  → confidence<0.5? → NEED_MORE_INFO（非拒绝）
  → SchemaValidator.validate(d.inputSchema, args)
  → CapabilityProbe.assertWithin(intent, d.capabilities)
  → PermissionEngine.assertAllowed(ctx.agent, d.requiredPerms)
  → RiskLevel r = RiskGate.classify(d, intent)
       r==R3 → R3_DENIED
       r==R2 || d.risk.requiresApproval → ApprovalRequired → pendingApproval
       tainted && isWriteOrEgress → TaintCheck
  → Snapshot.beforeWrite(intent, d)
  → SandboxedTool.execute(...) → SkillResult.success(output)
  → Audit.recordWithHashChain(...)  // 任何分支都记
```

## 7. 可追溯性映射（PM FR → Arch 模块）

| PM FR | Arch 模块 | 验收钩子 |
|-------|-----------|----------|
| FR-1 意图格式 | IntentFormat + contract | AC-2 |
| FR-3/FR-4 风险分级 | policy/RiskGate + AST | AC-6 |
| FR-5 状态机 AND | statemachine + RiskGate | AC-6 |
| FR-6 模式白名单 | mode/ModeToolPolicy | AC-3 |
| FR-7 模式转换 | mode 转换表 | AC-5 |
| FR-9 快照/审计 | audit + decorator/Snapshot | AC-5 |
| FR-11 审批 | approval/ApprovalPolicy | AC-5 |
| FR-13 状态落盘 | context/PipelineStateStore | AC-4 |

> Arch 结论：core 包结构、契约、状态机转换表、装饰器链顺序、接口签名已定；**不引入 Spring 依赖，core 单测不启动容器**。下一阶段（Dev）严格按第 2–6 节签名实现，禁止自由发挥。
