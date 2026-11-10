# 示例 (examples)

Town Demontower TypeScript SDK 的使用示例。

## 运行前提

```bash
cd towndemontower-sdk-typescript
npm install        # 已包含 ts-node 开发依赖
```

## 示例列表

| 文件 | 内容 | 运行命令 |
|------|------|----------|
| `basic_usage.ts` | 客户端创建、技能注册、R0 放行 / R2 审批拦截、审计与指标读取 | `npm run example:basic` |
| `advanced_features.ts` | 命令模板防注入、文件审计哈希链、GovernancePass 治理通行证、TypeScriptFunctionExecutor 与 SkillRegistry | `npm run example:advanced` |
| `rest_client.ts` | GovernanceRestClient 对齐 spring-starter 治理端点：指标观测、完整性自检、审批列表/批准、GovernanceApiError 错误处理（内置假 fetch，可离线运行） | `npm run example:rest` |

## 期望输出（节选）

`basic_usage.ts`：
```
[greet]      success=true output=Hello, World!
[update]     success=false errorCode=APPROVAL_REQUIRED
[audit]      共 5 条记录，链完整性=true
```

`advanced_features.ts`：
```
合法命令:   success=true
注入命令:   success=false errorCode=COMMAND_TEMPLATE_MISMATCH
落盘审计条数=2 链完整性=true
作用域内获取令牌 auditId=audit-001
add(2,3) = 5 (errorCode=undefined)
```

`rest_client.ts`：
```
总调用: 128 | 拒绝率: 0.07 | 缓存命中: 0.45
审计链有效: true | 记录数: 512 | 合规: true | 待审批: 6
数量: 2
  - [PENDING] apv-1 tool=db.drop by=alice
结果状态: APPROVED | 审批人: carol
捕获 GovernanceApiError -> status: 404 errorCode: NOT_FOUND | 审批单不存在: missing
```

> 提示：真实使用时去掉示例里的 `fetchImpl`，改为配置指向 Spring 服务的 `baseUrl` 与 `token`（对应服务端 `towndemontower.metrics-token`）。
