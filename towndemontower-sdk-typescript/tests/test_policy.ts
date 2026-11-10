import { createIntent, createContext, RiskLevel } from "../src/contract";
import { DefaultRiskGate, CommandTemplateValidator } from "../src/policy";

describe("DefaultRiskGate", () => {
  const gate = new DefaultRiskGate();

  test("descriptor R0", () => {
    const intent = createIntent("get_user", {});
    const ctx = createContext();
    const desc = { name: "get_user", risk: RiskLevel.R0, requiredPermissions: [], requiredCapabilities: [], requiredArgs: [] };
    const { risk, ticket } = gate.evaluate(intent, desc, ctx);
    expect(risk).toBe(RiskLevel.R0);
    expect(ticket).toBeNull();
  });

  test("descriptor R3", () => {
    const intent = createIntent("delete_all", {});
    const ctx = createContext();
    const desc = { name: "delete_all", risk: RiskLevel.R3, requiredPermissions: [], requiredCapabilities: [], requiredArgs: [] };
    const { risk, ticket } = gate.evaluate(intent, desc, ctx);
    expect(risk).toBe(RiskLevel.R3);
    expect(ticket).not.toBeNull();
  });

  test("启发式只读前缀→R0", () => {
    const { risk } = gate.evaluate(createIntent("get_user", {}), null, createContext());
    expect(risk).toBe(RiskLevel.R0);
  });

  test("启发式高危词→R3", () => {
    const { risk } = gate.evaluate(createIntent("delete_system", {}), null, createContext());
    expect(risk).toBe(RiskLevel.R3);
  });

  test("默认→R1", () => {
    const { risk } = gate.evaluate(createIntent("update_record", {}), null, createContext());
    expect(risk).toBe(RiskLevel.R1);
  });
});

describe("CommandTemplateValidator", () => {
  test("字面量匹配", () => {
    expect(CommandTemplateValidator.validate("ls -la", "ls -la").valid).toBe(true);
  });

  test("字面量不匹配", () => {
    const r = CommandTemplateValidator.validate("ls -l", "ls -la");
    expect(r.valid).toBe(false);
    expect(r.error).toContain("Expected");
  });

  test("单值槽位", () => {
    expect(CommandTemplateValidator.validate("ls /home", "ls {path}").valid).toBe(true);
  });

  test("单值槽位禁止 - 开头", () => {
    const r = CommandTemplateValidator.validate("ls -la", "ls {path}");
    expect(r.valid).toBe(false);
    expect(r.error).toContain("Single-value");
  });

  test("禁止控制字符", () => {
    const r = CommandTemplateValidator.validate("ls /home;rm", "ls {path}");
    expect(r.valid).toBe(false);
    expect(r.error).toContain("forbidden");
  });

  test("尾部通配符消费多词", () => {
    expect(CommandTemplateValidator.validate("cmd arg1 arg2 arg3", "cmd {args*}").valid).toBe(true);
  });

  test("git commit {args*} 允许多选项", () => {
    expect(CommandTemplateValidator.validate("git commit -m 'msg'", "git commit {args*}").valid).toBe(false); // 含单引号
    expect(CommandTemplateValidator.validate("git commit -am msg", "git commit {args*}").valid).toBe(true);
  });

  test("多余参数（无通配符）", () => {
    const r = CommandTemplateValidator.validate("ls /a /b /c", "ls {path}");
    expect(r.valid).toBe(false);
    expect(r.error).toContain("Extra");
  });

  test("缺少参数", () => {
    const r = CommandTemplateValidator.validate("ls", "ls -la");
    expect(r.valid).toBe(false);
    expect(r.error).toContain("Missing");
  });

  test("空命令", () => {
    expect(CommandTemplateValidator.validate("", "ls {path}").valid).toBe(false);
  });
});