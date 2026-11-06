# API 调用与中间件示例（可运行）

一个不依赖框架的 HTTP 调用器 + 可插拔中间件链，用来演示这套治理管理接口
（[docs/33-治理管理接口.md](../../docs/33-治理管理接口.md)）的标准调用姿势：
**请求配置 → 参数传递 → 中间件拦截 → 响应统一处理 → 异常收敛**。

全部代码只依赖 JDK 21 与 Jackson（项目已有的 Maven 依赖），可以直接编译运行。

---

## 1. 目录与职责

```
examples/api-client-demo/
├── cp.txt                              # 编译/运行所需的最小 classpath（Jackson 三个 jar）
├── src/main/java/com/towndemontower/examples/apiclient/
│   ├── ApiRequest.java                 # 不可变的请求描述：方法/路径/查询/头/体/超时
│   ├── ApiResponse.java                # 响应容器：状态/头/原始文本/JSON 树 + 信封取数
│   ├── ApiException.java               # 唯一异常类型：status / code / error / retryable
│   ├── Middleware.java                 # 中间件接口（洋葱模型的 Next）
│   ├── MiddlewareChain.java            # 注册 + 顺序控制 + 组装执行
│   ├── Middlewares.java                # 6 个内置中间件
│   ├── ApiClient.java                  # 传输层：拼 URL / 发请求 / 解析 / 异常收敛
│   ├── DemoServer.java                 # 本地假服务（复刻管理接口的关键行为）
│   └── Demo.java                       # 8 个分步场景，直接看输出
└── README.md                           # 本文
```

分层原则：**传输层不认识业务，中间件不认识业务，调用方不认识重试**。
`ApiClient` 只做"发出去、收回来、解析"；鉴权、重试、统一格式、异常兜底全部在中间件里。

---

## 2. 运行

```powershell
# 0) 指定 JDK 21（本仓库用的是 21.0.12；若 java -version 已是 21 可跳过）
$jdk = "C:\Program Files\Java\jdk-21.0.12\bin"

# 1) 编译（在仓库根目录执行；-sourcepath 会自动带上同包其它类）
$cp = (Get-Content examples/api-client-demo/cp.txt -Raw).Trim()
& "$jdk\javac.exe" -encoding UTF-8 -parameters `
  -sourcepath examples/api-client-demo/src/main/java `
  -cp $cp -d examples/api-client-demo/target/classes `
  examples/api-client-demo/src/main/java/com/towndemontower/examples/apiclient/Demo.java

# 2) 运行（默认起本地假服务，演示全部中间件）
& "$jdk\java.exe" -cp "examples/api-client-demo/target/classes;$cp" `
  com.towndemontower.examples.apiclient.Demo

# 3) 接真实服务（跳过 /flaky、/slow 两个演示端点，只跑只读场景）
& "$jdk\java.exe" -cp "examples/api-client-demo/target/classes;$cp" `
  com.towndemontower.examples.apiclient.Demo http://127.0.0.1:8080 你的-metrics-token
```

`cp.txt` 里是 Jackson 三个 jar 的绝对路径，按本机 Maven 仓库位置改一下即可。

> Windows 控制台若显示乱码，先 `chcp 65001`，或在 Windows Terminal / VS Code 终端里跑。

真实服务需要先打开开关并配好令牌（见 `docs/33`）：

```properties
towndemontower.admin-api-enabled=true
towndemontower.metrics-token=<至少 32 位的随机串>
```

---

## 3. 分步使用说明

### 步骤 1：引入 —— 认识三个对象

| 对象 | 回答什么问题 |
|---|---|
| `ApiRequest` | 我要调什么：方法、路径、查询参数、请求头、请求体、超时 |
| `ApiClient` | 发到哪：baseUrl + 连接池 + 中间件链 |
| `Middleware` | 调用前后要额外做什么 |

### 步骤 2：配置 —— 创建一个客户端

```java
ApiClient client = new ApiClient("http://127.0.0.1:8080", new ObjectMapper(), Duration.ofSeconds(5));
```

`ApiClient` 内部持有 `HttpClient`（连接池复用）与 `ObjectMapper`（线程安全），
**一个进程建一个**，不要每次调用都 new。

### 步骤 3：注册中间件 —— order 越小越外层

```java
client.add(0, Middlewares.onError(e -> log.warn("调用失败：{}", e.describe())))
      .add(1, Middlewares.retry(3, Duration.ofMillis(150)))
      .add(2, Middlewares.logging(System.out))
      .add(3, Middlewares.token(token))
      .add(4, Middlewares.unifiedEnvelope());
