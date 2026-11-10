import {
  RiskLevel,
  ErrorCodes,
} from "../src/contract";
import { TownDemontowerClient } from "../src/client";

// ── SDK 端到端集成测试 ──────────────────────────────────────────────────────────────
// 说明：本文件仅保留跨模块的集成工作流验证，单元测试已拆分到
// test_contract.ts / test_audit.ts / test_policy.ts / test_decorator.ts / test_client.ts

describe("SDK 端到端集成", () => {
  test("完整工作流：注册→调用→审计链连续→指标累计", async () => {
    const client = new TownDemontowerClient();
    client.skill("read_cfg", { risk: RiskLevel.R0 }, () => "value=1");
    client.skill("write_cfg", { risk: RiskLevel.R1 }, () => true);

    const r1 = await client.invoke("read_cfg", {});
    const r2 = await client.invoke("write_cfg", {});
    expect(r1.success).toBe(true);
    expect(r2.success).toBe(true);

    // 审计链必须自洽
    expect(client.verifyAuditChain()).toBe(true);

    const records = client.getAuditRecords();
    const riskEvals = records.filter((r) => r.action === "RISK_EVALUATED");
    const invocations = records.filter((r) => r.action === "SKILL_INVOKED");
    expect(riskEvals.length).toBe(2);
    expect(invocations.length).toBe(2);

    const metrics = client.getMetrics();
    expect(metrics.total_invocations).toBe(2);
    expect(metrics.r0_pass).toBe(1);
    expect(metrics.r1_pass).toBe(1);
  });

  test("风险分层：R0 通过 / R2 审批 / R3 拒绝 审计动作齐全", async () => {
    const client = new TownDemontowerClient();
    client.skill("r0", { risk: RiskLevel.R0 }, () => "ok");
    client.skill("r2", { risk: RiskLevel.R2 }, () => true);
    client.skill("r3", { risk: RiskLevel.R3 }, () => true);

    const okRes = await client.invoke("r0", {});
    const approveRes = await client.invoke("r2", {});
    const rejectRes = await client.invoke("r3", {});

    expect(okRes.success).toBe(true);
    expect(approveRes.errorCode).toBe(ErrorCodes.APPROVAL_REQUIRED);
    expect(rejectRes.errorCode).toBe(ErrorCodes.RISK_REJECTED);

    const metrics = client.getMetrics();
    expect(metrics.r0_pass).toBe(1);
    expect(metrics.r3_rejected).toBe(1);

    expect(client.verifyAuditChain()).toBe(true);
  });

  test("命令模板防护贯穿调用链路", async () => {
    const client = new TownDemontowerClient();
    client.skill(
      "shell",
      { risk: RiskLevel.R0, commandTemplate: "ls {path*}" },
      (args) => `ran: ${args.command}`,
    );

    const good = await client.invoke("shell", { command: "ls /var/log" });
    expect(good.success).toBe(true);

    const bad = await client.invoke("shell", { command: "ls /; rm -rf /" });
    expect(bad.success).toBe(false);
    expect(bad.errorCode).toBe(ErrorCodes.COMMAND_TEMPLATE_MISMATCH);

    // 注入尝试不应污染审计链
    expect(client.verifyAuditChain()).toBe(true);
  });
});
