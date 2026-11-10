/**
 * REST 客户端测试 - 使用 mock fetch，不产生真实网络请求
 */

import {
  GovernanceRestClient,
  GovernanceApiError,
  type FetchLike,
  type ApprovalStatus,
} from "../src/index";

interface Recorded {
  url: string;
  method: string;
  headers: Record<string, string>;
  body?: string;
}

/** 构造一个可编程的 mock fetch，按序返回状态/JSON */
function makeFetch(
  responses: Array<{ status?: number; json: unknown }>,
  recorded: Recorded[],
): FetchLike {
  let i = 0;
  return async (url, init) => {
    recorded.push({
      url,
      method: init.method,
      headers: init.headers,
      ...(init.body ? { body: init.body } : {}),
    });
    const r = responses[Math.min(i, responses.length - 1)];
    i += 1;
    return {
      status: r.status ?? 200,
      statusText: r.status && r.status >= 400 ? "error" : "OK",
      json: async () => r.json,
    };
  };
}

describe("GovernanceRestClient - 观测端点", () => {
  test("metrics() 返回裸 JSON 并带上令牌头", async () => {
    const rec: Recorded[] = [];
    const payload = {
      counters: { total_invocations: 3, r0_pass: 1, r1_pass: 0, r2_pending: 1, r2_approved: 0, r3_rejected: 1, permission_denied: 0, ungoverned_invocations: 0 },
      denialRate: 0.33,
      falseRejectionRate: 0,
      overConservatismRate: 0,
      misExecutionRate: 0,
      cacheHitRate: 0.5,
    };
    const c = new GovernanceRestClient({ baseUrl: "http://h:8080/", token: "secret", fetchImpl: makeFetch([{ json: payload }], rec) });
    const m = await c.metrics();
    expect(m.counters.total_invocations).toBe(3);
    expect(m.denialRate).toBe(0.33);
    expect(rec[0].url).toBe("http://h:8080/towndemontower/metrics");
    expect(rec[0].method).toBe("GET");
    expect(rec[0].headers["X-Governance-Token"]).toBe("secret");
  });

  test("integrity() 解析自检结果", async () => {
    const rec: Recorded[] = [];
    const payload = {
      auditChainValid: true,
      auditRecords: 42,
      pendingApprovals: 2,
      findings: ["GATE_KEY_WEAK"],
      compliant: false,
      mode: "NORMAL",
    };
    const c = new GovernanceRestClient({ baseUrl: "http://h", fetchImpl: makeFetch([{ json: payload }], rec) });
    const r = await c.integrity();
    expect(r.auditChainValid).toBe(true);
    expect(r.auditRecords).toBe(42);
    expect(r.findings).toEqual(["GATE_KEY_WEAK"]);
    expect(r.compliant).toBe(false);
    expect(rec[0].headers["X-Governance-Token"]).toBeUndefined();
  });
});

