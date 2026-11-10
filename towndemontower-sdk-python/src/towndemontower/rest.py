"""
REST 客户端模块 - 对齐 spring-starter 治理端点

封装三类观测/管理接口（与 Java 端契约逐字段对齐）:
  - GET  /towndemontower/metrics                    治理指标（裸 JSON）
  - GET  /towndemontower/integrity                  完整性自检（裸 JSON）
  - GET/POST /towndemontower/admin/approvals[...]   审批单管理（ApiResponse 信封）

鉴权: 请求头 X-Governance-Token（与 AdminGuard.HEADER 一致）。
传输: 默认使用标准库 urllib（零第三方依赖）；可注入自定义 transport 以便测试。
"""

import json
import urllib.parse
import urllib.request
import urllib.error
from dataclasses import dataclass
from typing import Any, Callable, Dict, List, Optional


class ApprovalStatus:
    """审批单状态常量 - 对齐 core ApprovalStatus.java"""
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    DENIED = "DENIED"
    EXPIRED = "EXPIRED"


class HttpResponse:
    """
    传输层统一响应对象。

    暴露 status / status_text / json()，与注入的自定义 transport 返回对象同构，
    调用方只依赖这三项（鸭子类型），不强制使用本类。
    """

    def __init__(self, status: int, status_text: str, body: bytes):
        self.status = status
        self.status_text = status_text
        self._body = body

    def json(self) -> Any:
        return json.loads(self._body.decode("utf-8"))


# transport 协议: (method, url, headers, body_bytes|None) -> 具备 status/status_text/json() 的对象
Transport = Callable[[str, str, Dict[str, str], Optional[bytes]], Any]


def _urllib_transport(
    method: str,
    url: str,
    headers: Dict[str, str],
    body: Optional[bytes],
) -> HttpResponse:
    """默认传输：标准库 urllib。非 2xx 也返回 HttpResponse（携带响应体）以便解信封错误。"""
    req = urllib.request.Request(url, data=body, method=method)
    for key, value in headers.items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req) as resp:
            reason = getattr(resp, "reason", "") or ""
            return HttpResponse(resp.status, reason, resp.read())
    except urllib.error.HTTPError as e:
        reason = getattr(e, "reason", "") or ""
        return HttpResponse(e.code, reason, e.read() or b"")


@dataclass
class RestClientConfig:
    """
    REST 客户端配置 - 对齐 TypeScript RestErrorConfig。

    Attributes:
        base_url: 服务端基地址，如 http://localhost:8080（末尾斜杠可有可无）
        token: X-Governance-Token 令牌；未配置时服务端返回 401/403
        transport: 注入传输实现（测试用）；默认标准库 urllib
    """
    base_url: str
    token: Optional[str] = None
    transport: Optional[Transport] = None


@dataclass
class MetricsReport:
    """/metrics 响应 - 对齐 GovernanceMetricsController.metrics()"""
    counters: Dict[str, int]
    denial_rate: float
    false_rejection_rate: float
    over_conservatism_rate: float
    mis_execution_rate: float
    cache_hit_rate: float


@dataclass
class AnchorReplicaView:
    """锚点副本状态 - 对齐 core AnchorReplicaState.java"""
    name: str
    off_host: bool
    records: int
    readable: bool
    error: Optional[str] = None


@dataclass
class IntegrityReport:
    """/integrity 响应 - 对齐 GovernanceMetricsController.integrity()"""
    audit_chain_valid: bool
    audit_records: int
    pending_approvals: int
    findings: List[str]
    compliant: bool
    mode: str
    audit_anchor_records: Optional[int] = None
    audit_anchor_lag: Optional[int] = None
    audit_anchor_batch: Optional[int] = None
    audit_anchor_replicas: Optional[List[AnchorReplicaView]] = None
    audit_anchor_replica_failures: Optional[int] = None
    audit_anchor_replica_errors: Optional[str] = None


@dataclass
class ApprovalView:
    """审批单视图 - 对齐 ApprovalAdminController.view()"""
    id: str
    tool: str
    risk_level: str
    requested_by: str
    status: str
    task_id: Optional[str] = None
    impact_note: Optional[str] = None
    rollback_plan: Optional[str] = None
    created_at: Optional[str] = None
    deadline: Optional[str] = None
    approver: Optional[str] = None
    comment: Optional[str] = None
    decided_at: Optional[str] = None


@dataclass
class ApprovalListData:
    """审批单列表 data 载荷"""
    total: int
    items: List[ApprovalView]


