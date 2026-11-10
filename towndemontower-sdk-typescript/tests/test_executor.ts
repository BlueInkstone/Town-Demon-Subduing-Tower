/**
 * Executor 模块测试 - 对齐 test_executor.py
 */

import {
  IntentObject,
  ExecutionContext,
  SkillResult,
  SkillDescriptor,
  RiskLevel,
  ErrorCodes,
  createIntent,
  createContext,
} from "../src/contract";
import {
  Executor,
  SkillInvoker,
  CapabilityException,
  IntentFormatException,
  SchemaValidationException,
  PermissionDeniedException,
  RejectAllExecutor,
  TypeScriptFunctionExecutor,
  SkillRegistry,
} from "../src/executor";

// ── 异常类测试 ────────────────────────────────────────────────────────────────────

describe("异常类", () => {
  test("CapabilityException", () => {
    const err = new CapabilityException();
    expect(err.name).toBe("CapabilityException");
    expect(err.message).toBe("Capability exceeded");
  });

  test("IntentFormatException", () => {
    const err = new IntentFormatException();
    expect(err.name).toBe("IntentFormatException");
  });

  test("SchemaValidationException", () => {
    const err = new SchemaValidationException();
    expect(err.name).toBe("SchemaValidationException");
  });

  test("PermissionDeniedException", () => {
    const err = new PermissionDeniedException();
    expect(err.name).toBe("PermissionDeniedException");
  });
});

// ── RejectAllExecutor 测试 ─────────────────────────────────────────────────────────

describe("RejectAllExecutor", () => {
  test("拒绝所有执行", async () => {
    const executor = new RejectAllExecutor();
    const intent = createIntent("test_tool", {});
    const ctx = createContext();
    const result = executor.execute(intent, ctx);

    if (result instanceof Promise) {
      const r = await result;
      expect(r.success).toBe(false);
      expect(r.errorCode).toBe("INTERNAL_ERROR");
      expect(r.errorMessage).toContain("Governance is disabled");
    } else {
      expect(result.success).toBe(false);
      expect(result.errorCode).toBe("INTERNAL_ERROR");
      expect(result.errorMessage).toContain("Governance is disabled");
    }
  });

  test("带审计存储时记录拒绝事件", () => {
    const auditStore = {
      append: jest.fn().mockReturnValue("audit-123"),
    };
    const executor = new RejectAllExecutor(auditStore as any);
    const intent = createIntent("blocked_tool", {});
    const ctx = createContext({ mode: "NORMAL" });

    const result = executor.execute(intent, ctx);

    expect(auditStore.append).toHaveBeenCalledWith(
      "SKILL_REJECTED",
      {
        tool: "blocked_tool",
        reason: "GOVERNANCE_DISABLED",
        mode: "NORMAL",
      },
    );
    expect(result.auditId).toBe("audit-123");
  });
});

// ── TypeScriptFunctionExecutor 测试 ─────────────────────────────────────────────────────

describe("TypeScriptFunctionExecutor", () => {
  let executor: TypeScriptFunctionExecutor;

  beforeEach(() => {
    executor = new TypeScriptFunctionExecutor();
  });

  test("注册并调用同步函数", async () => {
    executor.register(
      "greet",
      (args) => `Hello, ${args.name}!`,
      {
        name: "greet",
        risk: RiskLevel.R0,
        requiredPermissions: [],
        requiredCapabilities: [],
        requiredArgs: ["name"],
      },
    );

    const intent = createIntent("greet", { name: "World" });
    const ctx = createContext();
    const result = executor.execute(intent, ctx);

    expect(result.success).toBe(true);
    expect(result.output).toBe("Hello, World!");
  });

  test("异步函数返回 ASYNC_PENDING 错误", () => {
    executor.register(
      "async_fetch",
      async (args) => {
        await new Promise((resolve) => setTimeout(resolve, 10));
        return `Fetched: ${args.url}`;
      },
      {
        name: "async_fetch",
        risk: RiskLevel.R1,
        requiredPermissions: [],
        requiredCapabilities: [],
        requiredArgs: ["url"],
      },
    );

    const intent = createIntent("async_fetch", { url: "api/data" });
    const ctx = createContext();
    const result = executor.execute(intent, ctx);

    // 由于 Executor 接口是同步的，异步函数会返回 ASYNC_PENDING 错误
    expect(result.success).toBe(false);
    expect(result.errorCode).toBe("ASYNC_PENDING");
    expect(result.errorMessage).toContain("Async execution not supported");
  });

  test("未知工具返回 CAPABILITY_EXCEEDED", async () => {
    const intent = createIntent("unknown_tool", {});
    const ctx = createContext();
    const result = executor.execute(intent, ctx);

    if (result instanceof Promise) {
      const r = await result;
      expect(r.success).toBe(false);
      expect(r.errorCode).toBe(ErrorCodes.CAPABILITY_EXCEEDED);
    } else {
      expect(result.success).toBe(false);
      expect(result.errorCode).toBe(ErrorCodes.CAPABILITY_EXCEEDED);
    }
  });

  test("注销技能", async () => {
    executor.register("temp", () => "ok");
    executor.unregister("temp");

    const intent = createIntent("temp", {});
    const ctx = createContext();
    const result = executor.execute(intent, ctx);

    if (result instanceof Promise) {
      const r = await result;
      expect(r.success).toBe(false);
    } else {
      expect(result.success).toBe(false);
    }
  });

  test("列出所有技能", () => {
    executor.register("s1", () => {});
    executor.register("s2", () => {});
    executor.register("s3", () => {});

    const skills = executor.listSkills();
    expect(skills).toContain("s1");
    expect(skills).toContain("s2");
    expect(skills).toContain("s3");
    expect(skills.length).toBe(3);
  });

  test("获取技能描述符", () => {
    const desc: SkillDescriptor = {
      name: "test_skill",
      risk: RiskLevel.R1,
      requiredPermissions: ["perm1"],
      requiredCapabilities: ["cap1"],
      requiredArgs: ["arg1"],
    };
    executor.register("test_skill", () => {}, desc);

    const retrieved = executor.getDescriptor("test_skill");
    expect(retrieved).toBeDefined();
    expect(retrieved?.name).toBe("test_skill");
    expect(retrieved?.risk).toBe(RiskLevel.R1);
  });

  test("函数抛出异常时返回失败结果", () => {
    executor.register(
      "failing",
      () => {
        throw new Error("Intentional failure");
      },
      {
        name: "failing",
        risk: RiskLevel.R0,
        requiredPermissions: [],
        requiredCapabilities: [],
        requiredArgs: [],
      },
    );

    const intent = createIntent("failing", {});
    const ctx = createContext();
    const result = executor.execute(intent, ctx);

    expect(result.success).toBe(false);
    expect(result.errorCode).toBe("INTERNAL_ERROR");
    expect(result.errorMessage).toContain("Intentional failure");
  });

  test("支持 ExecutionContext 参数", async () => {
    executor.register(
      "ctx_test",
      (args, ctx) => `${ctx.mode}-${ctx.agentId}`,
      {
        name: "ctx_test",
        risk: RiskLevel.R0,
        requiredPermissions: [],
        requiredCapabilities: [],
        requiredArgs: [],
      },
    );

    const intent = createIntent("ctx_test", {});
    const ctx = createContext({ mode: "SAFE", agentId: "AGT-001" });
    const result = executor.execute(intent, ctx);

    if (result instanceof Promise) {
      const r = await result;
      expect(r.success).toBe(true);
      expect(r.output).toBe("SAFE-AGT-001");
    } else {
      expect(result.success).toBe(true);
      expect(result.output).toBe("SAFE-AGT-001");
    }
  });
});

