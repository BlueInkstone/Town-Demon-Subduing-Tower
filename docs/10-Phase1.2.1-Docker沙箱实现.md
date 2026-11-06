# Town Demon-Subduing Tower — Phase 1.2.1 实现交付（Docker 容器沙箱）

> 上游输入：`docs/08-版本任务清单.md` 中 v1.2.1 分组（Docker 容器级沙箱首批）。
> 本阶段角色推进：**PM 定位 → Arch 接口划分 → Dev 实现 → QA 验证 → Ops 说明**。
> 约束延续：core 纯 Java、零 Spring 依赖；仅替换 v1.2 预留的 `SandboxStrategy` 解耦点，装饰器链零改动。

---

## 1. PM 定位（做什么 / 为什么）

V1.2 已实现**进程级沙箱**（`ProcessSandboxStrategy`，独立 OS 进程），但其隔离边界仍受宿主环境影响（文件系统/网络/算力未真正隔离）。本批把沙箱升级为**容器级隔离**，满足对不可信命令更强的安全边界。

| 目标 | 来源 | 优先级 |
|------|------|--------|
| 容器级隔离执行 shell 命令 | V1.2.1 | P0 |
| 网络隔离（`--network none`） | 安全基线 | P0 |
| 资源上限（`--memory`）与降权（`--cap-drop ALL`） | 安全基线 | P0 |
| 只读根文件系统（`--read-only`） | 安全基线 | P0 |
| docker 不可用时的优雅降级 | 可用性 | P0 |

**不在本批**（仍排后到 v1.2.1 后续）：starter 事务一致性（`@Transactional`/Outbox/WS）、`ApprovalPolicy` 真实审批网关。

---

## 2. Arch 接口与划分

- 复用 v1.2 已定义的 `SandboxStrategy` 接口（`String execute(String command, CancellationToken token)`），**新增** `DockerSandboxStrategy` 实现，**无需改动**装饰器链与 `Executor` 接口。
- 新增 `DockerSandboxExecutor` 实现 `Executor`，与 `ProcessSandboxExecutor` 同形，仅把 `SandboxStrategy` 换成容器版；装配时把最内层 Executor 换成本类即可。
- 隔离基线在 `buildDockerArgs(String command)` 纯函数中集中拼装，便于单测断言与运维审计：
  - `run --rm -i`（退出即清理、交互式透传命令）
  - `--network none`（网络隔离）
  - `--memory <n>m`（内存上限）
  - `--cap-drop ALL`（丢弃全部 Linux capabilities）
  - `--read-only`（只读根文件系统，写需显式挂载卷）
  - 末尾 `<image> <shell> -c <command>`（命令作为容器内 shell 的单个参数，避免污染宿主 docker 调用）
- `isDockerAvailable()` 静态自检；`docker` CLI 缺失/启动失败时抛 `DockerUnavailableException`，供 Executor 映射为 `SANDBOX_UNAVAILABLE`。
- `ErrorCodes` 新增 `SANDBOX_UNAVAILABLE`。

> 取消令牌（NFR-5）复用 v1.2 的 `CancellationToken`：执行前已取消直接抛 `CancellationException`；执行中由守护线程监听，触发即 `destroyForcibly()`。

---

## 3. Dev 实现清单（代码成果）

新增 / 修改文件（均在 `towndemontower-core/src/.../core/`）：

| 文件 | 类型 | 说明 |
|------|------|------|
| `contract/ErrorCodes.java` | 修改 | 新增 `SANDBOX_UNAVAILABLE` |
| `decorator/DockerSandboxStrategy.java` | 新增 | 容器级沙箱策略：`buildDockerArgs` / `isDockerAvailable` / 取消令牌守护线程 |
| `decorator/DockerSandboxExecutor.java` | 新增 | 容器沙箱执行器（实现 `Executor`），未知工具/缺命令/取消/不可用均返回明确拒绝码 |
| `src/test/.../V121DockerSandboxTest.java` | 新增 | V1.2.1 功能测试 14 项 |

