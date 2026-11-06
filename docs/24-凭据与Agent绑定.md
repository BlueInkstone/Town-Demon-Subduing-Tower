# 24 · 凭据与 Agent 绑定（I3-b）

> 从 [23 · 授权收口](./23-授权收口与审计加固.md) 拆出来的那一项：
> **"凭据映射不到 ACTIVE Agent 即 403"**。V1.9 先把 `grant` 收权做完并全量验证，
> 这一版才动认证侧——两件事失败时不好区分，所以不做并行。

---

## 1. 这一版到底关掉了什么

V1.8 的令牌回答的是"**你是哪个调用者**"，不回答"**你代表哪个 Agent**"。
于是 `agents` 的每一次受治理写操作，`ExecutionContext.agentId` 都取自配置里的固定值
`towndemontower.agents.agent-id`：

```
任何持令牌的调用者 ──► 认证通过 ──► ctx.agentId = "agents-api-agent" ──► 拥有 agents:write
```

后果有两层，第二层才是真问题：

1. 审计里看不出是哪个 Agent 在改注册表（`agent=` 恒为同一个值）；
2. **权限等于共享**：给某一个服务发令牌，等于把注册表身份的权限整体发给它。
   "最小权限"在 REST 入口这一层是不成立的。

绑定后：

```
调用者 ops ──► 令牌校验 ──► 绑定表 ops→AGT-0001 ──► ctx.agentId = "AGT-0001"
                                                      ├─ RBAC 按 AGT-0001 裁决（只有它有的权限）
                                                      └─ 审计归属到 AGT-0001
```

同一张令牌表、同一套链路、同一个接口——只有"代表谁"变了。

## 2. 绑定表怎么配

与门禁密钥（V1.7）、调用令牌（V1.8）同一条规矩：**只读环境变量，不落配置文件**。

```
TOWNDEMONTOWER_API_BINDINGS = ops:AGT-0001,ci:AGT-0003
```

| 属性 | 规则 |
|---|---|
| 分隔 | `,` / `;` / 空白；冒号两侧的空白会被吃掉（`ops : AGT-0001` 等价） |
| 左侧 | 调用者名称，必须**与令牌表里的名字逐字一致**（`TOWNDEMONTOWER_API_TOKENS` 的 `名称`） |
| 右侧 | Agent ID，只允许字母数字与 `_ . -`，长度 1~64 |
| 重复 | 同一调用者出现多次 → 只保留首条，后续条目进 `rejected()` |
| 非法 | 进 `rejected()`，**不整条回显**（只打名称 + 原因） |

一个调用者只能绑一个 Agent；一个 Agent 可以由多个调用者代表（换令牌不换身份）。

## 3. 空表语义（本版最重要的取舍）

| 绑定表 | 行为 | 自检 |
|---|---|---|
| **空** | 不启用绑定校验，完全回落 V1.8（所有调用者共用注册表身份） | `token` 模式启动打 **WARN**；`loopback`/`off` 打 INFO |
| **非空** | 严格模式：表里没有的调用者一律 `CALLER_NOT_PROVISIONED` | 启动打 INFO（列出绑定），并点名两个方向的孤儿 |

为什么空表不 fail-closed：V1.8 的部署没有这张表。若"空表即全拒"，升级会一次性打死所有
接入方，而升级动作本身与"要不要按 Agent 隔离权限"是两件事。代价用**可见性**兜：
空表在 token 模式下必须告警，不允许"悄悄退回共享身份"。

反过来，只要表里有**一条**，就不存在"表里没写就沿用默认身份"——中间态才是真正的坑。

两个方向的孤儿都在启动日志里点名：

- 有绑定、无令牌：`调用者 [x] 没有对应令牌 → 该条绑定永不生效`（通常是名字拼错）
- 有令牌、无绑定：`令牌调用者 [y] 未绑定 Agent → 严格模式下将被 403`（通常是漏配）

## 4. 三个错误码，别混

| 码 | HTTP | 何时出现 | 修复动作 |
|---|---|---|---|
| `CALLER_UNAUTHENTICATED` | 401 | 缺头 / 方案不是 Bearer / 令牌无效 | 换令牌 |
| `CALLER_NOT_PROVISIONED` | 403 | 令牌有效，但调用者不在绑定表里（严格模式） | 改绑定表 |
| `CALLER_AGENT_UNAVAILABLE` | 403 | 绑定命中，但 Agent 不存在 / 已删除 / 非 ACTIVE | 恢复 Agent 状态或改绑定指向 |

后两个分开，是因为"补一条绑定"和"把停用的 Agent 恢复"是完全不同的运维动作。
若只用 RBAC 自然拒绝，调用方看到的是 `PERMISSION_DENIED`——真实原因却是"你的绑定指向了
一个已停用的 Agent"，排查会被带偏。

`CALLER_AGENT_UNAVAILABLE` 要求 **ACTIVE**，不是"保留权限"：PAUSED 仍持有权限（便于恢复），
但它的语义就是"暂停执行"。让一个暂停的 Agent 代表调用者改注册表，等于绕过暂停本身。

## 5. 改动面

