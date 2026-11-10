/**
 * 基础用法示例 - Town Demontower TypeScript SDK
 *
 * 运行方式：
 *   npx ts-node examples/basic_usage.ts
 *
 * 演示：创建客户端、注册只读技能、调用并读取结果与审计。
 */

import { TownDemontowerClient, RiskLevel } from "../src/index";

async function main(): Promise<void> {
  // 1. 创建客户端（默认使用内存哈希链审计存储）
  const client = new TownDemontowerClient();

  // 2. 注册一个低风险只读技能（R0 自动放行）
  client.skill(
    "greet",
    {
      risk: RiskLevel.R0,
      requiredArgs: ["name"],
      description: "向指定用户打招呼",
    },
    (args) => `Hello, ${String(args.name)}!`,
  );

  // 3. 注册一个中风险技能（R2 需要审批，会被拦截）
  client.skill(
    "update_record",
    { risk: RiskLevel.R2 },
    (args) => `updated: ${JSON.stringify(args)}`,
  );

  // 4. 调用低风险技能 —— 成功
  const okRes = await client.invoke("greet", { name: "World" });
  console.log("[greet]      success=%s output=%s", okRes.success, okRes.output);

  // 5. 调用中风险技能 —— 触发审批拦截
  const gated = await client.invoke("update_record", { id: 42 });
  console.log("[update]     success=%s errorCode=%s", gated.success, gated.errorCode);

  // 6. 查看审计记录
  const records = client.getAuditRecords();
  console.log("[audit]      共 %d 条记录，链完整性=%s", records.length, client.verifyAuditChain());
  for (const r of records) {
    console.log("   - seq=%d action=%s", r.seq, r.action);
  }

  // 7. 查看治理指标
  console.log("[metrics]    %j", client.getMetrics());
}

main().catch((err) => {
  console.error("示例执行失败:", err);
  process.exit(1);
});