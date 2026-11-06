# 22 · REST 调用者认证（v1.8 / I6）

> 解决的问题：`agents` 与 `delivery` 的 `/api/**` 此前**零身份校验**。同一网段内任何人
> `POST /api/agents` 就能注册一个自带 `agents:write` 权限的 Agent——治理链再严密，
> 也挡不住"从入口递进去一个合法身份"。

---

## 1. 三档模式

配置项：`towndemontower.agents.auth-mode` / `towndemontower.delivery.auth-mode`
（默认 `loopback`）。

| 模式 | 读（GET/HEAD/OPTIONS） | 写（POST/PUT/DELETE/PATCH） | 适用 |
|---|---|---|---|
| `loopback`（默认） | 放行 | **仅限本机**（环回地址） | 本机演示、开发、单机部署 |
| `token` | 必须带 Bearer 令牌 | 必须带 Bearer 令牌 | 多机部署、CI 调用 |
| `off` | 放行 | 放行 | **仅**当外层网关已强制鉴权 |

三条设计取舍，都是刻意的：

1. **默认不是 `token`。** 若默认强制令牌，所有已有接入方升级后立刻全 401，
   且必须先去配置环境变量——这是"升级即炸"，不是安全。默认 `loopback` 的收益是
   实打实的：跨机写入这条路当场断掉，本机演示与既有用例零改动。
2. **`loopback` 下读放行。** 读暴露的是注册表内容（名称/角色/技能/权限清单），
   不含凭据；写才是"改授权面"。按读写分级，比"一刀切要令牌"更容易被真正启用。
   要连读也保护，用 `token`。
3. **`off` 不做"静默接受"。** 启动时记 SEVERE 级告警，自检报告会带上，
   让它成为一个"要解释的决定"而不是"忘了配"。

## 2. 令牌从哪来

唯一来源：环境变量 `TOWNDEMONTOWER_API_TOKENS`，格式 `名称:令牌`，逗号分隔：

```bash
export TOWNDEMONTOWER_API_TOKENS='ops:8f3c9e1b5d2048a6c3a9e7d1b5f0c8a2,ci:1a9d7b5f3c1e04862b8d6a4f2e0c9b7d'
```

- **名称**＝调用者身份，只允许 `[A-Za-z0-9_.-]{1,64}`，会写进 `ExecutionContext.ownerUserId`；
- **令牌**必须过 `SecretPolicy`：≥32 字符、≥12 种字符、不在占位值清单
  （与门禁密钥同一套规则，v1.8 起收敛为 `core/security/SecretPolicy` 单一实现）；
- 不合格条目**只记名称与原因**，不记令牌内容；一条都不可用时表为空；
- **不回退**：没有系统属性、没有配置文件、没有内置默认令牌。

内存里只存令牌的 SHA-256 摘要，比较用 `MessageDigest.isEqual` 全量遍历，不提前返回。

## 3. 认证之后发生什么

```
请求 → ApiAuthFilter
        ├─ 非 /api/**            → 直接放行（页面、静态资源、OpenAPI 不受影响）
        ├─ 拒绝                  → 401/403 + JSON {code,message} + 审计 API_CALLER_DENIED
        └─ 通过                  → CallerContext.set(名称) → 控制器 → 清 ThreadLocal
```

- 控制器把 `CallerContext.currentOr(默认值)` 填进 `ExecutionContext.ownerUserId`，
  于是"谁在调用"进入治理链与审计，而不只是留在访问日志里；
- **RBAC 主体仍是 `agent-id` 配置值**，不随令牌名改变。理由：把令牌名当 agentId
  会让"没预先授权的令牌名"一律 403，故障现象与认证失败混在一起，排查成本高。
  "多调用者、多权限集"属于 I3（`grant` 收权）之后的事，不在本版硬凑；
- 拒绝事件写审计 `API_CALLER_DENIED`，payload 形如
  `码|模块|方法|路径|来源地址`，**不含令牌**。

