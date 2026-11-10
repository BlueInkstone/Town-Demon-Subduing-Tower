import {
  createIntent,
  createContext,
  ok,
  fail,
  ErrorCodes,
  newTicketId,
} from "../src/contract";

// ── 契约层 ────────────────────────────────────────────────────────────────────

describe("createIntent", () => {
  test("基本创建", () => {
    const i = createIntent("shell", { command: "ls" }, 0.9, "列出文件");
    expect(i.tool).toBe("shell");
    expect(i.args.command).toBe("ls");
    expect(i.confidence).toBe(0.9);
  });

  test("空 tool 抛异常", () => {
    expect(() => createIntent("")).toThrow("tool is required");
  });

  test("confidence 越界抛异常", () => {
    expect(() => createIntent("t", {}, 1.5)).toThrow();
    expect(() => createIntent("t", {}, -0.1)).toThrow();
  });

  test("args 被冻结", () => {
    const i = createIntent("t", { k: "v" });
    expect(() => { (i.args as Record<string, unknown>)["x"] = 1; }).toThrow();
  });
});

describe("createContext", () => {
  test("默认 NORMAL", () => {
    const ctx = createContext();
    expect(ctx.mode).toBe("NORMAL");
    expect(ctx.force).toBe(false);
  });

  test("覆盖字段", () => {
    const ctx = createContext({ mode: "SAFE", agentId: "AGT-001", force: true });
    expect(ctx.mode).toBe("SAFE");
    expect(ctx.agentId).toBe("AGT-001");
    expect(ctx.force).toBe(true);
  });
});

describe("SkillResult", () => {
  test("ok", () => {
    const r = ok("done", "1");
    expect(r.success).toBe(true);
    expect(r.output).toBe("done");
    expect(r.auditId).toBe("1");
  });

  test("fail", () => {
    const r = fail(ErrorCodes.PERMISSION_DENIED, "No perm", "TKT-1");
    expect(r.success).toBe(false);
    expect(r.errorCode).toBe(ErrorCodes.PERMISSION_DENIED);
    expect(r.ticketId).toBe("TKT-1");
  });
});

describe("newTicketId", () => {
  test("格式 TKT-xxxx", () => {
    const id = newTicketId();
    expect(id).toMatch(/^TKT-[0-9a-f]{12}$/);
  });
});