@dataclass
class ApiEnvelope:
    """管理接口统一响应信封 - 对齐 starter.admin.ApiResponse"""
    code: int
    message: str
    data: Any
    error: Optional[str]


class GovernanceApiError(Exception):
    """REST 调用错误（HTTP 层或业务信封层失败均抛出该错误）"""

    def __init__(self, status: int, error_code: str, message: str):
        super().__init__(message)
        self.status = status
        self.error_code = error_code

    def __repr__(self) -> str:
        return "GovernanceApiError(status=%r, error_code=%r, message=%r)" % (
            self.status, self.error_code, str(self))


_OBSERVABILITY_PATH = "/towndemontower"
_APPROVALS_PATH = "/towndemontower/admin/approvals"


def _to_metrics_report(raw: Dict[str, Any]) -> MetricsReport:
    return MetricsReport(
        counters=dict(raw.get("counters", {}) or {}),
        denial_rate=raw.get("denialRate", 0.0),
        false_rejection_rate=raw.get("falseRejectionRate", 0.0),
        over_conservatism_rate=raw.get("overConservatismRate", 0.0),
        mis_execution_rate=raw.get("misExecutionRate", 0.0),
        cache_hit_rate=raw.get("cacheHitRate", 0.0),
    )


def _to_integrity_report(raw: Dict[str, Any]) -> IntegrityReport:
    replicas: Optional[List[AnchorReplicaView]] = None
    raw_replicas = raw.get("auditAnchorReplicas")
    if raw_replicas is not None:
        replicas = [
            AnchorReplicaView(
                name=r.get("name", ""),
                off_host=r.get("offHost", False),
                records=r.get("records", 0),
                readable=r.get("readable", False),
                error=r.get("error"),
            )
            for r in raw_replicas
        ]
    return IntegrityReport(
        audit_chain_valid=raw.get("auditChainValid", False),
        audit_records=raw.get("auditRecords", 0),
        pending_approvals=raw.get("pendingApprovals", 0),
        findings=list(raw.get("findings", []) or []),
        compliant=raw.get("compliant", False),
        mode=raw.get("mode", ""),
        audit_anchor_records=raw.get("auditAnchorRecords"),
        audit_anchor_lag=raw.get("auditAnchorLag"),
        audit_anchor_batch=raw.get("auditAnchorBatch"),
        audit_anchor_replicas=replicas,
        audit_anchor_replica_failures=raw.get("auditAnchorReplicaFailures"),
        audit_anchor_replica_errors=raw.get("auditAnchorReplicaErrors"),
    )


def _to_approval_view(raw: Optional[Dict[str, Any]]) -> ApprovalView:
    if raw is None:
        raise GovernanceApiError(0, "EMPTY_DATA", "审批数据为空")
    return ApprovalView(
        id=raw.get("id", ""),
        tool=raw.get("tool", ""),
        risk_level=raw.get("riskLevel", ""),
        requested_by=raw.get("requestedBy", ""),
        status=raw.get("status", ""),
        task_id=raw.get("taskId"),
        impact_note=raw.get("impactNote"),
        rollback_plan=raw.get("rollbackPlan"),
        created_at=raw.get("createdAt"),
        deadline=raw.get("deadline"),
        approver=raw.get("approver"),
        comment=raw.get("comment"),
        decided_at=raw.get("decidedAt"),
    )


