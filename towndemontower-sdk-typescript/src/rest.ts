/**
 * REST 客户端模块 - 对齐 spring-starter 治理端点
 *
 * 封装三类观测/管理接口（与 Java 端契约逐字段对齐）:
 *   - GET  /towndemontower/metrics                    治理指标（裸 JSON）
 *   - GET  /towndemontower/integrity                  完整性自检（裸 JSON）
 *   - GET/POST /towndemontower/admin/approvals[...]   审批单管理（ApiResponse 信封）
 *
 * 鉴权: 请求头 X-Governance-Token。
 * 传输: 使用 Node 18+ 全局 fetch，不引入第三方依赖；可注入自定义 fetch 以便测试。
 */

import type { GovernanceMetricsSnapshot } from "./decorator";

/** 审批单状态 - 对齐 core ApprovalStatus.java */
export type ApprovalStatus = "PENDING" | "APPROVED" | "DENIED" | "EXPIRED";

/** /metrics 响应 - 对齐 GovernanceMetricsController.metrics() */
export interface MetricsReport {
  readonly counters: GovernanceMetricsSnapshot;
  readonly denialRate: number;
  readonly falseRejectionRate: number;
  readonly overConservatismRate: number;
  readonly misExecutionRate: number;
  readonly cacheHitRate: number;
}

/** 锚点副本状态 - 对齐 core AnchorReplicaState.java */
export interface AnchorReplicaView {
  readonly name: string;
  readonly offHost: boolean;
  readonly records: number;
  readonly readable: boolean;
  readonly error?: string;
}

/** /integrity 响应 - 对齐 GovernanceMetricsController.integrity() */
export interface IntegrityReport {
  readonly auditChainValid: boolean;
  readonly auditRecords: number;
  /** 仅当服务端使用 FileAuditStore 时存在 */
  readonly auditAnchorRecords?: number;
  readonly auditAnchorLag?: number;
  readonly auditAnchorBatch?: number;
  readonly auditAnchorReplicas?: readonly AnchorReplicaView[];
  readonly auditAnchorReplicaFailures?: number;
  readonly auditAnchorReplicaErrors?: string;
  readonly pendingApprovals: number;
  readonly findings: readonly string[];
  readonly compliant: boolean;
  readonly mode: string;
}

/** 审批单视图 - 对齐 ApprovalAdminController.view() */
export interface ApprovalView {
  readonly id: string;
  readonly taskId?: string;
  readonly tool: string;
  readonly riskLevel: string;
  readonly impactNote?: string;
  readonly rollbackPlan?: string;
  readonly requestedBy: string;
  readonly status: ApprovalStatus;
  readonly createdAt?: string;
  readonly deadline?: string;
  readonly approver?: string;
  readonly comment?: string;
  readonly decidedAt?: string;
}

/** 审批单列表 data 载荷 */
export interface ApprovalListData {
  readonly total: number;
  readonly items: readonly ApprovalView[];
}

/** 管理接口统一响应信封 - 对齐 starter.admin.ApiResponse record */
export interface ApiEnvelope<T> {
  readonly code: number;
  readonly message: string;
  readonly data: T | null;
  readonly error: string | null;
}

/** REST 调用错误（HTTP 层或业务信封层失败均抛出该错误） */
export class GovernanceApiError extends Error {
  /** HTTP 状态码 */
  readonly status: number;
  /** 机器可读错误码（信封 error 字段；HTTP 层失败时为 HTTP_<status>） */
  readonly errorCode: string;

  constructor(status: number, errorCode: string, message: string) {
    super(message);
    this.name = "GovernanceApiError";
    this.status = status;
    this.errorCode = errorCode;
  }
}

/** 最小化的 fetch 抽象（便于注入 mock） */
export type FetchLike = (
  url: string,
  init: { method: string; headers: Record<string, string>; body?: string },
) => Promise<{
  status: number;
  statusText?: string;
  json(): Promise<unknown>;
}>;

/** REST 客户端配置 */
export interface RestErrorConfig {
  /** 服务端基地址，如 http://localhost:8080（末尾斜杠可有可无） */
  baseUrl: string;
  /** X-Governance-Token 令牌；未配置时服务端将返回 403/ADMIN_API_DISABLED */
  token?: string;
  /** 注入 fetch 实现（测试用）；默认取 Node 18+ 全局 fetch */
  fetchImpl?: FetchLike;
}

const OBSERVABILITY_PATH = "/towndemontower";
const APPROVALS_PATH = "/towndemontower/admin/approvals";

/**
 * 治理 REST 客户端 - 对齐 spring-starter 观测与管理端点。
 */
export class GovernanceRestClient {
  private _baseUrl: string;
  private _token?: string;
  private _fetch: FetchLike;

