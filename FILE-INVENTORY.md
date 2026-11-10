# 项目文件清单

- 扫描根目录：`c:/WorkBuddy/Town Demon-Subduing Tower`
- 生成时间：2026-11-04 10:41:17
- 排除规则：`node_modules`、`.git`、`dist`、`build`、`target`、`out`、`.gradle`、`.idea`、构建产物目录（`out-main`/`out-v113-check`/`out-v121-check`）、隐藏文件与隐藏目录
- 纳入统计：**286** 个文件，合计 **1.75 MB**
- 已排除的构建产物：**500** 个文件，合计 1,012.3 KB（主要为 `target/` 与 `out-*` 下的 `*.class` 与日志）

> **补充（2026-11-10）**：本清单是 2026-11-04 的历史快照，未包含此后新增的 `towndemontower-sdk-python/`（15 个文件）与 `towndemontower-sdk-typescript/`（24 个文件，共 39 个，已纳入 git）。两份 SDK 的 `node_modules/`、`__pycache__/`、`.pytest_cache/`、`dist/` 等缓存与构建产物按 `.gitignore` 排除，不计入统计。当前进度以 `README.md`、`docs/08-版本任务清单.md` 为准。

## 一、按文件类型统计

| 扩展名 | 类型 | 数量 | 合计大小 |
| --- | --- | ---: | ---: |
| `.java` | 文本 | 235 | 1.06 MB |
| `.md` | 文本 | 31 | 445.1 KB |
| `.txt` | 文本 | 5 | 27.7 KB |
| `.xml` | 文本 | 4 | 13.2 KB |
| `.imports` | 文本 | 3 | 300 B |
| `.properties` | 文本 | 3 | 2.5 KB |
| `.jar` | 二进制 | 2 | 187.0 KB |
| `.html` | 文本 | 1 | 21.4 KB |
| `.py` | 文本 | 1 | 1.8 KB |
| `.sql` | 文本 | 1 | 828 B |

## 二、按模块/目录统计

| 目录 | 文件数 | 合计大小 |
| --- | ---: | ---: |
| (根目录) | 1 | 17.5 KB |
| docs | 30 | 427.6 KB |
| towndemontower-agents | 44 | 212.7 KB |
| towndemontower-core | 132 | 678.1 KB |
| towndemontower-delivery | 37 | 195.0 KB |
| towndemontower-spring-starter | 42 | 259.3 KB |

## 三、文件明细

路径按字母序排列。“预览”为文本类文件的前 2 行内容（截断 100 字符）；二进制文件不解析内容。


### `docs/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `00-快速上手.md` | 文本 (.md) | 12.5 KB | 2026-11-04 00:13 | # 快速上手（v1.4） / > 目标：**10 分钟内**看到两个可用的看板，并把治理接进你自己的 Spring Boot 项目。 |
| `01-PM-需求说明.md` | 文本 (.md) | 7.7 KB | 2026-11-02 11:27 | # PM 阶段交付物：Town Demon-Subduing Tower 需求说明（v1.3 基线收敛） / > 来源文档：'Town Demon-Subduing Tower 技术设计文档 v1.3… |
| `02-Arch-架构方案.md` | 文本 (.md) | 8.2 KB | 2026-11-02 11:27 | # Arch 阶段交付物：Town Demon-Subduing Tower 架构方案 / > 本阶段角色：**Arch（架构师）** —— 只回答「系统怎么拆、数据怎么流、接口怎么定」，不写业务代码… |
| `04-QA-测试用例与结果.md` | 文本 (.md) | 4.3 KB | 2026-11-02 11:23 | # QA 阶段交付物：测试用例与结果 / > 本阶段角色：**QA（测试与质量验证）** —— 只验证是否满足验收标准，不修改代码。 |
| `05-Ops-部署与运维上线.md` | 文本 (.md) | 6.6 KB | 2026-11-02 11:27 | # Ops 阶段交付物：部署与运维上线说明 / > 本阶段角色：**Ops（部署与运维）** —— 只回答「能否稳定部署、健康运行、可回滚」，不做架构/编码决策。 |
| `06-Phase1-适配层设计.md` | 文本 (.md) | 10.7 KB | 2026-11-02 12:10 | # Phase 1 — Spring 薄适配层设计（towndemontower-spring-starter） / > 承接 Phase 0（core 骨架 + v1.3 契约）。本阶段在 **不改… |
| `07-Phase1-伪代码实现.md` | 文本 (.md) | 11.0 KB | 2026-11-02 13:58 | # Town Demon-Subduing Tower —— Phase 1 适配层伪代码实现 / > 适用模块：'towndemontower-spring-starter'（基于 AOP 的薄适配… |
| `08-版本任务清单.md` | 文本 (.md) | 37.0 KB | 2026-11-04 00:13 | # Town Demon-Subduing Tower — 版本任务清单 / > 说明：设计基线为「技术设计文档 v1.3」；实现版本号独立编排（v1.0 起）。 |
| `09-Phase1.2-实现.md` | 文本 (.md) | 5.9 KB | 2026-11-02 14:35 | # Town Demon-Subduing Tower — Phase 1.2 实现交付（Runtime & Infra 增强） / > 上游输入：'docs/08-版本任务清单.md' 中 V1.2… |
| `10-Phase1.2.1-Docker沙箱实现.md` | 文本 (.md) | 6.4 KB | 2026-11-02 14:58 | # Town Demon-Subduing Tower — Phase 1.2.1 实现交付（Docker 容器沙箱） / > 上游输入：'docs/08-版本任务清单.md' 中 v1.2.1 分组… |
| `11-Phase1.2.1-事务一致性实现.md` | 文本 (.md) | 7.8 KB | 2026-11-02 15:40 | # Town Demon-Subduing Tower — Phase 1.2.1 实现交付（starter 事务一致性） / > 上游输入：'docs/08-版本任务清单.md' 中 v1.2.1 … |
| `12-Phase1.3-交付进度看板.md` | 文本 (.md) | 27.4 KB | 2026-11-02 18:23 | # Town Demon-Subduing Tower — Phase 1.3 实现交付（交付进度看板模块） / > 上游输入：'docs/08-版本任务清单.md' 中 v1.3 分组（面板族首批）… |
| `13-Phase1.4-Agent注册表.md` | 文本 (.md) | 19.2 KB | 2026-11-02 19:39 | # Phase 1.4 · Agent 注册表（v1.4） / > **一句话**：把治理链里的 'agentId' 从裸字符串变成有档案的注册对象—— |
| `14-流程设计说明.md` | 文本 (.md) | 14.8 KB | 2026-11-02 19:40 | # 流程设计说明：固定流程 + 可插拔策略（v1.4 现状） / > **结论**：当前实现是**固定流程（固化管线）**，不是可编排流程。 |
| `15-个人开发者可编排设计说明.md` | 文本 (.md) | 17.2 KB | 2026-11-02 19:41 | # 个人开发者的"可编排"：用代码声明意图，而不是填表单 / > **核心论点**：对个人开发者而言，"可编排"的价值不在"有图形界面"，而在 |
| `16-产品介绍.md` | 文本 (.md) | 3.3 KB | 2026-11-02 19:41 | # Town Demon-Subduing Tower · 产品介绍（v1.4） / ## 一句话定位 |
| `17-强制力与绕过面分析.md` | 文本 (.md) | 11.6 KB | 2026-11-02 19:50 | # 强制力边界与绕过面分析 / > 结论先行：本项目的管理机制是**框架层（进程内）强制**，不是操作系统层、更不是内核层强制。 |
| `18-防绕过方案.md` | 文本 (.md) | 26.1 KB | 2026-11-04 00:13 | # 防绕过方案 / > 前置结论（见 'docs/17-强制力与绕过面分析.md'）：当前治理是**框架层（进程内）强制**， |
| `19-需求对照与进度.md` | 文本 (.md) | 39.1 KB | 2026-11-04 00:13 | # 需求对照与进度盘点 / > 对照基准：项目原始设计《总体定位 / 核心决策 / 模块分层 / … / 落地路线 / 产品决策》。 |
| `20-默认安全配置说明.md` | 文本 (.md) | 15.1 KB | 2026-11-03 11:24 | # 默认配置即安全（v1.6） / > 一句话：**不配置也不该降低安全性**。凡是"图省事"的开关，默认值一律取安全侧； |
| `21-过渡宽限与门禁密钥.md` | 文本 (.md) | 10.3 KB | 2026-11-03 09:12 | # 过渡宽限与门禁密钥来源收敛（v1.7） / > 本期只做两件事，都围绕"变更的破坏性"： |
| `22-REST调用者认证.md` | 文本 (.md) | 6.5 KB | 2026-11-03 10:01 | # 22 · REST 调用者认证（v1.8 / I6） / > 解决的问题：'agents' 与 'delivery' 的 '/api/**' 此前**零身份校验**。同一网段内任何人 |
| `23-授权收口与审计加固.md` | 文本 (.md) | 8.5 KB | 2026-11-03 11:24 | # 23 · 授权收口（I3）与审计加固（I4） / > 本版按"解耦 + 串行"执行：**I3 只保留 grant 收权**，其余职责（凭据→Agent 绑定、 |
| `24-凭据与Agent绑定.md` | 文本 (.md) | 9.4 KB | 2026-11-03 10:10 | # 24 · 凭据与 Agent 绑定（I3-b） / > 从 [23 · 授权收口](./23-授权收口与审计加固.md) 拆出来的那一项： |
| `25-审计写入收权与锚点追平.md` | 文本 (.md) | 10.5 KB | 2026-11-03 11:24 | # 25 · 审计写入收权（I4-b）与锚点追平 / > 本版是 I4 的第二刀。I4 上一版（docs/23）解决的是"**链能不能自证没被重算**"（HMAC + 外锚）； |
| `26-锚点副本与回滚检出.md` | 文本 (.md) | 9.4 KB | 2026-11-03 11:23 | # 26 · 锚点副本与回滚检出（I4-c） / > 本文记录 v1.10 的唯一交付项：给审计外锚加一份**本机之外**的证词， |
| `27-测试清单.md` | 文本 (.md) | 20.5 KB | 2026-11-04 00:13 | # 27 · 完整测试清单（v1.10 基线） / > 适用范围：TownDemontower 治理框架（core / spring-starter / delivery / agents）。 |
| `28-测试执行报告.md` | 文本 (.md) | 34.9 KB | 2026-11-04 00:13 | # 28 · 测试执行报告（对照 docs/27-测试清单.md 逐项实测） / > 执行时间：2026-11-03 13:00 ~ 14:00（GMT+8） |
| `29-绕过治理令牌与静态扫描.md` | 文本 (.md) | 13.2 KB | 2026-11-03 23:35 | # v1.11 绕过治理令牌与静态扫描（D2 / CP-1 + CP-10） / > 本文是 v1.11 的交付记录，按 PM → Arch → Dev → QA → Ops 五段写。 |
| `30-命令模板化.md` | 文本 (.md) | 12.6 KB | 2026-11-04 00:13 | # v1.12 命令模板化（I5） / > 本文是 v1.12 的交付记录，按 PM → Arch → Dev → QA → Ops 五段写。 |