class GovernanceRestClient:
    """
    治理 REST 客户端 - 对齐 spring-starter 观测与管理端点。

    Usage:
        client = GovernanceRestClient(RestClientConfig(base_url="http://localhost:8080", token="xxx"))
        metrics = client.metrics()
        report = client.integrity()
        pending = client.list_approvals(ApprovalStatus.PENDING)
        client.approve("apv-1", approver="carol", comment="lgtm")
    """

    def __init__(
        self,
        config: Optional[RestClientConfig] = None,
        base_url: Optional[str] = None,
        token: Optional[str] = None,
        transport: Optional[Transport] = None,
    ):
        if config is None:
            if not base_url:
                raise ValueError("base_url is required")
            config = RestClientConfig(base_url=base_url, token=token, transport=transport)
        if not config.base_url:
            raise ValueError("base_url is required")
        self._base_url = config.base_url.rstrip("/")
        self._token = config.token
        self._transport = config.transport or _urllib_transport

    # 观测端点（裸 JSON，无信封）

    def metrics(self) -> MetricsReport:
        """GET /towndemontower/metrics"""
        raw = self._send("GET", _OBSERVABILITY_PATH + "/metrics", None, False)
        return _to_metrics_report(raw)

    def integrity(self) -> IntegrityReport:
        """GET /towndemontower/integrity"""
        raw = self._send("GET", _OBSERVABILITY_PATH + "/integrity", None, False)
        return _to_integrity_report(raw)

    # 审批管理端点（ApiResponse 信封）

    def list_approvals(self, status: Optional[str] = None) -> ApprovalListData:
        """GET /admin/approvals，审批单列表，可按状态过滤"""
        qs = "?status=" + urllib.parse.quote(status) if status else ""
        data = self._envelope("GET", _APPROVALS_PATH + qs)
        if not data:
            return ApprovalListData(total=0, items=[])
        return ApprovalListData(
            total=data.get("total", 0),
            items=[_to_approval_view(x) for x in data.get("items", [])],
        )

    def get_approval(self, approval_id: str) -> ApprovalView:
        """GET /admin/approvals/{id}，查询单个审批单"""
        data = self._envelope("GET", _APPROVALS_PATH + "/" + urllib.parse.quote(approval_id))
        return _to_approval_view(data)

    def approve(self, approval_id: str, approver: str, comment: Optional[str] = None) -> ApprovalView:
        """POST /admin/approvals/{id}/approve，批准（服务端禁自批、非 PENDING 返回 409）"""
        return self._decision("approve", approval_id, approver, comment)

    def deny(self, approval_id: str, approver: str, comment: Optional[str] = None) -> ApprovalView:
        """POST /admin/approvals/{id}/deny，驳回（服务端禁自驳、非 PENDING 返回 409）"""
        return self._decision("deny", approval_id, approver, comment)

    def _decision(
        self,
        action: str,
        approval_id: str,
        approver: str,
        comment: Optional[str],
    ) -> ApprovalView:
        if not approver:
            raise ValueError("approver is required for " + action)
        body: Dict[str, Any] = {"approver": approver}
        if comment is not None:
            body["comment"] = comment
        data = self._envelope(
            "POST",
            _APPROVALS_PATH + "/" + urllib.parse.quote(approval_id) + "/" + action,
            body,
        )
        return _to_approval_view(data)

    # 传输层

    def _envelope(self, method: str, path: str, body: Optional[Dict[str, Any]] = None) -> Any:
        """解 ApiResponse 信封；code != 0 抛 GovernanceApiError"""
        raw = self._send(method, path, body, True)
        env = ApiEnvelope(
            code=raw.get("code", -1),
            message=raw.get("message", ""),
            data=raw.get("data"),
            error=raw.get("error"),
        )
        if env.code != 0:
            raise GovernanceApiError(
                env.code,
                env.error or "UNKNOWN",
                env.message or ("governance API failure (code=%s)" % env.code),
            )
        return env.data

    def _send(
        self,
        method: str,
        path: str,
        body: Optional[Dict[str, Any]],
        is_envelope: bool,
    ) -> Dict[str, Any]:
        url = self._base_url + path
        headers: Dict[str, str] = {"Content-Type": "application/json"}
        if self._token:
            headers["X-Governance-Token"] = self._token
        body_bytes = json.dumps(body).encode("utf-8") if body is not None else None

        try:
            resp = self._transport(method, url, headers, body_bytes)
        except GovernanceApiError:
            raise
        except Exception as err:
            raise GovernanceApiError(0, "NETWORK_ERROR", "request failed: " + str(err))

        if resp.status < 200 or resp.status >= 300:
            # 管理端点的错误也可能带信封体（AdminApiException -> ApiResponse.fail）
            if is_envelope:
                try:
                    env = resp.json()
                    if isinstance(env, dict) and isinstance(env.get("code"), int) and env.get("code") != 0:
                        raise GovernanceApiError(
                            resp.status,
                            env.get("error") or ("HTTP_%s" % resp.status),
                            env.get("message") or ("HTTP %s" % resp.status),
                        )
                except GovernanceApiError:
                    raise
                except Exception:
                    # 非 JSON 响应体，退化为 HTTP 错误
                    pass
            status_text = getattr(resp, "status_text", "") or ""
            raise GovernanceApiError(
                resp.status,
                "HTTP_%s" % resp.status,
                ("HTTP %s %s" % (resp.status, status_text)).strip(),
            )

        return resp.json()
