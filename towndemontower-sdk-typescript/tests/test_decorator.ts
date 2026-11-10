import {
  GovernancePass,
  UngovernedInvocationError,
  MetricsCollector,
} from "../src/decorator";

describe("GovernancePass", () => {
  beforeEach(() => {
    while (GovernancePass.current() !== null) GovernancePass.clear();
  });

  test("issue + require", () => {
    GovernancePass.issue("1", "test");
    const t = GovernancePass.require("test");
    expect(t.auditId).toBe("1");
  });

  test("无令牌时抛异常", () => {
    expect(() => GovernancePass.require("test")).toThrow(UngovernedInvocationError);
  });

  test("withScope 自动清理", () => {
    GovernancePass.withScope("1", "outer", (t) => {
      expect(GovernancePass.require("outer").auditId).toBe("1");
    });
    expect(GovernancePass.current()).toBeNull();
  });

  test("嵌套 scope", () => {
    GovernancePass.withScope("1", "outer", () => {
      GovernancePass.withScope("2", "inner", () => {
        expect(GovernancePass.require("inner").auditId).toBe("2");
      });
      expect(GovernancePass.require("outer").auditId).toBe("1");
    });
  });

  test("consume 一次性", () => {
    const t = GovernancePass.issue("1", "test");
    expect(GovernancePass.consume(t)).toBe(true);
    expect(GovernancePass.consume(t)).toBe(false);
  });
});

describe("MetricsCollector", () => {
  test("记录各风险级别", () => {
    const m = new MetricsCollector();
    m.recordInvocation(); m.recordR0Pass();
    m.recordInvocation(); m.recordR1Pass();
    m.recordInvocation(); m.recordR3Rejected();
    const s = m.snapshot();
    expect(s.total_invocations).toBe(3);
    expect(s.r0_pass).toBe(1);
    expect(s.r1_pass).toBe(1);
    expect(s.r3_rejected).toBe(1);
  });

  test("快照不可变", () => {
    const m = new MetricsCollector();
    m.recordInvocation();
    const s1 = m.snapshot();
    m.recordInvocation();
    const s2 = m.snapshot();
    expect(s1.total_invocations).toBe(1);
    expect(s2.total_invocations).toBe(2);
  });
});