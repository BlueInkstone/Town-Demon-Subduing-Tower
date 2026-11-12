/**
 * 方向 D 第③步：SkillResult wire 结构统一验收（TypeScript 侧）。
 *
 * golden 直接读仓库根 `contract/skill-result.golden.json`，
 * 保证 toWire() 与 Java / Python 产出同一份结构。
 */

import { readFileSync } from "fs";
import { resolve } from "path";

import {
  ErrorCodes,
  SkillResult,
  SkillResultWire,
  fail,
  fromWire,
  ok,
  toWire,
} from "../src/index";
import { CANONICAL_CODES, SUCCESS_CODE } from "../src/codes";

interface Golden {
  wireFields: string[];
  languageLocalOnly: string[];
  cases: Array<{ id: string; wire: Record<string, unknown> }>;
}

const golden: Golden = JSON.parse(
  readFileSync(resolve(__dirname, "../../contract/skill-result.golden.json"), "utf8"),
);
const schema = JSON.parse(
  readFileSync(resolve(__dirname, "../../contract/towndemontower-wire.schema.json"), "utf8"),
);

function wireOf(id: string): Record<string, unknown> {
  const c = golden.cases.find((x) => x.id === id);
  if (!c) throw new Error(`missing golden case: ${id}`);
  return c.wire;
}

describe("方向 D · SkillResult wire 结构", () => {
  test("schema required 与 golden 字段集一致", () => {
    const required: string[] = schema.$defs.SkillResult.required;
    expect(new Set(golden.wireFields)).toEqual(new Set(required));
    expect(schema.$defs.SkillResult.additionalProperties).toBe(false);
  });

  test("golden 全量 case 逐字段一致", () => {
    const results: Record<string, SkillResult> = {
      ok: ok("done"),
      "ok-object-output": ok({ items: [1, 2], label: "batch" }),
      "denied-permission": fail("PERMISSION_DENIED", "No permission"),
      "pending-approval-alias": fail(
        ErrorCodes.APPROVAL_REQUIRED,
        "awaiting human approval",
        "TKT-1",
      ),
      "r3-denied-alias": fail(ErrorCodes.RISK_REJECTED, "irreversible action blocked"),
    };
    golden.cases.forEach((c) => {
      expect(toWire(results[c.id])).toEqual(c.wire);
    });
  });

  test("wire 不含语言本地扩展", () => {
    const w = toWire(ok("x", "audit-7"));
    expect(Object.keys(w).sort()).toEqual([...golden.wireFields].sort());
    golden.languageLocalOnly.forEach((f) => expect(w).not.toHaveProperty(f));
  });

  test("别名在 wire 中归一为规范码", () => {
    const w = toWire(fail(ErrorCodes.APPROVAL_REQUIRED, "x"));
    expect(w.code).toBe("PENDING_APPROVAL");
    expect(CANONICAL_CODES.has(String(w.code))).toBe(true);
  });

  test("成功码固定 OK", () => {
    expect(ok("x").code).toBe(SUCCESS_CODE);
    expect(toWire(ok("x")).code).toBe(SUCCESS_CODE);
  });

  test("toWire 对只带旧字段的对象也能兜底推导", () => {
    // 模拟 executor 里直接构造、尚未填规范字段的旧式结果
    const legacy: SkillResult = Object.freeze({
      success: false,
      errorCode: ErrorCodes.RISK_REJECTED,
      errorMessage: "blocked",
      auditId: "9",
    });
    expect(toWire(legacy)).toEqual({
      success: false,
      code: "R3_DENIED",
      message: "blocked",
      output: null,
      approvalTicketId: null,
    });
  });

  test("fromWire 往返一致", () => {
    const original = fail(ErrorCodes.RISK_REJECTED, "irreversible action blocked", "TKT-2");
    const restored = fromWire(toWire(original));
    expect(toWire(restored)).toEqual(toWire(original));
    expect(restored.code).toBe("R3_DENIED");
    expect(restored.auditId).toBeUndefined(); // 本地扩展不进 wire
  });

  test("本地字段与旧行为不变（零回归）", () => {
    const r = fail(ErrorCodes.APPROVAL_REQUIRED, "No permission", "TKT-1");
    expect(r.errorCode).toBe(ErrorCodes.APPROVAL_REQUIRED);
    expect(r.errorMessage).toBe("No permission");
    expect(r.ticketId).toBe("TKT-1");
    expect(ok("done").errorCode).toBeUndefined();
    expect(ok("done").output).toBe("done");
  });

  test("wire 类型满足 SkillResultWire", () => {
    const w: SkillResultWire = toWire(ok("done"));
    expect(w.output).toBe("done");
    expect(w.message).toBeNull();
    expect(w.approvalTicketId).toBeNull();
  });

  test("golden 引用的别名 case 输出确为规范码", () => {
    expect(CANONICAL_CODES.has(String(wireOf("pending-approval-alias").code))).toBe(true);
    expect(CANONICAL_CODES.has(String(wireOf("r3-denied-alias").code))).toBe(true);
  });
});