  constructor(config: RestErrorConfig) {
    if (!config || !config.baseUrl) {
      throw new Error("GovernanceRestClient: baseUrl is required");
    }
    this._baseUrl = config.baseUrl.replace(/\/+$/, "");
    this._token = config.token;
    this._fetch = config.fetchImpl ?? GovernanceRestClient._globalFetch();
  }

  private static _globalFetch(): FetchLike {
    const g = globalThis as { fetch?: unknown };
    if (typeof g.fetch !== "function") {
      throw new Error("global fetch unavailable: upgrade Node >= 18 or inject fetchImpl");
    }
    return g.fetch as FetchLike;
  }

  // 观测端点（裸 JSON，无信封）

  /** GET /towndemontower/metrics */
  async metrics(): Promise<MetricsReport> {
    const json = await this._send("GET", OBSERVABILITY_PATH + "/metrics", undefined, false);
    return json as MetricsReport;
  }

  /** GET /towndemontower/integrity */
  async integrity(): Promise<IntegrityReport> {
    const json = await this._send("GET", OBSERVABILITY_PATH + "/integrity", undefined, false);
    return json as IntegrityReport;
  }

  // 审批管理端点（ApiResponse 信封）

  /** GET /admin/approvals，审批单列表，可按状态过滤 */
  async listApprovals(status?: ApprovalStatus): Promise<ApprovalListData> {
    const qs = status ? "?status=" + encodeURIComponent(status) : "";
    const data = await this._envelope<ApprovalListData>("GET", APPROVALS_PATH + qs);
    return data ?? { total: 0, items: [] };
  }

  /** GET /admin/approvals/{id}，查询单个审批单 */
  async getApproval(id: string): Promise<ApprovalView> {
    const data = await this._envelope<ApprovalView>("GET", APPROVALS_PATH + "/" + encodeURIComponent(id));
    return data as ApprovalView;
  }

  /** POST /admin/approvals/{id}/approve，批准（服务端禁自批、非 PENDING 返回 409） */
  async approve(id: string, approver: string, comment?: string): Promise<ApprovalView> {
    return this._decision("approve", id, approver, comment);
  }

  /** POST /admin/approvals/{id}/deny，驳回（服务端禁自驳、非 PENDING 返回 409） */
  async deny(id: string, approver: string, comment?: string): Promise<ApprovalView> {
    return this._decision("deny", id, approver, comment);
  }

  private async _decision(
    action: "approve" | "deny",
    id: string,
    approver: string,
    comment?: string,
  ): Promise<ApprovalView> {
    if (!approver) {
      throw new Error("approver is required for " + action);
    }
    const body: Record<string, unknown> = { approver };
    if (comment !== undefined) body["comment"] = comment;
    const data = await this._envelope<ApprovalView>(
      "POST",
      APPROVALS_PATH + "/" + encodeURIComponent(id) + "/" + action,
      body,
    );
    return data as ApprovalView;
  }

  // 传输层

  /** 解 ApiResponse 信封；code!==0 抛 GovernanceApiError */
  private async _envelope<T>(method: string, path: string, body?: Record<string, unknown>): Promise<T | null> {
    const json = (await this._send(method, path, body, true)) as ApiEnvelope<T>;
    if (json.code !== 0) {
      throw new GovernanceApiError(
        json.code,
        json.error ?? "UNKNOWN",
        json.message ?? "governance API failure (code=" + json.code + ")",
      );
    }
    return json.data;
  }

  private async _send(
    method: string,
    path: string,
    body: Record<string, unknown> | undefined,
    isEnvelope: boolean,
  ): Promise<unknown> {
    const url = this._baseUrl + path;
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (this._token) headers["X-Governance-Token"] = this._token;

    let res: Awaited<ReturnType<FetchLike>>;
    try {
      res = await this._fetch(url, { method, headers, ...(body ? { body: JSON.stringify(body) } : {}) });
    } catch (err) {
      throw new GovernanceApiError(0, "NETWORK_ERROR", "request failed: " + (err instanceof Error ? err.message : String(err)));
    }

    if (res.status < 200 || res.status >= 300) {
      // 管理端点的错误也可能带信封体（AdminApiException -> ApiResponse.fail）
      if (isEnvelope) {
        try {
          const env = (await res.json()) as Partial<ApiEnvelope<unknown>>;
          if (env && typeof env.code === "number" && env.code !== 0) {
            throw new GovernanceApiError(
              res.status,
              env.error ?? ("HTTP_" + res.status),
              env.message ?? ("HTTP " + res.status + " " + (res.statusText ?? "")).trim(),
            );
          }
        } catch (e) {
          if (e instanceof GovernanceApiError) throw e;
          // 非 JSON 响应体，退化为 HTTP 错误
        }
      }
      throw new GovernanceApiError(res.status, "HTTP_" + res.status, ("HTTP " + res.status + " " + (res.statusText ?? "")).trim());
    }

    return res.json();
  }
}