### `./`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `README.md` | 文本 (.md) | 17.5 KB | 2026-11-04 00:14 | # Town Demon-Subduing Tower — 端到端实现链路（PM → Arch → Dev → QA → Ops） / > **第一次来？** 直接看 **[docs/00-快速上手.… |

### `towndemontower-agents/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `cp.txt` | 文本 (.txt) | 7.8 KB | 2026-11-02 18:37 | C:\Users\ZhuanZ\.m2\repository\com\towndemontower\towndemontower-spring-starter\0.1.0\towndemontower… |
| `pom.xml` | 文本 (.xml) | 3.3 KB | 2026-11-02 18:29 | <?xml version="1.0" encoding="UTF-8"?> / <project xmlns="http://maven.apache.org/POM/4.0.0" |

### `towndemontower-agents\src\main\java\com\towndemontower\agents/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AgentsApplication.java` | 文本 (.java) | 863 B | 2026-11-02 19:36 | package com.towndemontower.agents; / import org.springframework.boot.SpringApplication; |

### `towndemontower-agents\src\main\java\com\towndemontower\agents\config/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AgentsAutoConfiguration.java` | 文本 (.java) | 14.4 KB | 2026-11-03 10:09 | package com.towndemontower.agents.config; / import com.towndemontower.agents.domain.AccessProvisione… |
| `AgentsOpenApiConfiguration.java` | 文本 (.java) | 4.3 KB | 2026-11-02 19:36 | package com.towndemontower.agents.config; / import io.swagger.v3.oas.models.Components; |
| `AgentsProperties.java` | 文本 (.java) | 2.9 KB | 2026-11-03 09:19 | package com.towndemontower.agents.config; / import org.springframework.boot.context.properties.Confi… |