```

同 order 时按注册先后稳定执行（不依赖 map 迭代顺序，行为可复现）。

### 步骤 4：发起调用

```java
// GET + 查询参数
JsonNode plans = client.execute(
        ApiRequest.builder(ApiRequest.Method.GET, "/towndemontower/admin/plans")
                  .query("status", "PENDING")
                  .build())
        .data();

// POST + JSON 体
JsonNode approved = client.execute(
        ApiRequest.post("/towndemontower/admin/plans/plan-1/approve", "{\"approver\":\"alice\"}"))
        .data();

// 单次调用覆盖超时
client.execute(ApiRequest.builder(ApiRequest.Method.GET, "/towndemontower/admin/audit")
                         .timeout(Duration.ofSeconds(2))
                         .build());
```

### 步骤 5：处理响应与异常

```java
try {
    JsonNode data = client.execute(req).data();   // 信封与非信封都能直接取 data
    // 用数据
} catch (ApiException e) {
    // 业务码、HTTP 状态、网络错误都在这里，不需要再去分辨 IOException
    switch (e.status()) {
        case 401 -> redirectToLogin();
        case 403 -> showNoPermission();
        case 409 -> showConflict(e.getMessage());
        default -> showError(e.describe());
    }
}
```

---

## 4. 中间件是怎么执行的（洋葱模型）

`MiddlewareChain.execute` 从尾部往前包裹，形成嵌套调用：

```
onError
└─ retry
   └─ logging
      └─ token
         └─ unifiedEnvelope
            └─ ApiClient.send  ← 真正发 HTTP
