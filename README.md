# Town Demon-Subduing Tower — 导航页

> 本页只做**指路**，不复制状态、数字与结论——那类事实只在其权威出处维护，避免多处漂移。
> 项目带传播工程思想的性质；文档按「问题 → 取舍 → 局限 → 架构与契约 → 验证 → 用法」组织。

## 先看这三份（日常只需这三份）

| 你的目的 | 读哪份 | 它回答什么 |
|---|---|---|
| 判断要不要用 | [产品定位与工程思想.md](docs/产品定位与工程思想.md) | 定位、设计取舍、管得到哪里、管不到哪里 |
| 想直接用 | [网关接入指南.md](docs/网关接入指南.md) | 怎么接、怎么发意图、尚不支持什么 |
| 想知道做到哪 | [排期与拍板.md](docs/排期与拍板.md) | 拍板了什么、排了什么、哪些作废 |

## 事实的权威出处（只在一处维护，别处只引用）

| 事实类型 | 唯一出处 |
|---|---|
| 定位 / 边界 / 工程思想 | `docs/产品定位与工程思想.md` |
| 网关状态与未完成项 | `docs/网关接入指南.md` |
| 排期 / 拍板 / 作废项 | `docs/排期与拍板.md` |
| 测试基线与验收数字 | `docs/测试清单.md` |
| 架构与机制 | `docs/架构方案.md` |
| 实现记录 | `docs/实现记录.md` |
| 部署 / 运维 / 界面 | `docs/部署与运维.md` |
| 权威协议（IntentObject / 错误码 / 网关 golden） | `contract/` |

## 两种接入形态

- **网关（推荐，任意语言）**：`towndemontower-gateway/` 暴露 `POST /api/evaluate`；只裁决、不执行。接入与尚不支持项见 `docs/网关接入指南.md`。
- **嵌入式（Java / Spring Boot）**：加 starter 依赖 + `@AgentSkill` + 方法首行 `GovernancePass.require(...)`；最小可用版见 `docs/网关接入指南.md` 嵌入式章节。

## 模块一览（结构速查，不含状态）

| 目录 | 职责 |
|---|---|
| `towndemontower-core/` | 纯 Java 治理内核：10 环节固定链、审计哈希链、快照、审批、`BypassScanMain` 构建期扫描 |
| `towndemontower-spring-starter/` | Spring 薄适配层：`@AgentSkill`、AOP 切面、自动配置、事务一致性（Outbox） |
| `towndemontower-delivery/` | 交付进度看板（:8080，含治理管理页） |
| `towndemontower-agents/` | Agent 注册表（:8081，REST + 花名册页） |
| `towndemontower-gateway/` | 独立裁决网关（`POST /api/evaluate`、`GET /api/health`、`GET /console`、Python 薄包、Dockerfile） |
| `contract/` | 权威 JSON 协议与校验脚本 |
| `towndemontower-sdk-python/`、`towndemontower-sdk-typescript/` | ⚠️ 已归档（历史基线，见 `ARCHIVED.md`），不再维护 |

## 快速验证（命令见各权威文档）

```bash
# core：编译目标 Java 8，clean 不能省；完整命令与理由见 docs/测试清单.md
cd towndemontower-core
javac --release 8 -encoding UTF-8 -d out $(find src/main/java src/test/java -name "*.java")
java -cp out com.towndemontower.DemoRunner
java -cp out com.towndemontower.core.CoreTest

# 协议门禁：python contract/verify_codes.py
# 网关接入与启动：docs/网关接入指南.md
# 全仓基线数字与回归口径：docs/测试清单.md
```

## 页面入口（起服务方式见各模块文档）

| 页面 | 地址 | 所属模块 |
|---|---|---|
| 交付看板 + 治理管理 | `http://127.0.0.1:8080/delivery/` | delivery（demo profile） |
| Agent 花名册 | `http://127.0.0.1:8081/agents/` | agents（demo profile） |
| 裁决控制台 | `http://127.0.0.1:8080/console` | gateway（需配 `INKARMOR_API_TOKEN`） |
| Swagger UI / OpenAPI 3 | `http://127.0.0.1:8080/swagger-ui.html` ｜ `/v3/api-docs` | delivery，启动后可用 |

## 文档列表（共 7 份，按需查）

| 文档 | 回答什么 |
|---|---|
| `docs/产品定位与工程思想.md` | 定位、边界、工程思想、可编排设计（未实现） |
| `docs/网关接入指南.md` | 网关怎么接、分级行为、尚不支持项、嵌入式最小可用版 |
| `docs/排期与拍板.md` | 拍板结论、批次顺序、接入方边界、方向 C 取舍 |
| `docs/架构方案.md` | 分层、固定 10 环节链、防绕过、关键机制、构建期扫描 |
| `docs/实现记录.md` | 按版本记「做了什么能力、关键约束」 |
| `docs/测试清单.md` | 测试基线、QA 方法、运行方式、实测结论 |
| `docs/部署与运维.md` | 构建分发、治理管理接口、UI、页面入口、运维边界 |

> 旧编号文档已合并删除，历史版本可在 git 中查阅（`git log -- docs/`）。`contract/` 是协议不是文档，保留。