### `towndemontower-agents\src\main\java\com\towndemontower\agents\domain/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AccessProvisioner.java` | 文本 (.java) | 1.1 KB | 2026-11-02 19:36 | package com.towndemontower.agents.domain; / import java.util.List; |
| `Agent.java` | 文本 (.java) | 7.0 KB | 2026-11-02 19:33 | package com.towndemontower.agents.domain; / import java.time.Instant; |
| `AgentRepository.java` | 文本 (.java) | 862 B | 2026-11-02 19:36 | package com.towndemontower.agents.domain; / import java.util.Optional; |
| `AgentRole.java` | 文本 (.java) | 2.1 KB | 2026-11-02 19:36 | package com.towndemontower.agents.domain; / /** |
| `AgentStatus.java` | 文本 (.java) | 2.4 KB | 2026-11-02 19:33 | package com.towndemontower.agents.domain; / /** |
| `AgentSummary.java` | 文本 (.java) | 3.9 KB | 2026-11-02 19:34 | package com.towndemontower.agents.domain; / import java.time.Instant; |
| `AgentSummaryCalculator.java` | 文本 (.java) | 3.0 KB | 2026-11-02 19:34 | package com.towndemontower.agents.domain; / import java.time.Instant; |
| `FileAgentRepository.java` | 文本 (.java) | 4.8 KB | 2026-11-02 19:37 | package com.towndemontower.agents.domain; / import java.io.IOException; |
| `InMemoryAgentRepository.java` | 文本 (.java) | 2.3 KB | 2026-11-02 19:36 | package com.towndemontower.agents.domain; / import java.util.ArrayList; |
| `ModelTier.java` | 文本 (.java) | 1.7 KB | 2026-11-02 19:36 | package com.towndemontower.agents.domain; / /** |
| `RoleTemplate.java` | 文本 (.java) | 2.3 KB | 2026-11-02 19:36 | package com.towndemontower.agents.domain; / import java.util.List; |
| `SkillCatalog.java` | 文本 (.java) | 1.6 KB | 2026-11-02 19:36 | package com.towndemontower.agents.domain; / import java.util.List; |

### `towndemontower-agents\src\main\java\com\towndemontower\agents\governance/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AgentGovernanceException.java` | 文本 (.java) | 866 B | 2026-11-02 18:30 | package com.towndemontower.agents.governance; / /** |
| `AgentGovernanceFacade.java` | 文本 (.java) | 14.1 KB | 2026-11-03 09:33 | package com.towndemontower.agents.governance; / import com.towndemontower.agents.domain.AccessProvis… |
| `AgentSkillDescriptors.java` | 文本 (.java) | 2.7 KB | 2026-11-02 19:34 | package com.towndemontower.agents.governance; / import com.towndemontower.core.contract.SkillDescrip… |
| `AgentsModeToolPolicy.java` | 文本 (.java) | 1.3 KB | 2026-11-02 19:34 | package com.towndemontower.agents.governance; / import com.towndemontower.core.mode.AgentMode; |
| `BindingReadinessCheck.java` | 文本 (.java) | 3.2 KB | 2026-11-03 10:09 | package com.towndemontower.agents.governance; / import com.towndemontower.agents.domain.Agent; |
| `CallerBindingGuard.java` | 文本 (.java) | 3.5 KB | 2026-11-03 10:09 | package com.towndemontower.agents.governance; / import com.towndemontower.agents.domain.Agent; |
| `CorePermissionProvisioner.java` | 文本 (.java) | 2.1 KB | 2026-11-03 09:34 | package com.towndemontower.agents.governance; / import com.towndemontower.agents.domain.AccessProvis… |
| `RegistrySkillCatalog.java` | 文本 (.java) | 1.0 KB | 2026-11-02 19:36 | package com.towndemontower.agents.governance; / import com.towndemontower.core.executor.SkillRegistr… |

### `towndemontower-agents\src\main\java\com\towndemontower\agents\seed/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AgentSeedInitializer.java` | 文本 (.java) | 3.6 KB | 2026-11-02 19:35 | package com.towndemontower.agents.seed; / import com.towndemontower.agents.config.AgentsProperties; |
| `DefaultAgents.java` | 文本 (.java) | 3.8 KB | 2026-11-02 19:35 | package com.towndemontower.agents.seed; / import com.towndemontower.agents.domain.Agent; |

### `towndemontower-agents\src\main\java\com\towndemontower\agents\service/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AgentInactiveException.java` | 文本 (.java) | 1017 B | 2026-11-02 19:34 | package com.towndemontower.agents.service; / import com.towndemontower.agents.domain.AgentStatus; |
| `AgentNotFoundException.java` | 文本 (.java) | 361 B | 2026-11-02 19:34 | package com.towndemontower.agents.service; / /** |
| `AgentService.java` | 文本 (.java) | 11.1 KB | 2026-11-02 19:34 | package com.towndemontower.agents.service; / import com.towndemontower.agents.domain.Agent; |
| `AgentValidationException.java` | 文本 (.java) | 411 B | 2026-11-02 19:41 | package com.towndemontower.agents.service; / /** |

### `towndemontower-agents\src\main\java\com\towndemontower\agents\web/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AgentController.java` | 文本 (.java) | 16.8 KB | 2026-11-03 09:56 | package com.towndemontower.agents.web; / import com.towndemontower.agents.domain.Agent; |
| `AgentExceptionHandler.java` | 文本 (.java) | 2.9 KB | 2026-11-02 19:36 | package com.towndemontower.agents.web; / import com.towndemontower.agents.governance.AgentGovernance… |
| `AgentRequest.java` | 文本 (.java) | 2.7 KB | 2026-11-02 19:35 | package com.towndemontower.agents.web; / import io.swagger.v3.oas.annotations.media.Schema; |
| `AgentSummaryView.java` | 文本 (.java) | 3.0 KB | 2026-11-02 19:35 | package com.towndemontower.agents.web; / import com.towndemontower.agents.domain.AgentRole; |
| `AgentView.java` | 文本 (.java) | 4.4 KB | 2026-11-02 19:35 | package com.towndemontower.agents.web; / import com.towndemontower.agents.domain.Agent; |

### `towndemontower-agents\src\main\resources/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `application-demo.properties` | 文本 (.properties) | 445 B | 2026-11-02 19:36 | # [agents] 演示 profile：独立端口 + 演示数据播种 / # 与交付看板（8080）并存，因此这里固定 8081，避免端口冲突。 |

### `towndemontower-agents\src\main\resources\META-INF\spring/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 文本 (.imports) | 117 B | 2026-11-02 18:31 | com.towndemontower.agents.config.AgentsAutoConfiguration / com.towndemontower.agents.config.AgentsOp… |

### `towndemontower-agents\src\test\java\com\towndemontower\agents/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AgentAccessTest.java` | 文本 (.java) | 14.6 KB | 2026-11-03 09:37 | package com.towndemontower.agents; / import com.towndemontower.agents.domain.AccessProvisioner; |
| `AgentAuthTest.java` | 文本 (.java) | 9.1 KB | 2026-11-03 09:26 | package com.towndemontower.agents; / import com.towndemontower.core.audit.AuditStore; |
| `AgentBindingTest.java` | 文本 (.java) | 12.9 KB | 2026-11-03 10:09 | package com.towndemontower.agents; / import com.towndemontower.agents.domain.Agent; |
| `AgentDomainTest.java` | 文本 (.java) | 14.7 KB | 2026-11-02 19:38 | package com.towndemontower.agents; / import com.towndemontower.agents.domain.Agent; |
| `AgentWebTest.java` | 文本 (.java) | 15.6 KB | 2026-11-02 19:38 | package com.towndemontower.agents; / import com.towndemontower.core.audit.AuditStore; |

### `towndemontower-core/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `cp.txt` | 文本 (.txt) | 820 B | 2026-11-03 09:53 | C:\Users\ZhuanZ\.m2\repository\org\junit\jupiter\junit-jupiter\5.10.2\junit-jupiter-5.10.2.jar;C:\Us… |
| `pom.xml` | 文本 (.xml) | 1.7 KB | 2026-11-02 11:23 | <?xml version="1.0" encoding="UTF-8"?> / <project xmlns="http://maven.apache.org/POM/4.0.0" |
| `sources.txt` | 文本 (.txt) | 4.3 KB | 2026-11-02 11:27 | src/main/java/com/towndemontower/core/approval/ApprovalDecision.java / src/main/java/com/towndemonto… |

### `towndemontower-core\src\main\java\com\towndemontower\core\approval/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `ApprovalCenter.java` | 文本 (.java) | 6.9 KB | 2026-11-02 20:35 | package com.towndemontower.core.approval; / import com.towndemontower.core.contract.ApprovalTicket; |
| `ApprovalDecision.java` | 文本 (.java) | 392 B | 2026-11-02 11:23 | package com.towndemontower.core.approval; / /** |
| `ApprovalPolicy.java` | 文本 (.java) | 466 B | 2026-11-02 11:23 | package com.towndemontower.core.approval; / import com.towndemontower.core.contract.ApprovalTicket; |
| `ApprovalRecord.java` | 文本 (.java) | 4.2 KB | 2026-11-02 20:35 | package com.towndemontower.core.approval; / import java.time.Instant; |
| `ApprovalStatus.java` | 文本 (.java) | 392 B | 2026-11-02 20:34 | package com.towndemontower.core.approval; / /** |
| `GuardianOpinion.java` | 文本 (.java) | 448 B | 2026-11-02 11:23 | package com.towndemontower.core.approval; / /** |
| `GuardianPrecheck.java` | 文本 (.java) | 387 B | 2026-11-02 11:23 | package com.towndemontower.core.approval; / import com.towndemontower.core.contract.ApprovalTicket; |
| `GuardianPrecheckImpl.java` | 文本 (.java) | 771 B | 2026-11-02 11:23 | package com.towndemontower.core.approval; / import com.towndemontower.core.contract.ApprovalTicket; |
| `HumanApprovalPolicy.java` | 文本 (.java) | 973 B | 2026-11-02 11:23 | package com.towndemontower.core.approval; / import com.towndemontower.core.contract.ApprovalTicket; |
| `PlanApproval.java` | 文本 (.java) | 2.3 KB | 2026-11-04 00:14 | package com.towndemontower.core.approval; / import java.time.Instant; |
| `PlanApprovalService.java` | 文本 (.java) | 6.0 KB | 2026-11-04 00:14 | package com.towndemontower.core.approval; / import java.time.Instant; |
| `PlanStatus.java` | 文本 (.java) | 794 B | 2026-11-04 00:14 | package com.towndemontower.core.approval; / /** |
| `TrustedApprovalPolicy.java` | 文本 (.java) | 766 B | 2026-11-02 20:35 | package com.towndemontower.core.approval; / /** |

### `towndemontower-core\src\main\java\com\towndemontower\core\audit/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AnchorReplica.java` | 文本 (.java) | 2.3 KB | 2026-11-03 11:03 | package com.towndemontower.core.audit; / /** |
| `AnchorReplicaState.java` | 文本 (.java) | 1.1 KB | 2026-11-03 11:07 | package com.towndemontower.core.audit; / /** |
| `AnchorSnapshot.java` | 文本 (.java) | 3.0 KB | 2026-11-03 11:03 | package com.towndemontower.core.audit; / /** |
| `AuditAnchor.java` | 文本 (.java) | 12.6 KB | 2026-11-03 11:18 | package com.towndemontower.core.audit; / import java.io.IOException; |
| `AuditCrypto.java` | 文本 (.java) | 3.9 KB | 2026-11-03 09:38 | package com.towndemontower.core.audit; / import com.towndemontower.core.security.SecretPolicy; |
| `AuditRecord.java` | 文本 (.java) | 1.1 KB | 2026-11-02 11:23 | package com.towndemontower.core.audit; / import java.util.Objects; |
| `AuditReplicas.java` | 文本 (.java) | 2.5 KB | 2026-11-03 11:04 | package com.towndemontower.core.audit; / import java.nio.file.Path; |
| `AuditStore.java` | 文本 (.java) | 1.7 KB | 2026-11-03 10:35 | package com.towndemontower.core.audit; / import java.util.List; |
| `FileAnchorReplica.java` | 文本 (.java) | 7.3 KB | 2026-11-03 11:03 | package com.towndemontower.core.audit; / import java.io.IOException; |
| `FileAuditStore.java` | 文本 (.java) | 16.5 KB | 2026-11-03 11:19 | package com.towndemontower.core.audit; / import java.io.IOException; |
| `HashChainAuditStore.java` | 文本 (.java) | 4.2 KB | 2026-11-03 10:35 | package com.towndemontower.core.audit; / import java.util.ArrayList; |
| `HttpAnchorReplica.java` | 文本 (.java) | 6.3 KB | 2026-11-03 11:04 | package com.towndemontower.core.audit; / import java.net.URI; |

### `towndemontower-core\src\main\java\com\towndemontower\core\auth/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `ApiCallerRegistry.java` | 文本 (.java) | 8.5 KB | 2026-11-03 09:18 | package com.towndemontower.core.auth; / import com.towndemontower.core.security.SecretPolicy; |
| `AuthMode.java` | 文本 (.java) | 1.6 KB | 2026-11-03 09:18 | package com.towndemontower.core.auth; / import java.util.Locale; |
| `CallerAgentBindings.java` | 文本 (.java) | 9.3 KB | 2026-11-03 10:11 | package com.towndemontower.core.auth; / import java.util.ArrayList; |
| `CallerContext.java` | 文本 (.java) | 3.1 KB | 2026-11-03 09:52 | package com.towndemontower.core.auth; / /** |

### `towndemontower-core\src\main\java\com\towndemontower\core\bypass/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `BypassFinding.java` | 文本 (.java) | 2.3 KB | 2026-11-03 23:05 | package com.towndemontower.core.bypass; / /** |
| `BypassScanMain.java` | 文本 (.java) | 5.1 KB | 2026-11-03 23:08 | package com.towndemontower.core.bypass; / import java.io.IOException; |
| `BypassScanner.java` | 文本 (.java) | 21.3 KB | 2026-11-04 00:12 | package com.towndemontower.core.bypass; / import java.io.IOException; |
| `JavaSourceLexer.java` | 文本 (.java) | 5.5 KB | 2026-11-03 23:08 | package com.towndemontower.core.bypass; / /** |
| `SonarIssueReport.java` | 文本 (.java) | 5.4 KB | 2026-11-04 00:12 | package com.towndemontower.core.bypass; / import java.util.LinkedHashMap; |

### `towndemontower-core\src\main\java\com\towndemontower\core\context/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `ContextBuilder.java` | 文本 (.java) | 5.2 KB | 2026-11-02 20:35 | package com.towndemontower.core.context; / import com.towndemontower.core.contract.RiskLevel; |
| `ContextSelector.java` | 文本 (.java) | 455 B | 2026-11-02 11:23 | package com.towndemontower.core.context; / import com.towndemontower.core.contract.ExecutionContext; |
| `FilePipelineStateStore.java` | 文本 (.java) | 3.6 KB | 2026-11-02 20:36 | package com.towndemontower.core.context; / import com.towndemontower.core.mode.AgentMode; |
| `InMemoryPipelineStateStore.java` | 文本 (.java) | 852 B | 2026-11-02 11:23 | package com.towndemontower.core.context; / import java.util.Map; |
| `PipelineState.java` | 文本 (.java) | 1.6 KB | 2026-11-02 20:36 | package com.towndemontower.core.context; / import com.towndemontower.core.mode.AgentMode; |
| `PipelineStateStore.java` | 文本 (.java) | 297 B | 2026-11-02 11:23 | package com.towndemontower.core.context; / /** |
| `PromptAssembly.java` | 文本 (.java) | 1.8 KB | 2026-11-02 20:35 | package com.towndemontower.core.context; / import com.towndemontower.core.routing.ModelProfile; |

### `towndemontower-core\src\main\java\com\towndemontower\core\contract/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `ApprovalTicket.java` | 文本 (.java) | 753 B | 2026-11-02 11:23 | package com.towndemontower.core.contract; / /** |
| `ErrorCodes.java` | 文本 (.java) | 4.0 KB | 2026-11-03 23:56 | package com.towndemontower.core.contract; / /** |
| `ExecutionContext.java` | 文本 (.java) | 1.5 KB | 2026-11-02 14:26 | package com.towndemontower.core.contract; / import com.towndemontower.core.mode.AgentMode; |
| `Executor.java` | 文本 (.java) | 314 B | 2026-11-02 11:23 | package com.towndemontower.core.contract; / /** |
| `IntentObject.java` | 文本 (.java) | 969 B | 2026-11-02 11:23 | package com.towndemontower.core.contract; / import java.util.Map; |
| `RiskLevel.java` | 文本 (.java) | 349 B | 2026-11-02 11:23 | package com.towndemontower.core.contract; / /** |
| `SkillDescriptor.java` | 文本 (.java) | 3.4 KB | 2026-11-04 00:14 | package com.towndemontower.core.contract; / /** |
| `SkillResult.java` | 文本 (.java) | 1.2 KB | 2026-11-02 11:23 | package com.towndemontower.core.contract; / /** |

### `towndemontower-core\src\main\java\com\towndemontower\core\credential/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `CredentialBroker.java` | 文本 (.java) | 2.6 KB | 2026-11-02 14:24 | package com.towndemontower.core.credential; / import java.nio.charset.StandardCharsets; |

### `towndemontower-core\src\main\java\com\towndemontower\core\decorator/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AuditDecorator.java` | 文本 (.java) | 6.8 KB | 2026-11-03 09:52 | package com.towndemontower.core.decorator; / import com.towndemontower.core.audit.AuditStore; |
| `CapabilityDecorator.java` | 文本 (.java) | 1.4 KB | 2026-11-02 11:23 | package com.towndemontower.core.decorator; / import com.towndemontower.core.contract.ExecutionContex… |
| `DockerSandboxExecutor.java` | 文本 (.java) | 2.5 KB | 2026-11-02 14:51 | package com.towndemontower.core.decorator; / import com.towndemontower.core.contract.ErrorCodes; |
| `DockerSandboxStrategy.java` | 文本 (.java) | 10.3 KB | 2026-11-03 09:10 | package com.towndemontower.core.decorator; / import com.towndemontower.core.executor.CancellationTok… |
| `GovernancePass.java` | 文本 (.java) | 14.6 KB | 2026-11-03 22:52 | package com.towndemontower.core.decorator; / import com.towndemontower.core.audit.AuditStore; |
| `IntentFormatDecorator.java` | 文本 (.java) | 1.9 KB | 2026-11-02 11:23 | package com.towndemontower.core.decorator; / import com.towndemontower.core.contract.ExecutionContex… |
| `ModeToolPolicyDecorator.java` | 文本 (.java) | 1.2 KB | 2026-11-02 11:23 | package com.towndemontower.core.decorator; / import com.towndemontower.core.contract.ErrorCodes; |
| `PermissionDecorator.java` | 文本 (.java) | 1.2 KB | 2026-11-02 11:23 | package com.towndemontower.core.decorator; / import com.towndemontower.core.contract.ExecutionContex… |
| `ProcessSandboxExecutor.java` | 文本 (.java) | 2.2 KB | 2026-11-02 14:24 | package com.towndemontower.core.decorator; / import com.towndemontower.core.contract.ErrorCodes; |
| `ProcessSandboxStrategy.java` | 文本 (.java) | 2.3 KB | 2026-11-02 14:27 | package com.towndemontower.core.decorator; / import com.towndemontower.core.executor.CancellationTok… |
| `RiskGateDecorator.java` | 文本 (.java) | 10.3 KB | 2026-11-04 00:14 | package com.towndemontower.core.decorator; / import com.towndemontower.core.approval.ApprovalCenter; |
| `SandboxedToolExecutor.java` | 文本 (.java) | 3.0 KB | 2026-11-03 22:34 | package com.towndemontower.core.decorator; / import com.towndemontower.core.audit.AuditStore; |
| `SandboxStrategy.java` | 文本 (.java) | 529 B | 2026-11-02 14:24 | package com.towndemontower.core.decorator; / import com.towndemontower.core.executor.CancellationTok… |
| `SchemaDecorator.java` | 文本 (.java) | 1.4 KB | 2026-11-02 11:23 | package com.towndemontower.core.decorator; / import com.towndemontower.core.contract.ExecutionContex… |
| `SnapshotDecorator.java` | 文本 (.java) | 1.2 KB | 2026-11-02 11:23 | package com.towndemontower.core.decorator; / import com.towndemontower.core.contract.ExecutionContex… |
| `TaintCheckDecorator.java` | 文本 (.java) | 1.3 KB | 2026-11-02 11:23 | package com.towndemontower.core.decorator; / import com.towndemontower.core.contract.ErrorCodes; |
| `UngovernedInvocationException.java` | 文本 (.java) | 1.6 KB | 2026-11-03 22:34 | package com.towndemontower.core.decorator; / import com.towndemontower.core.contract.ErrorCodes; |

### `towndemontower-core\src\main\java\com\towndemontower\core\executor/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `CancellationToken.java` | 文本 (.java) | 779 B | 2026-11-02 14:24 | package com.towndemontower.core.executor; / import java.util.concurrent.atomic.AtomicBoolean; |
| `CapabilityException.java` | 文本 (.java) | 212 B | 2026-11-02 11:23 | package com.towndemontower.core.executor; / /** [core] 意图超出工具能力范围。 */ |
| `EnginePermissionProvisioner.java` | 文本 (.java) | 2.7 KB | 2026-11-03 09:33 | package com.towndemontower.core.executor; / import com.towndemontower.core.audit.AuditStore; |
| `ExecutorFactory.java` | 文本 (.java) | 5.6 KB | 2026-11-04 00:15 | package com.towndemontower.core.executor; / import com.towndemontower.core.approval.ApprovalCenter; |
| `IntentArgsValidator.java` | 文本 (.java) | 3.6 KB | 2026-11-03 08:38 | package com.towndemontower.core.executor; / import java.util.Locale; |
| `IntentFormatException.java` | 文本 (.java) | 239 B | 2026-11-02 11:23 | package com.towndemontower.core.executor; / /** [core] 意图格式非法（建议文本 / 非意图 JSON）。 */ |
| `PermissionDeniedException.java` | 文本 (.java) | 242 B | 2026-11-02 11:23 | package com.towndemontower.core.executor; / /** [core] 权限不足，执行被拒（审计仍记录）。 */ |
| `PermissionEngine.java` | 文本 (.java) | 3.9 KB | 2026-11-03 09:32 | package com.towndemontower.core.executor; / import com.towndemontower.core.contract.ErrorCodes; |
| `PermissionProvisioner.java` | 文本 (.java) | 2.6 KB | 2026-11-03 09:33 | package com.towndemontower.core.executor; / import com.towndemontower.core.audit.AuditStore; |
| `RejectAllExecutor.java` | 文本 (.java) | 1.6 KB | 2026-11-03 08:23 | package com.towndemontower.core.executor; / import com.towndemontower.core.audit.AuditStore; |
| `SchemaValidationException.java` | 文本 (.java) | 249 B | 2026-11-02 11:23 | package com.towndemontower.core.executor; / /** [core] 意图参数不满足工具 Schema（缺必需参数）。 */ |
| `SkillInvoker.java` | 文本 (.java) | 375 B | 2026-11-02 11:23 | package com.towndemontower.core.executor; / import com.towndemontower.core.contract.IntentObject; |
| `SkillRegistry.java` | 文本 (.java) | 3.1 KB | 2026-11-02 18:30 | package com.towndemontower.core.executor; / import com.towndemontower.core.contract.SkillDescriptor; |
| `SnapshotFailedException.java` | 文本 (.java) | 232 B | 2026-11-02 11:23 | package com.towndemontower.core.executor; / /** [core] 写操作前快照失败，禁止继续写。 */ |
| `SnapshotService.java` | 文本 (.java) | 8.7 KB | 2026-11-03 08:23 | package com.towndemontower.core.executor; / import com.towndemontower.core.contract.IntentObject; |

