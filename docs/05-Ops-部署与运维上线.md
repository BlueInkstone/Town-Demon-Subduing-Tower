# Ops 阶段交付物：部署与运维上线说明

> 本阶段角色：**Ops（部署与运维）** —— 只回答「能否稳定部署、健康运行、可回滚」，不做架构/编码决策。
> 上游输入：`docs/04-QA-测试用例与结果.md`（34/34 通过，Phase 0 验收达成）
> 交付：本说明 + `towndemontower-core/pom.xml`（可构建为 Maven 制品）+ 仓库根 `README.md`（端到端链路索引）

---

## 1. 制品形态与定位

Town Demon-Subduing Tower **不是独立程序**，是嵌入用户 Spring Boot 程序的治理 starter。本 Phase 0 交付的是**纯 Java 的 `towndemontower-core` 构件**：

- 零外部依赖、零 Spring 依赖（`core` 内 `import org.springframework.*` 即违规，ArchUnit 卡点见 §6）。
- 契约语言中立：IntentObject / 错误码 / Executor 接口均 JSON 可序列化，供 Python/JS SDK 后续对齐。
- 实际生效入口：`Executor.execute(intent, ctx)` 装饰器链。

> 注：`towndemontower-spring-starter`（薄适配层：@AgentSkill / AOP / AutoConfiguration / @Transactional / Outbox / WS）按 README 排期在 Phase 1 交付，本阶段仅给出接入契约与配置占位。

## 2. 构建与发布

```bash
# 构建 core（JDK 25 LTS）
cd towndemontower-core
javac -d out $(find src/main/java src/test/java -name "*.java")
java -cp out com.towndemontower.core.CoreTest   # 验收 34/34

# 若使用 Maven（见 pom.xml）
mvn -q -pl towndemontower-core test      # 触发 CoreTest
```

Maven 坐标（pom.xml 已就绪）：
```
groupId: com.towndemontower
artifactId: towndemontower-core
version: 0.1.0
packaging: jar
```

## 3. 接入方式（主路径：写程序接入）

用户的 Spring Boot 应用引入依赖后，凡工具调用**必经 Executor**：

```java
// 1) 装配（starter 自动装配；本 Phase 0 由用户手动装配 core 构件）
Executor governor = ExecutorFactory.buildDefault(
        registry, modePolicy, riskGate,
        permissionEngine, snapshotService, invoker, auditStore, approvalPolicy);

// 2) 模型只输出意图对象，一律过闸门
IntentObject intent = llm.parseToIntent(modelRawOutput);   // 解析层：建议文本视为 INTENT_FORMAT_INVALID
SkillResult r = governor.execute(intent, ctx);
switch (r.code) {
    case "OK":                 /* 放行，取 r.output */ break;
    case "PENDING_APPROVAL":   /* R2 已挂人工，ticketId=r.approvalTicketId */ break;
    case "R3_DENIED":          /* 不可逆，转人工终端 */ break;
    case "NEED_MORE_INFO":     /* 低置信，回灌模型补充信息 */ break;
    default:                   /* 其余拒绝码，按场景处理 */ break;
}
```

**零配置生效原则**：加依赖、启动、`core` 默认策略（标准档）即生效；日志出现 `Town Demon-Subduing Tower core 标准档已生效` 即成功。

## 4. 配置档位（机制写死 / 策略可配）

| 写死（不可自定义） | 可配（YAML / Java） |
|--------------------|----------------------|
| 工具调用必经 Executor | 工具→风险级映射（R0-R3） |
| R0-R3 语义、R3=不可逆 | 默认策略档位（宽松/标准/严格） |
| 审计必写 + 哈希链必算 | 审计存储实现（本地/DB/远端） |
| 写操作前快照必落 | 快照存哪、存多久 |
| 装饰器链顺序 | R2 审批通道（CLI/API/IM） |
| 模式白名单必经 Executor 校验 | 各模式映射哪些工具、maxDwellTime |
| 门禁脚本签名校验 | 门禁清单、重试上限 |
| R3 默认拒绝、Guardian 不终批 | Guardian 是否启用预审 |
| 模式切换只由状态机事件触发 | 各模式上下文选择器 |

**自检红线**：任何自定义项若能让用户绕过 R3 拒绝、关掉审计、跳过模式白名单或改门禁脚本，则该项**不该可配**。

## 5. 质量指标看板（DAO 阶段接入）

| 指标 | 含义 | 异常应对 |
|------|------|----------|
| `wrong_rejection_rate` | 错误拒绝率（该做没做） | 升 → 调高置信阈值/换模型 |
| `over_conservative_rate` | 过度保守率（R0 也要确认） | 升 → 收紧确认范围 |
| `intent_format_error_total` | 意图格式错误数 | 升 → 查提示词/解析层 |
| `need_more_info_total` | 低置信转补充信息数 | 监控分布 |
| `r3_denied_total` | R3 拒绝数 | 审计用，不应为 0 之外的异常 |
| `tainted_write_blocked_total` | 污点写被阻 | 审计用 |
| `mode_tool_not_allowed_total` | 模式白名单拒绝 | 调试用 |
| `prompt_cache_hit_rate` | 缓存命中率 | 骤降联动查格式错误率/幻觉率 |

**关键原则**：不只看操作成功率——成功率过高可能是"少做少错"，必须同时看错误拒绝率与误执行率。

## 6. 安全与急停（运维硬约束）

- **物理急停 / KillSwitch**：触发 `EMERGENCY_STOP` 事件，状态机转 `PAUSED` 并发布**取消令牌**，终止所有在途 Tool 调用（SkillExecutor / 沙箱 / Broker 均须响应），新调用不得从状态机出去。
- **三层 AND**：业务态允许 + 安全闸门允许 + 模式白名单允许，缺一不可（EXECUTING 态仍不能执行 R3）。
- **零常驻凭据**：运行时无明文密钥；凭据由独立 CredentialBroker 注入 JIT 短期令牌（Phase 1 交付，本阶段留接口）。
- **沙箱**：每次 Tool 调用独立一次性进程/容器，默认无网，只读项目目录+临时目录（Phase 1 容器化）。
- **审计不可删**：`audit_log` 只 INSERT；哈希链断裂告警 `AUDIT_HASH_CHAIN_BROKEN`。

## 7. 回滚与故障排查

| 场景 | 处理 |
|------|------|
| 写操作出错 | 执行前已落盘快照 + 回滚动作，按 `rollbackHint` 回滚 |
| 哈希链校验失败 | 立即告警，定位被篡改/损坏的审计段，隔离该段 |
| 门禁脚本签名不匹配 | 拒绝执行，检查是否被篡改或配置漂移 |
| 模式切换异常 | `MODE_TRANSITION_INVALID` 已留审计；恢复需走合法事件 |
| 审批超时 | R2 超时默认拒绝，需重新发起 |

## 8. 上线检查清单（Go-Live）

- [ ] `CoreTest` 34/34 通过（`mvn test` 或 javac+java）
- [ ] core 无 `import org.springframework.*`（ArchUnit 闸门）
- [ ] 默认策略档位生效，日志出现标准档标识
- [ ] 审计存储接通，哈希链 `verifyChain()==true`
- [ ] 急停取消令牌链路在目标环境验证（starter 阶段）
- [ ] R3 工具在生产配置中确认默认拒绝（转人工终端）

> Ops 结论：Phase 0 `towndemontower-core` 构件可独立构建、可嵌入、可审计、可回滚、可急停；满足上线检查清单前置项，待 Phase 1 starter 补齐后完成完整生产部署验证。端到端链路（PM→Arch→Dev→QA→Ops）在此闭环。