| 层 | 改了什么 | 没改什么 |
|---|---|---|
| core | 新增 `auth/CallerAgentBindings`；`CallerContext` 多带 `agentId`；`ErrorCodes` +2；`AuditDecorator` 的 `SKILL_INVOKED` 增加 `agent=` / `owner=` | 链顺序（仍 10 层）、`ExecutionArgsConstructor` 签名、`ApiCallerRegistry` 的解析规则 |
| starter | `ApiAuthFilter` 多收一个可空绑定表（5 参构造，旧 3/4 参保留）；`GovernanceIntegrityChecker` 新增 `CALLER_BINDING_PARTIAL` / `CALLER_BINDING_ORPHAN` | `auth-mode` 三档语义、`/api/*` 限定、`API_CALLER_DENIED` 的字段 |
| agents | 新增 `CallerBindingGuard`；控制器 `ctx()` 的 `agentId` 改由它解析；自动配置装配绑定表 Bean 并接入过滤器 | 8 个 REST 接口的路径与返回结构、`AgentService`、门面 |
| delivery | **未接入**（无 Agent 概念，构造器走 4 参 → 行为不变） | 全部 |

`ApiAuthFilter` 传 `null` 绑定表 = 本模块不做绑定检查。这是刻意的模块边界：
"调用者是哪个 Agent"只有拥有注册表的模块答得出来。

### 审计归属

`SKILL_INVOKED` 的 payload 现在是稳定的六段：

```
agents.registerAgent|OK|task=agents-api|mode=ARCHITECT_PLANNING|agent=AGT-0001|owner=ops
```

`agent` / `owner` 两个字段**恒出现**，无绑定时值为空串（不写 `null`），结构保持可解析。
这是 I3-b 的直接收益：不用翻访问日志就能回答"谁、以什么身份、做了什么"。

## 6. 完成标准与验证

### 完成标准（全部满足）

1. 表为空 → 行为与 V1.8 完全一致（core 8 项 + starter 4 项）；
2. 表非空且未绑定 → 403 `CALLER_NOT_PROVISIONED`，且**不写入 caller/agent 上下文**（starter 6 项）；
3. 绑定命中 → `CallerContext` 同时拿到 caller 与 agentId，请求结束一并清理（starter 4 项）；
4. 绑定到无 `agents:write` 的 Agent → 写操作 403（此前 201），读仍 200 —— **身份确实换了**（agents 3 项）；
5. 绑定到不存在 / 非 ACTIVE 的 Agent → 403 `CALLER_AGENT_UNAVAILABLE`（agents 5 项）；
6. 审计 `SKILL_INVOKED` 归属到绑定的 Agent，且**不出现回落身份**（agents 3 项）；
7. 四模块回归全绿，既有断言未放松。

### 验证记录（实测）

| 模块 | 数量 | 相对上一版 |
|---|---|---|
| core | **384**（34+30+14+45+20+24+26+62+32+33+**64**） | +64（`V19CallerBindingTest`） |
| starter | **137**（5+40+24+30+6+**32**）+ tx 14 | +32（`V19CallerBindingWiringTest`） |
| delivery | 219（79+43+56+41） | 不变 |
| agents | **241**（78+50+73+17+**23**） | +23（`AgentBindingTest`） |
| **全仓** | **995 / 995** | +119 |

`AgentBindingTest` 的预置流程走真实门面（`facade.register`）在进程内建号，
对应"运维先建 Agent、再发凭据"的真实顺序，没有为测试在产品代码里开后门。

## 7. 冷启动顺序（会卡住人的地方）

绑定是"调用者 → 已存在的 Agent"，而 Agent 只能通过注册表接口创建——**鸡生蛋**。
正确顺序只有一种：

1. **第一步：不配绑定表**（或 `auth-mode=loopback` 仅本机），启动 agents；
2. 用令牌（或本机）注册出需要作为"操作者"的 Agent，并给它 `agents:read` / `agents:write`；
3. 记下它的 ID（`AGT-0001` 等）；
4. **配 `TOWNDEMONTOWER_API_BINDINGS` 并重启**——此时严格模式生效，未绑定的调用者开始被 403。

反过来（先配绑定表再想建 Agent）会得到 `CALLER_AGENT_UNAVAILABLE`，因为绑定指向的
Agent 还不存在。这不是缺陷，是"身份必须先存在"的必然结果；但启动日志里点名
`绑定的 Agent 不存在` 就是为了让人在两分钟内看懂这一点。

## 8. 已知代价与未覆盖

- **`CALLER_AGENT_UNAVAILABLE` 是每请求一次仓储读取**。内存仓储是 Map 查表，可忽略；
  `storage=file` 时若数据量大，应加一层短期缓存（当前未做，等真实规模再说）。
- **绑定表把调用者与 Agent 绑死**：一个调用者不能同时代表多个 Agent。多 Agent 编排
  （v2.0）需要改成 `调用者 + 目标 Agent` 的二维映射，届时这条约束要重写。
- **绑定表同样是静态的**：改绑定必须重启（环境变量只在启动时读）。动态改绑定需要
  一个可信的管理面——那本身又是一个需要认证的入口，不能在本版顺手加。
- **没有校验"绑定指向的 Agent 是否已授予该调用者应有的权限"**。绑定只说"代表谁"，
  权限仍由该 Agent 的档案决定。运维把 `ops` 绑到一个权限过大的 Agent，本版不拦。
- **loopback 模式下绑定不生效**（没有调用者身份）。这是设计，但意味着"想在本地试用
  按 Agent 隔离"必须配 `auth-mode=token` + 令牌，比纯本地演示麻烦。
- **`GovernanceIntegrityChecker` 只能报"配了但配错"**（`CALLER_BINDING_PARTIAL` /
  `CALLER_BINDING_ORPHAN`），报不了"压根没配"——它不知道模块的 `auth-mode`。
  空表告警因此落在各模块启动日志里。