### `towndemontower-core\src\main\java\com\towndemontower\core\gate/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `GateExecutor.java` | 文本 (.java) | 367 B | 2026-11-02 11:23 | package com.towndemontower.core.gate; / /** |
| `GateResult.java` | 文本 (.java) | 616 B | 2026-11-02 11:23 | package com.towndemontower.core.gate; / /** |
| `GateScript.java` | 文本 (.java) | 505 B | 2026-11-02 11:23 | package com.towndemontower.core.gate; / import java.time.Duration; |
| `GateScriptRunner.java` | 文本 (.java) | 7.5 KB | 2026-11-03 09:18 | package com.towndemontower.core.gate; / import com.towndemontower.core.contract.ErrorCodes; |
| `SimpleGateScript.java` | 文本 (.java) | 1.6 KB | 2026-11-03 09:04 | package com.towndemontower.core.gate; / import java.time.Duration; |

### `towndemontower-core\src\main\java\com\towndemontower\core\metrics/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `GovernanceMetrics.java` | 文本 (.java) | 4.9 KB | 2026-11-03 22:51 | package com.towndemontower.core.metrics; / import java.util.Collections; |

### `towndemontower-core\src\main\java\com\towndemontower\core\mode/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AgentMode.java` | 文本 (.java) | 346 B | 2026-11-02 11:23 | package com.towndemontower.core.mode; / /** |
| `DefaultModeToolPolicy.java` | 文本 (.java) | 1.5 KB | 2026-11-02 11:23 | package com.towndemontower.core.mode; / import java.util.Collections; |
| `ModeEvent.java` | 文本 (.java) | 381 B | 2026-11-02 11:23 | package com.towndemontower.core.mode; / /** |
| `ModeStateMachine.java` | 文本 (.java) | 1.3 KB | 2026-11-02 11:23 | package com.towndemontower.core.mode; / import java.util.EnumMap; |
| `ModeToolPolicy.java` | 文本 (.java) | 355 B | 2026-11-02 11:23 | package com.towndemontower.core.mode; / import java.util.Set; |
| `ModeTransitionInvalidException.java` | 文本 (.java) | 329 B | 2026-11-02 11:23 | package com.towndemontower.core.mode; / import com.towndemontower.core.contract.ErrorCodes; |

### `towndemontower-core\src\main\java\com\towndemontower\core\policy/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AstRisk.java` | 文本 (.java) | 1.3 KB | 2026-11-02 14:26 | package com.towndemontower.core.policy; / import com.towndemontower.core.contract.RiskLevel; |
| `CommandTemplate.java` | 文本 (.java) | 11.1 KB | 2026-11-03 23:58 | package com.towndemontower.core.policy; / import com.towndemontower.core.contract.IntentObject; |
| `DefaultRiskGate.java` | 文本 (.java) | 2.1 KB | 2026-11-02 14:26 | package com.towndemontower.core.policy; / import com.towndemontower.core.contract.IntentObject; |
| `RiskGate.java` | 文本 (.java) | 518 B | 2026-11-02 11:23 | package com.towndemontower.core.policy; / import com.towndemontower.core.contract.IntentObject; |
| `ShellAstAnalyzer.java` | 文本 (.java) | 289 B | 2026-11-02 11:23 | package com.towndemontower.core.policy; / /** |
| `SimpleShellAstAnalyzer.java` | 文本 (.java) | 2.6 KB | 2026-11-02 14:26 | package com.towndemontower.core.policy; / import com.towndemontower.core.contract.RiskLevel; |

### `towndemontower-core\src\main\java\com\towndemontower\core\prompt/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `ModePrompts.java` | 文本 (.java) | 3.6 KB | 2026-11-02 20:35 | package com.towndemontower.core.prompt; / import com.towndemontower.core.mode.AgentMode; |

### `towndemontower-core\src\main\java\com\towndemontower\core\routing/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `ModelProfile.java` | 文本 (.java) | 676 B | 2026-11-02 11:23 | package com.towndemontower.core.routing; / /** |
| `ModelRouter.java` | 文本 (.java) | 501 B | 2026-11-02 11:23 | package com.towndemontower.core.routing; / import com.towndemontower.core.contract.RiskLevel; |
| `SimpleModelRouter.java` | 文本 (.java) | 1.1 KB | 2026-11-02 11:23 | package com.towndemontower.core.routing; / import com.towndemontower.core.contract.RiskLevel; |

### `towndemontower-core\src\main\java\com\towndemontower\core\security/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `SecretPolicy.java` | 文本 (.java) | 3.4 KB | 2026-11-03 09:21 | package com.towndemontower.core.security; / import java.util.HashSet; |

### `towndemontower-core\src\main\java\com\towndemontower\core\statemachine/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `IllegalTransitionException.java` | 文本 (.java) | 263 B | 2026-11-02 11:23 | package com.towndemontower.core.statemachine; / public class IllegalTransitionException extends Runt… |
| `TaskEvent.java` | 文本 (.java) | 134 B | 2026-11-02 11:23 | package com.towndemontower.core.statemachine; / public enum TaskEvent { |
| `TaskStateMachine.java` | 文本 (.java) | 1.4 KB | 2026-11-02 11:23 | package com.towndemontower.core.statemachine; / import java.util.EnumMap; |
| `TaskStatus.java` | 文本 (.java) | 254 B | 2026-11-02 11:23 | package com.towndemontower.core.statemachine; / /** |

### `towndemontower-core\src\main\java\com\towndemontower/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `DemoRunner.java` | 文本 (.java) | 6.1 KB | 2026-11-03 09:35 | package com.towndemontower; / import com.towndemontower.core.audit.AuditStore; |

### `towndemontower-core\src\test\java\com\towndemontower\core\audit/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AuditRecordInjector.java` | 文本 (.java) | 1001 B | 2026-11-03 10:37 | package com.towndemontower.core.audit; / import java.util.List; |

### `towndemontower-core\src\test\java\com\towndemontower\core\bypass/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `BypassScanTest.java` | 文本 (.java) | 21.2 KB | 2026-11-04 00:12 | package com.towndemontower.core.bypass; / import java.io.IOException; |

### `towndemontower-core\src\test\java\com\towndemontower\core/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `CoreTest.java` | 文本 (.java) | 15.8 KB | 2026-11-03 09:36 | package com.towndemontower.core; / import com.towndemontower.core.audit.AuditStore; |
| `V111PassTest.java` | 文本 (.java) | 13.4 KB | 2026-11-03 22:53 | package com.towndemontower.core; / import com.towndemontower.core.audit.AuditRecord; |
| `V112TemplateTest.java` | 文本 (.java) | 10.5 KB | 2026-11-03 23:58 | package com.towndemontower.core; / import com.towndemontower.core.approval.ApprovalDecision; |
| `V113PlanTest.java` | 文本 (.java) | 8.8 KB | 2026-11-04 00:15 | package com.towndemontower.core; / import com.towndemontower.core.approval.ApprovalDecision; |
| `V121DockerSandboxTest.java` | 文本 (.java) | 4.8 KB | 2026-11-02 14:51 | package com.towndemontower.core; / import com.towndemontower.core.contract.*; |
| `V12FeatureTest.java` | 文本 (.java) | 10.6 KB | 2026-11-03 09:36 | package com.towndemontower.core; / import com.towndemontower.core.contract.*; |
| `V15HardeningTest.java` | 文本 (.java) | 16.8 KB | 2026-11-03 09:36 | package com.towndemontower.core; / import com.towndemontower.core.approval.ApprovalCenter; |
| `V16SecureCoreTest.java` | 文本 (.java) | 7.5 KB | 2026-11-03 09:36 | package com.towndemontower.core; / import com.towndemontower.core.audit.AuditRecord; |
| `V17GateKeyEnvTest.java` | 文本 (.java) | 7.5 KB | 2026-11-03 09:08 | package com.towndemontower.core; / import com.towndemontower.core.contract.ErrorCodes; |
| `V17SandboxHardeningTest.java` | 文本 (.java) | 5.6 KB | 2026-11-03 09:11 | package com.towndemontower.core; / import com.towndemontower.core.decorator.DockerSandboxStrategy; |
| `V18AuthTest.java` | 文本 (.java) | 8.2 KB | 2026-11-03 09:21 | package com.towndemontower.core; / import com.towndemontower.core.auth.ApiCallerRegistry; |
| `V19AnchorReplicaTest.java` | 文本 (.java) | 22.0 KB | 2026-11-03 11:19 | package com.towndemontower.core; / import com.sun.net.httpserver.HttpExchange; |
| `V19AuditHardeningTest.java` | 文本 (.java) | 12.4 KB | 2026-11-03 10:38 | package com.towndemontower.core; / import com.towndemontower.core.audit.AuditCrypto; |
| `V19AuditSealTest.java` | 文本 (.java) | 15.1 KB | 2026-11-03 10:38 | package com.towndemontower.core; / import com.towndemontower.core.audit.AuditCrypto; |
| `V19CallerBindingTest.java` | 文本 (.java) | 10.6 KB | 2026-11-03 09:54 | package com.towndemontower.core; / import com.towndemontower.core.auth.ApiCallerRegistry; |
| `V19PermissionTest.java` | 文本 (.java) | 7.7 KB | 2026-11-03 09:36 | package com.towndemontower.core; / import com.towndemontower.core.audit.AuditRecord; |

### `towndemontower-core/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `towndemontower-core-0.1.0.jar` | 二进制 (.jar) | 141.7 KB | 2026-11-03 11:20 | —（不解析） |

### `towndemontower-delivery/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `cp.txt` | 文本 (.txt) | 7.8 KB | 2026-11-02 18:20 | C:\Users\ZhuanZ\.m2\repository\com\towndemontower\towndemontower-spring-starter\0.1.0\towndemontower… |
| `pom.xml` | 文本 (.xml) | 4.1 KB | 2026-11-02 18:18 | <?xml version="1.0" encoding="UTF-8"?> / <project xmlns="http://maven.apache.org/POM/4.0.0" |

### `towndemontower-delivery\src\main\java\com\towndemontower\delivery\config/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `DeliveryAutoConfiguration.java` | 文本 (.java) | 10.0 KB | 2026-11-03 09:33 | package com.towndemontower.delivery.config; / import com.towndemontower.core.approval.ApprovalPolicy… |
| `DeliveryOpenApiConfiguration.java` | 文本 (.java) | 5.5 KB | 2026-11-02 18:15 | package com.towndemontower.delivery.config; / import io.swagger.v3.oas.models.Components; |
| `DeliveryProperties.java` | 文本 (.java) | 3.0 KB | 2026-11-03 09:20 | package com.towndemontower.delivery.config; / import org.springframework.boot.context.properties.Con… |

### `towndemontower-delivery\src\main\java\com\towndemontower\delivery/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `DeliveryBoardApplication.java` | 文本 (.java) | 1.3 KB | 2026-11-02 17:47 | package com.towndemontower.delivery; / import org.springframework.boot.SpringApplication; |

### `towndemontower-delivery\src\main\java\com\towndemontower\delivery\domain/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `DeliveryItem.java` | 文本 (.java) | 5.7 KB | 2026-11-02 16:34 | package com.towndemontower.delivery.domain; / import java.time.Instant; |
| `DeliveryRepository.java` | 文本 (.java) | 726 B | 2026-11-02 16:24 | package com.towndemontower.delivery.domain; / import java.util.List; |
| `DeliveryStatus.java` | 文本 (.java) | 2.0 KB | 2026-11-02 16:24 | package com.towndemontower.delivery.domain; / /** |
| `DeliveryStatusResolver.java` | 文本 (.java) | 2.4 KB | 2026-11-02 16:24 | package com.towndemontower.delivery.domain; / import java.time.Instant; |
| `DeliverySummary.java` | 文本 (.java) | 4.6 KB | 2026-11-02 17:51 | package com.towndemontower.delivery.domain; / import java.time.Instant; |
| `DeliverySummaryCalculator.java` | 文本 (.java) | 2.9 KB | 2026-11-02 17:51 | package com.towndemontower.delivery.domain; / import java.time.Instant; |
| `FileDeliveryRepository.java` | 文本 (.java) | 3.7 KB | 2026-11-02 16:24 | package com.towndemontower.delivery.domain; / import java.io.IOException; |
| `InMemoryDeliveryRepository.java` | 文本 (.java) | 1.8 KB | 2026-11-02 16:24 | package com.towndemontower.delivery.domain; / import java.util.ArrayList; |

### `towndemontower-delivery\src\main\java\com\towndemontower\delivery\governance/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `DeliveryGovernanceException.java` | 文本 (.java) | 1020 B | 2026-11-02 16:24 | package com.towndemontower.delivery.governance; / /** |
| `DeliveryGovernanceFacade.java` | 文本 (.java) | 13.7 KB | 2026-11-03 09:33 | package com.towndemontower.delivery.governance; / import com.towndemontower.core.approval.ApprovalPo… |
| `DeliveryModeToolPolicy.java` | 文本 (.java) | 1.7 KB | 2026-11-02 16:24 | package com.towndemontower.delivery.governance; / import com.towndemontower.core.mode.AgentMode; |
| `DeliverySkillDescriptors.java` | 文本 (.java) | 2.3 KB | 2026-11-02 16:24 | package com.towndemontower.delivery.governance; / import com.towndemontower.core.contract.SkillDescr… |

### `towndemontower-delivery\src\main\java\com\towndemontower\delivery\seed/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `DeliverySeedInitializer.java` | 文本 (.java) | 4.1 KB | 2026-11-02 17:47 | package com.towndemontower.delivery.seed; / import com.towndemontower.delivery.domain.DeliveryReposi… |
| `ProjectRoadmap.java` | 文本 (.java) | 6.3 KB | 2026-11-02 17:48 | package com.towndemontower.delivery.seed; / import com.towndemontower.delivery.service.DeliveryProgr… |

### `towndemontower-delivery\src\main\java\com\towndemontower\delivery\service/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `DeliveryNotFoundException.java` | 文本 (.java) | 399 B | 2026-11-02 16:24 | package com.towndemontower.delivery.service; / /** |
| `DeliveryProgressService.java` | 文本 (.java) | 7.7 KB | 2026-11-02 16:34 | package com.towndemontower.delivery.service; / import com.towndemontower.delivery.domain.DeliveryIte… |
| `DeliveryValidationException.java` | 文本 (.java) | 526 B | 2026-11-02 16:24 | package com.towndemontower.delivery.service; / /** |

### `towndemontower-delivery\src\main\java\com\towndemontower\delivery\web/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `DeliveryBoardPageController.java` | 文本 (.java) | 1.0 KB | 2026-11-02 17:20 | package com.towndemontower.delivery.web; / import org.springframework.stereotype.Controller; |
| `DeliveryController.java` | 文本 (.java) | 13.0 KB | 2026-11-03 09:20 | package com.towndemontower.delivery.web; / import com.towndemontower.core.auth.CallerContext; |
| `DeliveryExceptionHandler.java` | 文本 (.java) | 2.4 KB | 2026-11-02 16:24 | package com.towndemontower.delivery.web; / import com.towndemontower.delivery.governance.DeliveryGov… |
| `DeliveryItemRequest.java` | 文本 (.java) | 2.7 KB | 2026-11-02 18:16 | package com.towndemontower.delivery.web; / import io.swagger.v3.oas.annotations.media.Schema; |
| `DeliveryItemView.java` | 文本 (.java) | 2.9 KB | 2026-11-02 18:16 | package com.towndemontower.delivery.web; / import com.towndemontower.delivery.domain.DeliveryItem; |
| `DeliverySummaryView.java` | 文本 (.java) | 2.4 KB | 2026-11-02 18:16 | package com.towndemontower.delivery.web; / import com.towndemontower.delivery.domain.DeliverySummary… |

### `towndemontower-delivery\src\main\resources/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `application-demo.properties` | 文本 (.properties) | 1.6 KB | 2026-11-02 17:47 | # ===================================================================== / # Town Demon-Subduing Towe… |

### `towndemontower-delivery\src\main\resources\META-INF\spring/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 文本 (.imports) | 125 B | 2026-11-02 18:16 | com.towndemontower.delivery.config.DeliveryAutoConfiguration / com.towndemontower.delivery.config.De… |

### `towndemontower-delivery\src\main\resources\static\delivery/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `index.html` | 文本 (.html) | 21.4 KB | 2026-11-02 17:51 | <!DOCTYPE html> / <html lang="zh-CN"> |

### `towndemontower-delivery\src\test\java\com\towndemontower\delivery/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `DeliveryDomainTest.java` | 文本 (.java) | 20.6 KB | 2026-11-02 17:51 | package com.towndemontower.delivery; / import com.towndemontower.delivery.domain.DeliveryItem; |
| `DeliveryOpenApiTest.java` | 文本 (.java) | 8.3 KB | 2026-11-02 18:21 | package com.towndemontower.delivery; / import com.fasterxml.jackson.databind.JsonNode; |
| `DeliveryRoadmapTest.java` | 文本 (.java) | 7.3 KB | 2026-11-02 17:48 | package com.towndemontower.delivery; / import com.towndemontower.delivery.domain.DeliveryItem; |
| `DeliveryWebTest.java` | 文本 (.java) | 16.1 KB | 2026-11-02 17:53 | package com.towndemontower.delivery; / import com.towndemontower.core.audit.AuditStore; |

### `towndemontower-delivery\tools/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `mark-local-artifacts.py` | 文本 (.py) | 1.8 KB | 2026-11-02 18:18 | #!/usr/bin/env python3 / """把本地 m2 中已存在的 springdoc / swagger / webjars artifact 标记为 local 安装， |

### `towndemontower-spring-starter/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `cp.txt` | 文本 (.txt) | 7.0 KB | 2026-11-03 08:14 | C:\Users\ZhuanZ\.m2\repository\com\towndemontower\towndemontower-core\0.1.0\towndemontower-core-0.1.… |
| `pom.xml` | 文本 (.xml) | 4.2 KB | 2026-11-03 08:13 | <?xml version="1.0" encoding="UTF-8"?> / <project xmlns="http://maven.apache.org/POM/4.0.0" |

### `towndemontower-spring-starter\src\main\java\com\towndemontower\spring/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AgentSkill.java` | 文本 (.java) | 3.7 KB | 2026-11-04 00:15 | package com.towndemontower.spring; / import java.lang.annotation.Documented; |
| `AgentSkillAspect.java` | 文本 (.java) | 14.6 KB | 2026-11-04 00:15 | package com.towndemontower.spring; / import com.towndemontower.core.audit.AuditStore; |
| `DelegatingSkillInvoker.java` | 文本 (.java) | 1.4 KB | 2026-11-02 11:51 | package com.towndemontower.spring; / import com.towndemontower.core.contract.IntentObject; |
| `GovernanceDeniedException.java` | 文本 (.java) | 967 B | 2026-11-02 11:51 | package com.towndemontower.spring; / /** |
| `GovernanceIntegrityChecker.java` | 文本 (.java) | 15.6 KB | 2026-11-03 11:12 | package com.towndemontower.spring; / import com.towndemontower.core.approval.ApprovalCenter; |
| `GovernanceMetricsController.java` | 文本 (.java) | 7.3 KB | 2026-11-03 11:13 | package com.towndemontower.spring; / import com.towndemontower.core.approval.ApprovalCenter; |
| `GovernedModeSession.java` | 文本 (.java) | 4.0 KB | 2026-11-03 08:11 | package com.towndemontower.spring; / import com.towndemontower.core.audit.AuditStore; |
| `LegacyGovernanceGrace.java` | 文本 (.java) | 3.7 KB | 2026-11-03 09:04 | package com.towndemontower.spring; / import com.towndemontower.core.audit.AuditStore; |
| `TownDemontowerAutoConfiguration.java` | 文本 (.java) | 18.6 KB | 2026-11-04 00:15 | package com.towndemontower.spring; / import com.towndemontower.core.approval.ApprovalCenter; |
| `TownDemontowerProperties.java` | 文本 (.java) | 13.3 KB | 2026-11-03 11:11 | package com.towndemontower.spring; / import org.springframework.boot.context.properties.Configuratio… |

### `towndemontower-spring-starter\src\main\java\com\towndemontower\spring\tx/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `ApprovalEvent.java` | 文本 (.java) | 499 B | 2026-11-02 15:18 | package com.towndemontower.spring.tx; / import java.time.Instant; |
| `ApprovalEventPublisher.java` | 文本 (.java) | 491 B | 2026-11-02 15:18 | package com.towndemontower.spring.tx; / /** |
| `GovernanceDecisionCommittedEvent.java` | 文本 (.java) | 390 B | 2026-11-02 15:18 | package com.towndemontower.spring.tx; / /** |
| `GovernanceDecisionRecorder.java` | 文本 (.java) | 857 B | 2026-11-02 15:19 | package com.towndemontower.spring.tx; / import com.towndemontower.core.contract.ExecutionContext; |
| `GovernanceDecisionRepository.java` | 文本 (.java) | 1.1 KB | 2026-11-02 15:19 | package com.towndemontower.spring.tx; / import org.springframework.jdbc.core.JdbcTemplate; |
| `InMemoryWebSocketBroker.java` | 文本 (.java) | 1.3 KB | 2026-11-02 15:19 | package com.towndemontower.spring.tx; / import java.util.List; |
| `OutboxMessage.java` | 文本 (.java) | 509 B | 2026-11-02 15:19 | package com.towndemontower.spring.tx; / import java.time.Instant; |
| `OutboxRelay.java` | 文本 (.java) | 2.8 KB | 2026-11-02 15:32 | package com.towndemontower.spring.tx; / import org.springframework.scheduling.annotation.Scheduled; |
| `OutboxRepository.java` | 文本 (.java) | 2.8 KB | 2026-11-02 15:19 | package com.towndemontower.spring.tx; / import org.springframework.jdbc.core.JdbcTemplate; |
| `TransactionalGovernanceDecisionRecorder.java` | 文本 (.java) | 3.4 KB | 2026-11-02 15:32 | package com.towndemontower.spring.tx; / import com.towndemontower.core.contract.ExecutionContext; |

### `towndemontower-spring-starter\src\main\java\com\towndemontower\spring\web/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `ApiAuthFilter.java` | 文本 (.java) | 9.1 KB | 2026-11-03 09:54 | package com.towndemontower.spring.web; / import com.towndemontower.core.auth.ApiCallerRegistry; |

### `towndemontower-spring-starter\src\main\resources\META-INF\spring/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 文本 (.imports) | 58 B | 2026-11-02 12:05 | com.towndemontower.spring.TownDemontowerAutoConfiguration |

### `towndemontower-spring-starter\src\test\java\com\towndemontower\spring/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `AgentSkillDemo.java` | 文本 (.java) | 2.2 KB | 2026-11-03 22:52 | package com.towndemontower.spring; / import com.towndemontower.core.decorator.GovernancePass; |
| `QuickstartSmokeTest.java` | 文本 (.java) | 5.0 KB | 2026-11-03 22:52 | package com.towndemontower.spring; / import com.towndemontower.core.audit.AuditStore; |
| `StarterDemo.java` | 文本 (.java) | 3.8 KB | 2026-11-03 09:37 | package com.towndemontower.spring; / import com.towndemontower.core.audit.AuditStore; |
| `TestAopConfig.java` | 文本 (.java) | 468 B | 2026-11-02 12:07 | package com.towndemontower.spring; / import org.springframework.context.annotation.Configuration; |
| `TownDemontowerStarterTest.java` | 文本 (.java) | 1.6 KB | 2026-11-02 12:08 | package com.towndemontower.spring; / import com.towndemontower.core.audit.HashChainAuditStore; |

### `towndemontower-spring-starter\src\test\java\com\towndemontower\spring\tx/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `CapturingPublisher.java` | 文本 (.java) | 938 B | 2026-11-02 15:27 | package com.towndemontower.spring.tx; / import java.util.List; |
| `TownDemontowerTxTest.java` | 文本 (.java) | 7.0 KB | 2026-11-02 15:34 | package com.towndemontower.spring.tx; / import com.towndemontower.core.contract.ExecutionContext; |

### `towndemontower-spring-starter\src\test\java\com\towndemontower\spring/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `V111PassEnforcementTest.java` | 文本 (.java) | 5.8 KB | 2026-11-03 23:01 | package com.towndemontower.spring; / import com.towndemontower.core.audit.AuditStore; |
| `V112TemplateWiringTest.java` | 文本 (.java) | 5.6 KB | 2026-11-04 00:00 | package com.towndemontower.spring; / import com.towndemontower.core.contract.ErrorCodes; |
| `V15WiringTest.java` | 文本 (.java) | 12.2 KB | 2026-11-03 22:52 | package com.towndemontower.spring; / import com.towndemontower.core.approval.ApprovalCenter; |
| `V16SecureDefaultsTest.java` | 文本 (.java) | 11.5 KB | 2026-11-03 22:52 | package com.towndemontower.spring; / import com.towndemontower.core.audit.AuditStore; |
| `V18AuthWiringTest.java` | 文本 (.java) | 6.8 KB | 2026-11-03 09:25 | package com.towndemontower.spring; / import com.towndemontower.core.auth.ApiCallerRegistry; |
| `V19AnchorReplicaWiringTest.java` | 文本 (.java) | 12.6 KB | 2026-11-03 22:52 | package com.towndemontower.spring; / import com.towndemontower.core.audit.AnchorReplica; |
| `V19AuditSealWiringTest.java` | 文本 (.java) | 10.0 KB | 2026-11-03 22:52 | package com.towndemontower.spring; / import com.towndemontower.core.audit.AuditStore; |
| `V19CallerBindingWiringTest.java` | 文本 (.java) | 9.4 KB | 2026-11-03 09:55 | package com.towndemontower.spring; / import com.towndemontower.core.audit.AuditStore; |

### `towndemontower-spring-starter\src\test\resources/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `application.properties` | 文本 (.properties) | 469 B | 2026-11-02 15:26 | # 事务一致性测试用：使用内嵌 H2（仅 test 作用域，不污染消费方） / spring.sql.init.mode=always |
| `schema.sql` | 文本 (.sql) | 828 B | 2026-11-02 15:26 | CREATE TABLE IF NOT EXISTS governance_decision ( / id               VARCHAR(36)  PRIMARY KEY, |

### `towndemontower-spring-starter/`

| 文件 | 类型 | 大小 | 最后修改 | 预览 |
| --- | --- | ---: | --- | --- |
| `towndemontower-spring-starter-0.1.0.jar` | 二进制 (.jar) | 45.3 KB | 2026-11-03 11:20 | —（不解析） |

## 四、异常与缺失

- 遍历过程中未遇到权限错误或不可访问的目录。
