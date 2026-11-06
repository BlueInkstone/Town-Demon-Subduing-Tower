# Town Demon-Subduing Tower — Phase 1.2 实现交付（Runtime & Infra 增强）

> 上游输入：`docs/08-版本任务清单.md` 中 V1.2 分组。
> 本阶段角色推进：**PM 定位 → Arch 接口划分 → Dev 实现 → QA 验证 → Ops 说明**。
> 约束延续：core 纯 Java、零 Spring 依赖；契约语言中立；可审计、可取消、可回滚。

---

## 1. PM 定位（做什么 / 为什么）

V1.2 把 Phase 0/1 中**只有接口与桩**的能力变成**真实可落地的运行时治理**：

| 目标 | 来源 | 优先级 |
|------|------|--------|
| 取消令牌贯穿 SkillExecutor/沙箱/Broker（急停在途调用） | NFR-5 | P0 |
| PipelineState 文件持久化（落盘/恢复一致） | FR-13 | P1 |
| 门禁脚本运行时（签名校验+重试≤3+超限 escalate） | FR-10 | P1（运行时在 v1.0 已具桩，本阶段补验证） |
| 凭据代理运行时（注入/轮换/脱敏/哈希审计，禁硬编码） | V1.2 | P0 |
| AST 增强（管道 `|` 与子 shell `$(...)` 覆盖） | 缺陷修复 | P1 |
| 进程级沙箱策略（容器级沙箱的过渡隔离实现） | V1.2 | P0 |

**明确排后到 V1.2.1**：Docker 容器级沙箱（替换进程策略即可，链路不变）、starter 侧 `@Transactional`/Outbox/WS 事务一致性（需 Spring 事务基础设施）。本阶段已提供 `SandboxStrategy` 解耦点，使上述替换无需改动装饰器链。

---

## 2. Arch 接口与划分

- **CancellationToken**（`executor`）：`cancel()` / `isCancelled()`，线程安全；进入 `ExecutionContext`（向后兼容，旧 7 参构造委托 8 参，token 可 null）。
- **PipelineStateStore** 新增实现 `FilePipelineStateStore`（`context`），接口不变。
- **CredentialBroker**（`credential`，新包）：`register/rotate/revoke/redact/inject/secretHashes`，线程安全，明文仅 inject 瞬间存在。
- **SandboxStrategy**（`decorator`，新接口）+ `ProcessSandboxStrategy`（独立 OS 进程执行）+ `ProcessSandboxExecutor`（`Executor` 实现，shell 工具硬隔离注入点）。
- **AstRisk** 增加 `subshell` 字段；`SimpleShellAstAnalyzer` 复合分隔符扩展为 `; && || |`，新增子 shell 识别；`DefaultRiskGate` 对 `subshell` 至少升 R1。
- **ErrorCodes** 新增 `CANCELLED`。

> 划分原则：所有新增均为 core 包内纯 Java；未触碰 starter；旧链路（`SandboxedToolExecutor` + invoker）默认行为不变，仅新增取消令牌检查。

---

## 3. Dev 实现清单（代码成果）

新增 / 修改文件（均在 `towndemontower-core/src/.../core/`）：

| 文件 | 类型 | 说明 |
|------|------|------|
| `executor/CancellationToken.java` | 新增 | 急停取消令牌 |
| `contract/ExecutionContext.java` | 修改 | 新增 `cancellationToken` 字段 + 8 参构造（兼容旧） |
| `contract/ErrorCodes.java` | 修改 | 新增 `CANCELLED` |
| `decorator/SandboxedToolExecutor.java` | 修改 | 执行前检查取消令牌 |
| `context/FilePipelineStateStore.java` | 新增 | 文件持久化状态（零依赖键值落盘） |
| `credential/CredentialBroker.java` | 新增 | 凭据代理运行时 |
| `policy/AstRisk.java` | 修改 | 增加 `subshell` |
| `policy/SimpleShellAstAnalyzer.java` | 修改 | 管道 + 子 shell 识别 |
| `policy/DefaultRiskGate.java` | 修改 | 子 shell 至少升 R1 |
| `decorator/SandboxStrategy.java` | 新增 | 沙箱策略接口 |
| `decorator/ProcessSandboxStrategy.java` | 新增 | 进程级隔离（跨平台选 shell） |
| `decorator/ProcessSandboxExecutor.java` | 新增 | 进程沙箱执行器（实现 Executor） |
| `src/test/.../V12FeatureTest.java` | 新增 | V1.2 功能测试 22 项 |

**已确认在 v1.0/v1.1 已交付、本阶段不再重复**：`SimpleModelRouter`（路由落地）、`GuardianPrecheckImpl`（预审落地）、`GateScript` 运行时（签名/重试/升级）。

---

## 4. QA 验证结果

- **V1.2 功能测试**：`com.towndemontower.core.V12FeatureTest` → **pass=22 fail=0**（凭据脱敏/注入/轮换/哈希审计、状态落盘恢复、AST 管道/子 shell/重定向、子 shell 风险升级、进程沙箱 echo + 取消急停、Executor 接入、取消令牌贯穿、门禁签名/重试/升级）。
- **回归**：Phase 0 `CoreTest` 仍 **34/34 通过**，无破坏。
- **编译**：JDK 21 离线编译 `rc=0`。

运行命令：
```bash
cd towndemontower-core
"/c/Program Files/Java/jdk-21.0.12/bin/javac" -d out $(find src/main/java src/test/java -name "*.java")
"/c/Program Files/Java/jdk-21.0.12/bin/java"  -cp out com.towndemontower.core.V12FeatureTest
"/c/Program Files/Java/jdk-21.0.12/bin/java"  -cp out com.towndemontower.core.CoreTest
```

---

## 5. Ops 说明（如何使用 / 遗留）

**使用方式（core 侧）**
- 急停：`CancellationToken token = new CancellationToken("task-x"); ... token.cancel();` → 沙箱/工具执行前即返回 `CANCELLED`。
- 状态持久化：`new FilePipelineStateStore(Paths.get("./state")); store.save(taskId, state); store.load(taskId);`
- 凭据：`CredentialBroker b = new CredentialBroker(); b.register("DB_PWD", secret); b.redact(logText); b.inject(template); b.secretHashes()`（审计只留哈希）。
- 硬隔离沙箱（可选注入）：`new ProcessSandboxExecutor(registry, new ProcessSandboxStrategy(10000), token)`，专用于 shell 类工具；普通工具仍走原 `SandboxedToolExecutor`。

**遗留 / 下一步（V1.2.1）**
1. Docker 容器级沙箱：实现 `SandboxStrategy` 的容器版（资源/网络/文件系统边界），替换 `ProcessSandboxStrategy`，装饰器链零改动。
2. starter 事务一致性：`@Transactional` + Outbox + WS 事件总线（需 Spring 事务基础设施，属 starter 侧）。
3. 审批网关接入（`ApprovalPolicy` 真实用户/IM/CLI 实现）—— 接口与 `HumanApprovalPolicy` 已在 v1.0，本阶段未扩，建议在 V1.2.1 或 v1.3 落地。

> 注：本阶段未改动 `towndemontower-spring-starter`；其仍可正常编译运行（已重新验证 5/5）。
