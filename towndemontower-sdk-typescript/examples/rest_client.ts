/**
 * 示例: REST 客户端（GovernanceRestClient）
 *
 * 演示如何对齐 spring-starter 治理端点做观测与审批管理。
 * 为可离线运行，这里注入一个"假 fetch"模拟服务端响应；
 * 真实使用时去掉 fetchImpl，并配置 baseUrl 与 token 即可。
 *
 * 运行: npm run example:rest
 */

import {
  GovernanceRestClient,
  GovernanceApiError,
  type ApprovalStatus,
  type FetchLike,
} from "../src/index";

/** 假 fetch：根据 URL 路由到不同的模拟响应，并记录请求 */
function makeFakeFetch(
  log: Array<{ method: string; url: string; token?: string; body?: string }>,
): FetchLike {
  return async (url, init) => {
    log.push({
      method: init.method,
      url,
      token: init.headers["X-Governance-Token"],
      ...(init.body ? { body: init.body } : {}),
    });

    // 模拟一个 404（对未知审批单 id），演示错误分支
    if (url.endsWith("/approvals/missing")) {
      return {
        status: 404,
        statusText: "Not Found",
        json: async () => ({ code: 404, message: "审批单不存在: missing", data: null, error: "NOT_FOUND" }),
      };
    }

    if (url.includes("/metrics")) {
      return {
        status: 200,
        json: async () => ({
          counters: {
            total_invocations: 128, r0_pass: 90, r1_pass: 20, r2_pending: 6,
            r2_approved: 3, r3_rejected: 9, permission_denied: 4, ungoverned_invocations: 0,
          },
          denialRate: 0.07, falseRejectionRate: 0.01, overConservatismRate: 0.02,
          misExecutionRate: 0.0, cacheHitRate: 0.45,
        }),
      };
    }

    if (url.includes("/integrity")) {
      return {
        status: 200,
        json: async () => ({
          auditChainValid: true, auditRecords: 512, auditAnchorLag: 0,
          pendingApprovals: 6, findings: [], compliant: true, mode: "STRICT",
        }),
      };
    }

    // 审批相关：列表
    if (url.endsWith("/approvals") || url.includes("/approvals?")) {
      return {
        status: 200,
        json: async () => ({
          code: 0, message: "ok", error: null,
          data: {
            total: 2,
            items: [
              { id: "apv-1", tool: "db.drop", riskLevel: "R3", requestedBy: "alice", status: "PENDING" },
              { id: "apv-2", tool: "deploy.prod", riskLevel: "R3", requestedBy: "bob", status: "PENDING" },
            ],
          },
        }),
      };
    }

    // 审批相关：批准（POST .../approve）
    if (url.endsWith("/approve")) {
      const approver = JSON.parse(init.body ?? "{}").approver ?? "carol";
      return {
        status: 200,
        json: async () => ({
          code: 0, message: "ok", error: null,
          data: { id: "apv-1", tool: "db.drop", riskLevel: "R3", requestedBy: "alice", status: "APPROVED", approver, comment: "lgtm" },
        }),
      };
    }

    // 审批相关：单个查询
    return {
      status: 200,
      json: async () => ({
        code: 0, message: "ok", error: null,
        data: { id: "apv-1", tool: "db.drop", riskLevel: "R3", requestedBy: "alice", status: "PENDING" },
      }),
    };
  };
}

async function main(): Promise<void> {
  const log: Array<{ method: string; url: string; token?: string; body?: string }> = [];
  const client = new GovernanceRestClient({
    baseUrl: "http://localhost:8080",
    token: "s3cr3t-governance-token",
    fetchImpl: makeFakeFetch(log),
  });

  console.log("=== 治理指标 (/towndemontower/metrics) ===");
  const metrics = await client.metrics();
  console.log("总调用:", metrics.counters.total_invocations, "| 拒绝率:", metrics.denialRate, "| 缓存命中:", metrics.cacheHitRate);

  console.log("\n=== 完整性自检 (/towndemontower/integrity) ===");
  const integrity = await client.integrity();
  console.log("审计链有效:", integrity.auditChainValid, "| 记录数:", integrity.auditRecords, "| 合规:", integrity.compliant, "| 待审批:", integrity.pendingApprovals);

  console.log("\n=== 审批单列表（PENDING 过滤） ===");
  const list = await client.listApprovals("PENDING" as ApprovalStatus);
  console.log("数量:", list.total);
  for (const item of list.items) {
    console.log(`  - [${item.status}] ${item.id} tool=${item.tool} by=${item.requestedBy}`);
  }

  console.log("\n=== 批准 apv-1 ===");
  const approved = await client.approve("apv-1", "carol", "lgtm");
  console.log("结果状态:", approved.status, "| 审批人:", approved.approver);

  console.log("\n=== 错误处理：查询不存在的审批单 ===");
  try {
    await client.getApproval("missing");
  } catch (err) {
    if (err instanceof GovernanceApiError) {
      console.log("捕获 GovernanceApiError -> status:", err.status, "errorCode:", err.errorCode, "|", err.message);
    }
  }

  console.log("\n=== 实际发出的请求（含鉴权头） ===");
  for (const r of log) {
    console.log(`  ${r.method} ${r.url.replace("http://localhost:8080", "")} token=${r.token ?? "-"}`);
  }
}

main().catch((e) => {
  console.error("示例运行失败:", e);
  process.exitCode = 1;
});