## 4. 错误码

| 码 | HTTP | 含义 |
|---|---|---|
| `CALLER_UNAUTHENTICATED` | 401 | 缺 `Authorization`、方案不是 Bearer、令牌为空或无效 |
| `AUTH_TOKEN_UNAVAILABLE` | 401 | 令牌表为空——认证不可用，一律拒绝（不是"没配就放行"） |
| `CALLER_NOT_LOCAL` | 403 | `loopback` 模式下写操作来自非本机地址 |

401 响应携带 `WWW-Authenticate: Bearer`（RFC 6750 §3.1）。

**已知客户端坑**：JDK 的 `HttpURLConnection` 在"带 body 的请求 + 401"时会抛
`HttpRetryException: cannot retry due to server authentication, in streaming mode`，
**与响应头无关**，是 JDK 自身限制。Java 侧请用 Apache HttpClient / OkHttp，
或从 `errorStream` 读状态。`AgentAuthTest` 里对这种现象做了显式归一（见 `status(Call)`），
以免把客户端限制误判成服务端缺陷。

## 5. 环回判定

`127.0.0.0/8`、`::1`、`0:0:0:0:0:0:0:1`、`::ffff:127.x.x.x`（IPv4-mapped，双栈环境常态）、
`localhost`。**不能只判 `127.0.0.1`**：Windows/Tomcat 双栈下 `remoteAddr` 常是
`::ffff:127.0.0.1`，漏掉会把本机也判成跨机，演示与用例全崩。

反代之后 `remoteAddr` 是代理地址，`loopback` 模式会退化为"代理即本机"——
这种部署必须用 `token` 模式，不要指望 `X-Forwarded-For`（可伪造）。

## 6. 边界（别夸大）

- **令牌是服务身份，不是用户登录态。** 无会话、无刷新、无吊销列表；
  轮换＝改环境变量并重启。
- **不做按接口粒度的授权（scope）。** 一个令牌能调该模块所有 `/api/**`；
  细粒度权限仍由 core 的 `PermissionEngine` 在链内裁决。
- **只保护 `/api/**`。** 页面与静态资源不拦；若页面本身承载敏感信息，需在网关层处理。
- **`CallerContext` 是 ThreadLocal。** 异步派发（`@Async`、自建线程池）前必须显式传递，
  否则子线程看不到调用者。
- **不解决传输安全。** 令牌走明文 HTTP 时等于公开；生产必须 TLS。

## 7. 测试

| 用例 | 覆盖 |
|---|---|
| `core/V18AuthTest`（62） | 密钥策略、令牌表解析与拒绝项、认证矩阵、状态机、上下文清理 |
| `starter/V18AuthWiringTest`（30） | 三档模式边界、环回地址判定（含 IPv4-mapped）、上下文不串号 |
| `agents/AgentAuthTest`（17） | 真实 Tomcat：无表全拒、缺头/错令牌 401、正确令牌可读写、查询参数令牌无效、拒绝进审计、`auth-mode` 写错启动失败 |

全仓回归 **811 / 811**（core 255 + starter 119 + delivery 219 + agents 218）。

## 8. 后续：这一层刻意缺的那一半

本版只回答"**你是哪个调用者**"。而"**你代表哪个 Agent**"（即 `ExecutionContext.agentId`
到底取谁）在本版仍是配置里的固定值——意味着任何持令牌的调用者共享注册表身份的全部权限。
这一半已在 [24 · 凭据与 Agent 绑定](./24-凭据与Agent绑定.md)（v1.9.1 / I3-b）补齐：
绑定表非空即严格模式，未绑定 `403 CALLER_NOT_PROVISIONED`，绑定的 Agent 不可用
`403 CALLER_AGENT_UNAVAILABLE`。两版合起来才构成完整的"服务身份 + 治理主体"两级身份。