// ── SkillRegistry 测试 ──────────────────────────────────────────────────────────────

describe("SkillRegistry", () => {
  let registry: SkillRegistry;

  beforeEach(() => {
    registry = new SkillRegistry();
  });

  test("注册和获取技能", () => {
    const desc: SkillDescriptor = {
      name: "test_skill",
      risk: RiskLevel.R1,
      requiredPermissions: [],
      requiredCapabilities: [],
      requiredArgs: [],
    };
    registry.register(desc);

    const retrieved = registry.get("test_skill");
    expect(retrieved).toBeDefined();
    expect(retrieved?.name).toBe("test_skill");
  });

  test("注销技能", () => {
    const desc: SkillDescriptor = {
      name: "temp_skill",
      risk: RiskLevel.R0,
      requiredPermissions: [],
      requiredCapabilities: [],
      requiredArgs: [],
    };
    registry.register(desc);
    registry.unregister("temp_skill");

    expect(registry.get("temp_skill")).toBeUndefined();
  });

  test("检查技能是否存在", () => {
    const desc: SkillDescriptor = {
      name: "exists",
      risk: RiskLevel.R0,
      requiredPermissions: [],
      requiredCapabilities: [],
      requiredArgs: [],
    };
    registry.register(desc);

    expect(registry.contains("exists")).toBe(true);
    expect(registry.contains("missing")).toBe(false);
  });

  test("列出所有技能名称", () => {
    const desc1: SkillDescriptor = {
      name: "skill_a",
      risk: RiskLevel.R0,
      requiredPermissions: [],
      requiredCapabilities: [],
      requiredArgs: [],
    };
    const desc2: SkillDescriptor = {
      name: "skill_b",
      risk: RiskLevel.R1,
      requiredPermissions: [],
      requiredCapabilities: [],
      requiredArgs: [],
    };
    registry.register(desc1);
    registry.register(desc2);

    const names = registry.names();
    expect(names).toContain("skill_a");
    expect(names).toContain("skill_b");
    expect(names.length).toBe(2);
  });

  test("获取所有技能描述符", () => {
    const desc1: SkillDescriptor = {
      name: "alpha",
      risk: RiskLevel.R0,
      requiredPermissions: [],
      requiredCapabilities: [],
      requiredArgs: [],
    };
    const desc2: SkillDescriptor = {
      name: "beta",
      risk: RiskLevel.R2,
      requiredPermissions: [],
      requiredCapabilities: [],
      requiredArgs: [],
    };
    registry.register(desc1);
    registry.register(desc2);

    const all = registry.all();
    expect(all.length).toBe(2);
    expect(all.some((d: SkillDescriptor) => d.name === "alpha")).toBe(true);
    expect(all.some((d: SkillDescriptor) => d.name === "beta")).toBe(true);
  });

  test("重复注册覆盖旧描述符", () => {
    const desc1: SkillDescriptor = {
      name: "overwrite",
      risk: RiskLevel.R0,
      requiredPermissions: [],
      requiredCapabilities: [],
      requiredArgs: [],
    };
    const desc2: SkillDescriptor = {
      name: "overwrite",
      risk: RiskLevel.R3,
      requiredPermissions: [],
      requiredCapabilities: [],
      requiredArgs: [],
    };
    registry.register(desc1);
    registry.register(desc2);

    const retrieved = registry.get("overwrite");
    expect(retrieved?.risk).toBe(RiskLevel.R3);
  });
});
