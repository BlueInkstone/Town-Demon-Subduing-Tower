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

## 快速验证（实测可抄，5 步跑通）

> 仓库**没有根聚合 pom**，网关按 Maven 坐标依赖 core，所以必须先装 core 再打网关。
> 以下在仓库根目录执行；`clean` 不能省（增量编译会留下旧 JDK 字节码）。
> 一条命令自动完成「编译 → 起服务 → 探活 → 发意图 → 断言 → 关服务」：见 § 烟测脚本。

**① 编译 core 到本机 Maven 仓库**

```bash
mvn -B -q -f towndemontower-core/pom.xml clean install -DskipTests
```

**② 打包网关**

```bash
mvn -B -q -f towndemontower-gateway/pom.xml clean package
```

产物是 `towndemontower-gateway/target/towndemontower-gateway-0.1.0.jar` + `target/lib/`，
**两者必须一起拷走**；`lib/` 按 jar 所在目录解析，只拷 jar 会 `NoClassDefFoundError`。

**③ 起服务**（令牌必配，否则所有请求 403；端口默认 8080）

```bash
INKARMOR_API_TOKEN=test PORT=8080 java -jar towndemontower-gateway/target/towndemontower-gateway-0.1.0.jar
```

**④ 探活与发意图**（另开终端）

```bash
curl http://127.0.0.1:8080/api/health
# {"code":0,"message":"ok","data":{"status":"up","uptimeSeconds":...}}

curl -X POST http://127.0.0.1:8080/api/evaluate \
  -H 'X-Inkarmor-Token: test' -H 'Content-Type: application/json' \
  -d '{"tool":"deleteData","args":{"table":"test_table","rows":"1"},"confidence":0.9,
       "reason":"清理测试数据","expectedEffect":"删除一行","rollbackHint":"无"}'
# 预期：data.code=R3_DENIED（不可逆操作硬拒绝，说明治理链真在工作）
```

**⑤ 协议门禁（可选）**

```bash
python contract/verify_codes.py    # 69/69
```

完整协议字段、配置项与尚不支持项见 `docs/网关接入指南.md`；全仓基线数字与回归口径见 `docs/测试清单.md`。

## 一键烟测（外部开发者最短路径）

不想手敲上面 5 步时，直接跑脚本——它自动完成编译 → 起服务 → 三个断言 → 关服务：

```bash
# Linux / macOS / Git Bash
bash scripts/quickstart.sh

# Windows PowerShell
powershell -ExecutionPolicy Bypass -File scripts/quickstart.ps1
```

断言：① `/api/health` 返回 `status=up`；② 缺参数意图返回 `INTENT_FORMAT_INVALID`；③ 不可逆工具 `deleteData` 返回 `R3_DENIED`。三项全过打印 `ALL CHECKS PASSED` 并以退出码 0 结束；任一项失败退出码 1。脚本只依赖 JDK + Maven + Python3（协议门禁），不要求本机有 Docker。

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
