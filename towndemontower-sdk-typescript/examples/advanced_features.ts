/**
 * 高级特性示例 - Town Demontower TypeScript SDK
 *
 * 运行方式：
 *   npx ts-node examples/advanced_features.ts
 *
 * 演示：
 *   - 命令模板防护（防注入）
 *   - 文件审计存储 + 哈希链验证
 *   - 治理通行证 GovernancePass
 *   - 独立使用执行器 TypeScriptFunctionExecutor / SkillRegistry
 */

import { existsSync, rmSync } from "fs";
import { tmpdir } from "os";
import { join } from "path";

import {
  TownDemontowerClient,
  FileAuditStore,
  RiskLevel,
  ErrorCodes,
  GovernancePass,
  UngovernedInvocationError,
  TypeScriptFunctionExecutor,
  SkillRegistry,
  createIntent,
  createContext,
} from "../src/index";

async function demoCommandTemplate(): Promise<void> {
  console.log("\n=== 1. 命令模板防护（防注入） ===");
  const client = new TownDemontowerClient();
  client.skill(
    "safe_shell",
    { risk: RiskLevel.R0, commandTemplate: "ls {path*}" },
    (args) => `ran: ${String(args.command)}`,
  );

  const good = await client.invoke("safe_shell", { command: "ls /var/log" });
  console.log("合法命令:   success=%s", good.success);

  const evil = await client.invoke("safe_shell", { command: "ls /; rm -rf /" });
  console.log("注入命令:   success=%s errorCode=%s", evil.success, evil.errorCode);
}

async function demoFileAudit(): Promise<void> {
  console.log("\n=== 2. 文件审计存储 + 哈希链 ===");
  const file = join(tmpdir(), `tdt-audit-${Date.now()}.jsonl`);
  try {
    const store = new FileAuditStore(file);
    store.append("SKILL_INVOKED", { tool: "read", user: "alice" });
    store.append("SKILL_INVOKED", { tool: "write", user: "bob" });
    console.log("落盘审计条数=%d 链完整性=%s", store.size(), store.verify());

    // 重新加载验证持久化
    const reloaded = new FileAuditStore(file);
    console.log("重载条数=%d 链完整性=%s", reloaded.size(), reloaded.verify());
  } finally {
    if (existsSync(file)) rmSync(file);
  }
}

function demoGovernancePass(): void {
  console.log("\n=== 3. 治理通行证 GovernancePass ===");

  // 未授予令牌时，require 会抛出异常
  try {
    GovernancePass.require("checkout");
  } catch (err) {
    console.log(
      "无令牌调用被拒:",
      err instanceof UngovernedInvocationError ? "UNGOVERNED_INVOCATION" : String(err),
    );
  }

  // 在作用域内自动发放/清理令牌
  GovernancePass.withScope("audit-001", "checkout", () => {
    const token = GovernancePass.require("checkout");
    console.log("作用域内获取令牌 auditId=%s", token.auditId);
  });
  console.log("作用域结束后 current=%s", GovernancePass.current());
}

function demoExecutorRegistry(): void {
  console.log("\n=== 4. 执行器与技能注册表 ===");
  const executor = new TypeScriptFunctionExecutor();
  executor.register(
    "add",
    (args) => Number(args.a) + Number(args.b),
    {
      name: "add",
      risk: RiskLevel.R0,
      requiredPermissions: [],
      requiredCapabilities: [],
      requiredArgs: ["a", "b"],
    },
  );

  const intent = createIntent("add", { a: 2, b: 3 });
  const result = executor.execute(intent, createContext());
  console.log("add(2,3) = %s (errorCode=%s)", result.output, result.errorCode);

  const registry = new SkillRegistry();
  registry.register({
    name: "add",
    risk: RiskLevel.R0,
    requiredPermissions: [],
    requiredCapabilities: [],
    requiredArgs: ["a", "b"],
  });
  console.log("注册表技能:", registry.names());
}

async function main(): Promise<void> {
  await demoCommandTemplate();
  await demoFileAudit();
  demoGovernancePass();
  demoExecutorRegistry();
  console.log("\n所有高级特性示例执行完毕。");
}

main().catch((err) => {
  console.error("示例执行失败:", err);
  process.exit(1);
});