import { RiskLevel, ErrorCodes } from "../src/contract";
import { TownDemontowerClient } from "../src/client";

describe("TownDemontowerClient", () => {
  let client: TownDemontowerClient;

  beforeEach(() => { client = new TownDemontowerClient(); });

  test("注册并调用 R0", async () => {
    client.skill("greet", { risk: RiskLevel.R0 }, (args) => `Hello, ${args.name}!`);
    const r = await client.invoke("greet", { name: "World" });
    expect(r.success).toBe(true);
    expect(r.output).toBe("Hello, World!");
  });

  test("R3 被拒绝", async () => {
    client.skill("delete_all", { risk: RiskLevel.R3 }, () => true);
    const r = await client.invoke("delete_all", {});
    expect(r.success).toBe(false);
    expect(r.errorCode).toBe(ErrorCodes.RISK_REJECTED);
  });

  test("R2 需要审批", async () => {
    client.skill("modify_config", { risk: RiskLevel.R2 }, () => true);
    const r = await client.invoke("modify_config", {});
    expect(r.success).toBe(false);
    expect(r.errorCode).toBe(ErrorCodes.APPROVAL_REQUIRED);
  });

  test("未知工具", async () => {
    const r = await client.invoke("unknown", {});
    expect(r.success).toBe(false);
    expect(r.errorCode).toBe(ErrorCodes.CAPABILITY_EXCEEDED);
  });

  test("审计记录写入", async () => {
    client.skill("audit_test", { risk: RiskLevel.R0 }, () => "ok");
    await client.invoke("audit_test", {});
    const records = client.getAuditRecords();
    const actions = records.map((r) => r.action);
    expect(actions).toContain("RISK_EVALUATED");
    expect(actions).toContain("SKILL_INVOKED");
  });

  test("审计链验证", async () => {
    client.skill("chain_test", { risk: RiskLevel.R0 }, () => true);
    await client.invoke("chain_test", {});
    expect(client.verifyAuditChain()).toBe(true);
  });

  test("指标统计", async () => {
    client.skill("m", { risk: RiskLevel.R0 }, () => "ok");
    await client.invoke("m", {});
    const metrics = client.getMetrics();
    expect(metrics.total_invocations).toBe(1);
    expect(metrics.r0_pass).toBe(1);
  });

  test("命令模板验证通过", async () => {
    client.skill(
      "safe_shell",
      { risk: RiskLevel.R0, commandTemplate: "ls {path*}" },
      (args) => `ran: ${args.command}`,
    );
    const r = await client.invoke("safe_shell", { command: "ls /home/user" });
    expect(r.success).toBe(true);
  });

  test("命令模板注入拒绝", async () => {
    client.skill(
      "safe_shell",
      { risk: RiskLevel.R0, commandTemplate: "ls {path*}" },
      (args) => `ran: ${args.command}`,
    );
    const r = await client.invoke("safe_shell", { command: "ls /home; rm -rf /" });
    expect(r.success).toBe(false);
    expect(r.errorCode).toBe(ErrorCodes.COMMAND_TEMPLATE_MISMATCH);
  });

  test("注销技能", async () => {
    client.skill("temp", { risk: RiskLevel.R0 }, () => "ok");
    client.unregisterSkill("temp");
    const r = await client.invoke("temp", {});
    expect(r.success).toBe(false);
  });

  test("listSkills", () => {
    client.skill("s1", { risk: RiskLevel.R0 }, () => {});
    client.skill("s2", { risk: RiskLevel.R1 }, () => {});
    const names = client.listSkills().map((s) => s.name);
    expect(names).toContain("s1");
    expect(names).toContain("s2");
  });

  // ── 统一执行链路（client → executor.invoke）行为锁定 ──

  test("异步处理函数经执行器正确 await", async () => {
    client.skill(
      "fetch",
      { risk: RiskLevel.R0 },
      async (args) => {
        await Promise.resolve();
        return `fetched:${String(args.id)}`;
      },
    );
    const r = await client.invoke("fetch", { id: 7 });
    expect(r.success).toBe(true);
    expect(r.output).toBe("fetched:7");
  });

  test("处理函数抛错映射为 EXECUTION_ERROR 并记录 SKILL_FAILED", async () => {
    client.skill("boom", { risk: RiskLevel.R0 }, () => {
      throw new Error("handler crashed");
    });
    const r = await client.invoke("boom", {});
    expect(r.success).toBe(false);
    expect(r.errorCode).toBe("EXECUTION_ERROR");
    expect(r.errorMessage).toContain("handler crashed");

    const actions = client.getAuditRecords().map((rec) => rec.action);
    expect(actions).toContain("SKILL_INVOKED");
    expect(actions).toContain("SKILL_FAILED");
    expect(client.verifyAuditChain()).toBe(true);
  });

  test("成功调用写入 auditId 且技能由执行器托管", async () => {
    client.skill("g", { risk: RiskLevel.R0 }, () => "hi");
    const r = await client.invoke("g", {});
    expect(r.success).toBe(true);
    expect(typeof r.auditId).toBe("string");
    // 注销后执行器不再持有该技能
    client.unregisterSkill("g");
    const missing = await client.invoke("g", {});
    expect(missing.errorCode).toBe(ErrorCodes.CAPABILITY_EXCEEDED);
  });
});