describe("GovernanceRestClient - 审批端点（信封）", () => {
  const envelope = <T>(data: T) => ({ code: 0, message: "ok", data, error: null });

  test("listApprovals() 带 status 过滤并解信封", async () => {
    const rec: Recorded[] = [];
    const data = { total: 1, items: [{ id: "t1", tool: "exec", riskLevel: "R3", requestedBy: "alice", status: "PENDING" }] };
    const c = new GovernanceRestClient({ baseUrl: "http://h", fetchImpl: makeFetch([{ json: envelope(data) }], rec) });
    const r = await c.listApprovals("PENDING" as ApprovalStatus);
    expect(r.total).toBe(1);
    expect(r.items[0].id).toBe("t1");
    expect(rec[0].url).toBe("http://h/towndemontower/admin/approvals?status=PENDING");
  });

  test("listApprovals() 无过滤时不带查询串", async () => {
    const rec: Recorded[] = [];
    const c = new GovernanceRestClient({ baseUrl: "http://h", fetchImpl: makeFetch([{ json: envelope({ total: 0, items: [] }) }], rec) });
    const r = await c.listApprovals();
    expect(r.items).toEqual([]);
    expect(rec[0].url).toBe("http://h/towndemontower/admin/approvals");
  });

  test("getApproval() 走 GET /{id}", async () => {
    const rec: Recorded[] = [];
    const view = { id: "x1", tool: "deploy", riskLevel: "R3", requestedBy: "bob", status: "PENDING" };
    const c = new GovernanceRestClient({ baseUrl: "http://h", fetchImpl: makeFetch([{ json: envelope(view) }], rec) });
    const r = await c.getApproval("x1");
    expect(r.id).toBe("x1");
    expect(rec[0].method).toBe("GET");
    expect(rec[0].url).toBe("http://h/towndemontower/admin/approvals/x1");
  });

  test("approve() POST 带 approver/comment 体", async () => {
    const rec: Recorded[] = [];
    const view = { id: "x2", tool: "deploy", riskLevel: "R3", requestedBy: "bob", status: "APPROVED", approver: "carol", comment: "ok" };
    const c = new GovernanceRestClient({ baseUrl: "http://h", token: "t", fetchImpl: makeFetch([{ json: envelope(view) }], rec) });
    const r = await c.approve("x2", "carol", "ok");
    expect(r.status).toBe("APPROVED");
    expect(rec[0].method).toBe("POST");
    expect(rec[0].url).toBe("http://h/towndemontower/admin/approvals/x2/approve");
    expect(JSON.parse(rec[0].body!)).toEqual({ approver: "carol", comment: "ok" });
  });

  test("deny() 省略 comment 时不写入体", async () => {
    const rec: Recorded[] = [];
    const view = { id: "x3", tool: "deploy", riskLevel: "R3", requestedBy: "bob", status: "DENIED", approver: "dave" };
    const c = new GovernanceRestClient({ baseUrl: "http://h", fetchImpl: makeFetch([{ json: envelope(view) }], rec) });
    const r = await c.deny("x3", "dave");
    expect(r.status).toBe("DENIED");
    expect(JSON.parse(rec[0].body!)).toEqual({ approver: "dave" });
  });

  test("approve() 缺 approver 直接抛参数错误，不发请求", async () => {
    const rec: Recorded[] = [];
    const c = new GovernanceRestClient({ baseUrl: "http://h", fetchImpl: makeFetch([{ json: envelope({}) }], rec) });
    await expect(c.approve("x", "")).rejects.toThrow(/approver is required/);
    expect(rec.length).toBe(0);
  });
});

describe("GovernanceRestClient - 错误处理", () => {
  test("信封 code!=0 抛 GovernanceApiError（业务错误码）", async () => {
    const rec: Recorded[] = [];
    const body = { code: 409, message: "审批单已是 APPROVED", data: null, error: "NOT_PENDING" };
    const c = new GovernanceRestClient({ baseUrl: "http://h", fetchImpl: makeFetch([{ status: 200, json: body }], rec) });
    await expect(c.getApproval("x")).rejects.toBeInstanceOf(GovernanceApiError);
    try {
      await c.getApproval("x");
    } catch (e) {
      const err = e as GovernanceApiError;
      expect(err.errorCode).toBe("NOT_PENDING");
    }
  });

  test("HTTP 401 且响应体为信封时解析 error", async () => {
    const rec: Recorded[] = [];
    const body = { code: 401, message: "无效的治理令牌", data: null, error: "UNAUTHORIZED" };
    const c = new GovernanceRestClient({ baseUrl: "http://h", fetchImpl: makeFetch([{ status: 401, json: body }], rec) });
    await expect(c.getApproval("x")).rejects.toMatchObject({ errorCode: "UNAUTHORIZED", status: 401 });
  });

  test("观测端点 HTTP 403 抛 HTTP_403", async () => {
    const rec: Recorded[] = [];
    const c = new GovernanceRestClient({ baseUrl: "http://h", fetchImpl: makeFetch([{ status: 403, json: {} }], rec) });
    await expect(c.metrics()).rejects.toMatchObject({ errorCode: "HTTP_403" });
  });

  test("网络异常包装为 NETWORK_ERROR", async () => {
    const failFetch: FetchLike = async () => {
      throw new Error("ECONNREFUSED");
    };
    const c = new GovernanceRestClient({ baseUrl: "http://h", fetchImpl: failFetch });
    await expect(c.metrics()).rejects.toMatchObject({ errorCode: "NETWORK_ERROR", status: 0 });
  });

  test("baseUrl 缺失抛错", () => {
    expect(() => new GovernanceRestClient({} as never)).toThrow(/baseUrl is required/);
  });

  test("去掉 baseUrl 末尾斜杠", async () => {
    const rec: Recorded[] = [];
    const c = new GovernanceRestClient({ baseUrl: "http://h///", fetchImpl: makeFetch([{ json: { ok: 1 } }], rec) });
    await c.integrity();
    expect(rec[0].url).toBe("http://h/towndemontower/integrity");
  });
});