```

- **请求方向**（由外到内）：每层都可以改请求（`withHeader` / `withQuery`），然后 `next.proceed(req)`；
- **响应方向**（由内到外）：内层返回 `ApiResponse`，外层可以改写（`withJson`）或直接抛异常；
- **异常方向**：任何层抛 `ApiException`，外层可以重试、上报、降级，也可以原样抛。

所以"顺序"直接决定能力，顺序错了功能就失效：

| 想要的能力 | 正确位置 |
|---|---|
| 记录每一次真实尝试（含重试） | logging 在 **retry 内层** |
| 重试 503 / 超时 | retry 包住 unifiedEnvelope（异常得先从内层抛出） |
| 统一兜底的告警/提示 | onError 在 **最外层** |
| 每次重试都重新带 token | token 在 retry 内层 |

---

## 5. 常见场景

### 5.1 请求头自动添加

```java
client.add(3, Middlewares.token(token));                                   // X-Governance-Token
client.add(3, Middlewares.headers(Map.of("X-Request-Id", "req-001")))      // 任意头，只补不覆盖
```

只补不覆盖是刻意的：调用方显式设置的头优先，中间件不应该悄悄改掉它。
确需以中间件为准时用 `request.withHeaderOverride(...)`。

### 5.2 响应数据统一格式化

`unifiedEnvelope` 把两种失败统一成 `ApiException`：

1. HTTP 非 2xx → 失败（哪怕 body 里写着 `code=0`）；
2. HTTP 200 但信封 `code != 0` → 失败（最常见的坑：把业务失败当成功）。

成功时用 `resp.data()` 取数：信封取 `data`，非信封取整棵树，调用方不需要判断"有没有包一层"。

### 5.3 异常统一处理

```java
client.add(0, Middlewares.onError(e -> {
    metrics.counter("api_error", e.error()).increment();
    if (e.isNetworkError()) alert("下游不可达");
}));
```

**处理完必须再抛**（`Middlewares.onError` 内部就是 `sink.accept(e); throw e;`）。
如果这里 return 一个默认值，调用方会以为成功——这是最危险的中间件反模式。

### 5.4 自动重试

```java
client.add(1, Middlewares.retry(3, Duration.ofMillis(150)));   // 150ms 起，指数退避 + 抖动
```

两道保险：`ApiException.retryable()`（只重试网络错误 / 429 / 5xx）+
幂等判断（GET/PUT/DELETE，或显式带 `X-Idempotent: true`）。
不幂等的 POST 不会被重试——审批动作重复执行比失败更糟。

### 5.5 超时

```java
new ApiClient(baseUrl, mapper, Duration.ofSeconds(5))                 // 客户端默认
ApiRequest.builder(GET, "/audit").timeout(Duration.ofMillis(300))    // 单次覆盖
```

注意重试会放大总耗时：`attempts × timeout + 退避`。给用户侧留的总预算应该是
`ApiClient` 默认超时的 2~3 倍，而不是相等。

---

## 6. 注意事项与最佳实践

**执行机制相关**

1. **顺序即能力**：`logging` 放外层就只能看到最终结果，看不到重试过程；`onError` 放内层就抓不到外层的异常。
2. **`next.proceed()` 一般只调一次**：只有重试类中间件可以多次调用；其它中间件多次调用会导致请求被重复发送。
3. **请求对象不可变**：中间件里"改请求"得到的是新对象。原地修改会在重试时叠加副作用（比如每次重试多塞一个 trace-id）。
4. **不要短路成假成功**：想短路（命中缓存）就返回真实的 `ApiResponse`，不要凭空造一个 `code=0`。

**常见坑**

5. **重试非幂等请求**：POST 审批 / 建单类接口重试可能造成重复决策。要么带幂等键，要么在中间件里显式排除。
6. **重试 4xx**：401/403/400/409 重试一万次也不会变对，只会放大故障与延迟（示例 §5 专门验证了"只尝试 1 次"）。
7. **固定间隔重试**：一批客户端会在同一毫秒再次打向服务端，把抖动放大成雪崩——必须加抖动。
8. **响应体只能读一次**：`HttpResponse.BodyHandlers.ofString` 已经把 body 缓存成字符串，我们再做 JSON 解析；如果改成流式读取，中间件就不能二次读 body 了。
9. **超时分两层**：连接超时（3s，`HttpClient.Builder.connectTimeout`）与读超时（请求级 `timeout`）。只设一个，另一类故障就会挂死。
10. **日志泄密**：不要打印 `Authorization` / `X-Governance-Token` / 请求体。示例的日志只打方法、路径、状态、耗时、业务码。
11. **静默降级**：`onError` 里 return 默认值，会让"下游挂了"变成"业务正常但数据为空"，排查成本极高。
12. **受管请求头**：`Host`、`Content-Length` 等由 JDK 管理，手动设置会抛 `IllegalArgumentException`；`ApiClient` 里做了忽略处理，不要以为是自己写错了。

**性能相关**

13. **复用客户端**：`HttpClient` 与 `ObjectMapper` 创建成本高，必须单例复用（HTTP/2 多路复用 + 连接池）。
14. **给重试一个总预算**：建议在调用链最外层加一个 deadline 中间件，超过预算就不再重试，避免"3 次 × 5s"拖垮上游线程池。
15. **批量优于 N+1**：列表接口一次拿全量，不要在循环里逐条查询（示例的列表/过滤都是单次调用）。
16. **大响应别全量建树**：`ObjectMapper.readTree` 会把整个 JSON 变成内存对象；几十 MB 的审计导出应改成流式解析或分页。
17. **线程安全**：中间件实例会被并发复用，禁止在其中保存"本次请求"的可变状态（要放上下文请用参数传递或 `ThreadLocal` 且务必在 finally 清理）。
18. **可观测**：把 `error` 码作为指标维度（`api_error{error="PLAN_REJECTED"}`），比统计"失败总数"更能定位问题。

---

## 7. 与后端契约的对应关系

| 本示例 | 后端实现 |
|---|---|
| `unifiedEnvelope` 判定的 `{code,message,data,error}` | `spring/admin/ApiResponse` |
| `X-Governance-Token` | `spring/admin/AdminGuard`（未配令牌 403、令牌错 401） |
| `PLAN_REJECTED` / `NOT_PENDING` / `SELF_APPROVAL` 等错误码 | `spring/admin/PlanAdminController`、`ApprovalAdminController` |
| `limit/offset`、`status`、`q` 查询参数 | `AuditAdminController`、`AuditSyncAdminController` |

前端接 UI 时，把 `ApiClient` 这套思路平移到 axios 拦截器即可：
请求拦截 ≈ `token` / `headers`，响应拦截 ≈ `unifiedEnvelope`，错误拦截 ≈ `onError`，
重试仍是独立的一层（不要让响应拦截器顺手重试，职责会混在一起）。