**装配约定（调用方）**：保持装饰器链不变，仅需把最内层 Executor 由 `SandboxedToolExecutor` / `ProcessSandboxExecutor` 替换为：
```java
DockerSandboxStrategy strat = new DockerSandboxStrategy(10_000); // 10s 超时
Executor dockerInner = new DockerSandboxExecutor(registry, strat, token);
```
starter 侧如需启用，可在 `ExecutorFactory.buildDefault` 暴露点按配置切换（属 P2 接线，本批未强制）。

---

## 4. QA 验证结果

- **V1.2.1 功能测试**：`com.towndemontower.core.V121DockerSandboxTest` → **pass=14 fail=0**
  - 隔离基线参数装配（rm / network-none / cap-drop ALL / memory / read-only / image / shell）
  - 自定义镜像与 shell 生效、可关闭 network/read-only
  - 执行器拒绝路径：缺 `args.command` → `INTENT_FORMAT_INVALID`；未知工具 → `MODE_TOOL_NOT_ALLOWED`；调用前取消令牌 → `CANCELLED`
  - docker 不可用时的优雅降级：返回明确拒绝码（`SANDBOX_UNAVAILABLE` 或 `GATE_SCRIPT_FAILED`），不抛未捕获异常
- **回归**：
  - `V12FeatureTest`（进程沙箱等 V1.2 功能）→ **22/22 无回归**
  - `CoreTest`（Phase 0 验收）→ **34/34 无回归**
- **编译**：JDK 21 离线编译 `rc=0`。

> 说明：当前 CI/本机未安装或无法连接 docker daemon，故真实 `docker run` 路径由"可用性探测→降级"分支覆盖；命令装配逻辑已通过纯函数 `buildDockerArgs` 完整断言。在有 docker 的环境，`V121DockerSandboxTest` 会自动改跑真实 `echo container-ok` 验证。

运行命令：
```bash
cd towndemontower-core
"/c/Program Files/Java/jdk-21.0.12/bin/javac" -d out $(find src/main/java src/test/java -name "*.java")
"/c/Program Files/Java/jdk-21.0.12/bin/java"  -cp out com.towndemontower.core.V121DockerSandboxTest
"/c/Program Files/Java/jdk-21.0.12/bin/java"  -cp out com.towndemontower.core.V12FeatureTest
"/c/Program Files/Java/jdk-21.0.12/bin/java"  -cp out com.towndemontower.core.CoreTest
```

---

## 5. Ops 说明（如何使用 / 遗留）

**core 侧启用容器沙箱**
```java
DockerSandboxStrategy strat = new DockerSandboxStrategy(10_000);   // 默认 alpine:latest + 128m + 网络隔离 + 降权 + 只读根
Executor inner = new DockerSandboxExecutor(registry, strat, token);
// 自定义：ubuntu + bash + 关闭网络隔离与只读
// new DockerSandboxStrategy("ubuntu:22.04", "bash", 5000, 256, false, false, List.of());
```
- 命令取自 `intent.args["command"]`，作为容器内 shell 的单个 `-c` 参数，宿主侧不可被注入。
- 写需求（如生成产物）需通过 `extraArgs` 显式挂载卷（`-v host:container`），配合 `--read-only` 使用。

**告警与降级**
- 若宿主机无 `docker` CLI 或 daemon 不可达，执行器返回 `SANDBOX_UNAVAILABLE` / `GATE_SCRIPT_FAILED`，**不会**使调用方线程崩溃；运维应在 War Room（v1.3）对沙箱不可用语设置告警。
- 超时（默认 10s）由 `waitFor(timeout)` 控制，超时即强杀容器并抛 `TimeoutException`。

**遗留 / 下一步（v1.2.1 后续）**
1. starter 事务一致性：`@Transactional` + Outbox + WS 事件总线（需 Spring 事务基础设施）。
2. `ApprovalPolicy` 真实审批网关（用户/IM/CLI/转人工终端）。
3. 可选：starter 装配切换开关，按配置在进程沙箱 / 容器沙箱间选择。
