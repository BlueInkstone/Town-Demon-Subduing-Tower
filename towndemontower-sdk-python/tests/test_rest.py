"""
测试 REST 客户端模块（GovernanceRestClient）

注入假 transport（不发真实网络请求），验证:
  - 端点路径与查询串构造、鉴权头注入
  - 裸 JSON 与 ApiResponse 信封解析、camelCase -> snake_case 映射
  - GovernanceApiError 错误分支（业务码 / HTTP / 网络）
"""

import unittest
import sys
import os
import json

# 添加 src 到路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from towndemontower.rest import (
    GovernanceRestClient,
    RestClientConfig,
    GovernanceApiError,
    ApprovalStatus,
    HttpResponse,
)


def _resp(status, payload, status_text="OK"):
    """构造一个 HttpResponse（body 为 JSON 字节；payload 为 bytes 时透传，便于构造非 JSON 体）。"""
    body = payload if isinstance(payload, (bytes, bytearray)) else json.dumps(payload).encode("utf-8")
    return HttpResponse(status, status_text, bytes(body))


def _envelope(data):
    """成功信封。"""
    return {"code": 0, "message": "ok", "data": data, "error": None}


class _Recorder:
    """记录 transport 调用并返回预置响应序列。"""

    def __init__(self, responses):
        self._responses = list(responses)
        self.calls = []

    def __call__(self, method, url, headers, body):
        self.calls.append({"method": method, "url": url, "headers": dict(headers), "body": body})
        if not self._responses:
            raise AssertionError("没有更多预置响应")
        item = self._responses.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


class TestGovernanceRestClient(unittest.TestCase):

    def _client(self, responses, token="tok"):
        rec = _Recorder(responses)
        client = GovernanceRestClient(RestClientConfig(
            base_url="http://localhost:8080/", token=token, transport=rec,
        ))
        return client, rec

    # 观测端点

    def test_metrics_parses_counters_and_rates(self):
        payload = {
            "counters": {"total_invocations": 128, "r0_pass": 90, "r3_rejected": 9},
            "denialRate": 0.07,
            "falseRejectionRate": 0.01,
            "overConservatismRate": 0.02,
            "misExecutionRate": 0.0,
            "cacheHitRate": 0.45,
        }
        client, rec = self._client([_resp(200, payload)])
        report = client.metrics()

        self.assertEqual(report.counters["total_invocations"], 128)
        self.assertEqual(report.denial_rate, 0.07)
        self.assertEqual(report.cache_hit_rate, 0.45)
        # URL 与鉴权头、末尾斜杠归一
        call = rec.calls[0]
        self.assertEqual(call["method"], "GET")
        self.assertEqual(call["url"], "http://localhost:8080/towndemontower/metrics")
        self.assertEqual(call["headers"]["X-Governance-Token"], "tok")
        self.assertIsNone(call["body"])

    def test_integrity_basic_without_anchor(self):
        payload = {
            "auditChainValid": True,
            "auditRecords": 512,
            "pendingApprovals": 6,
            "findings": [],
            "compliant": True,
            "mode": "STRICT",
        }
        client, rec = self._client([_resp(200, payload)])
        report = client.integrity()

        self.assertTrue(report.audit_chain_valid)
        self.assertEqual(report.audit_records, 512)
        self.assertEqual(report.pending_approvals, 6)
        self.assertTrue(report.compliant)
        self.assertEqual(report.mode, "STRICT")
        # 非 FileAuditStore 时锚点字段缺省为 None
        self.assertIsNone(report.audit_anchor_records)
        self.assertIsNone(report.audit_anchor_replicas)

    def test_integrity_with_anchor_replicas(self):
        payload = {
            "auditChainValid": True,
            "auditRecords": 10,
            "pendingApprovals": 0,
            "findings": ["AUDIT_VOLATILE"],
            "compliant": False,
            "mode": "NORMAL",
            "auditAnchorRecords": 8,
            "auditAnchorLag": 2,
            "auditAnchorBatch": 4,
            "auditAnchorReplicas": [
                {"name": "s3", "offHost": True, "records": 8, "readable": True},
                {"name": "peer", "offHost": False, "records": 0, "readable": False, "error": "unreachable"},
            ],
            "auditAnchorReplicaFailures": 1,
            "auditAnchorReplicaErrors": "peer:unreachable",
        }
        client, _ = self._client([_resp(200, payload)])
        report = client.integrity()

        self.assertEqual(report.audit_anchor_lag, 2)
        self.assertEqual(len(report.audit_anchor_replicas), 2)
        self.assertTrue(report.audit_anchor_replicas[0].off_host)
        self.assertEqual(report.audit_anchor_replicas[1].error, "unreachable")
        self.assertFalse(report.compliant)
        self.assertEqual(report.findings, ["AUDIT_VOLATILE"])

    # 审批管理端点

    def test_list_approvals_with_status_filter(self):
        data = {"total": 2, "items": [
            {"id": "a1", "tool": "db.drop", "riskLevel": "R3", "requestedBy": "alice", "status": "PENDING"},
            {"id": "a2", "tool": "deploy", "riskLevel": "R3", "requestedBy": "bob", "status": "PENDING"},
        ]}
        client, rec = self._client([_resp(200, _envelope(data))])
        result = client.list_approvals(ApprovalStatus.PENDING)

        self.assertEqual(result.total, 2)
        self.assertEqual(result.items[0].id, "a1")
        self.assertEqual(rec.calls[0]["url"], "http://localhost:8080/towndemontower/admin/approvals?status=PENDING")

    def test_list_approvals_without_status(self):
        data = {"total": 0, "items": []}
        client, rec = self._client([_resp(200, _envelope(data))])
        result = client.list_approvals()

        self.assertEqual(result.total, 0)
        self.assertEqual(rec.calls[0]["url"], "http://localhost:8080/towndemontower/admin/approvals")

    def test_list_approvals_null_data_defaults(self):
        client, _ = self._client([_resp(200, {"code": 0, "message": "ok", "data": None, "error": None})])
        result = client.list_approvals()
        self.assertEqual(result.total, 0)
        self.assertEqual(result.items, [])

    def test_get_approval_parses_view(self):
        data = {"id": "x1", "taskId": "t1", "tool": "rm", "riskLevel": "R2",
                "requestedBy": "alice", "status": "PENDING", "createdAt": "2026-01-01T00:00:00Z"}
        client, rec = self._client([_resp(200, _envelope(data))])
        view = client.get_approval("x1")

        self.assertEqual(view.id, "x1")
        self.assertEqual(view.task_id, "t1")
        self.assertEqual(view.risk_level, "R2")
        self.assertEqual(view.created_at, "2026-01-01T00:00:00Z")
        self.assertEqual(rec.calls[0]["url"], "http://localhost:8080/towndemontower/admin/approvals/x1")

    def test_approve_posts_body_and_returns_approved(self):
        result_data = {"id": "x2", "tool": "rm", "riskLevel": "R3", "requestedBy": "alice",
                       "status": "APPROVED", "approver": "carol", "comment": "lgtm"}
        client, rec = self._client([_resp(200, _envelope(result_data))])
        view = client.approve("x2", "carol", "lgtm")

        self.assertEqual(view.status, "APPROVED")
        self.assertEqual(view.approver, "carol")
        call = rec.calls[0]
        self.assertEqual(call["method"], "POST")
        self.assertEqual(call["url"], "http://localhost:8080/towndemontower/admin/approvals/x2/approve")
        sent = json.loads(call["body"].decode("utf-8"))
        self.assertEqual(sent, {"approver": "carol", "comment": "lgtm"})

    def test_deny_returns_denied(self):
        result_data = {"id": "x3", "tool": "rm", "riskLevel": "R3", "requestedBy": "alice",
                       "status": "DENIED", "approver": "dave"}
        client, rec = self._client([_resp(200, _envelope(result_data))])
        view = client.deny("x3", "dave")

        self.assertEqual(view.status, "DENIED")
        sent = json.loads(rec.calls[0]["body"].decode("utf-8"))
        self.assertEqual(sent, {"approver": "dave"})

    def test_approve_requires_approver(self):
        client, _ = self._client([])
        with self.assertRaises(ValueError):
            client.approve("x", "")

    # 错误处理

    def test_envelope_business_error_raises(self):
        client, _ = self._client([_resp(200, {"code": 400, "message": "approver 不能为空", "data": None, "error": "VALIDATION_FAILED"})])
        with self.assertRaises(GovernanceApiError) as ctx:
            client.get_approval("x")
        self.assertEqual(ctx.exception.error_code, "VALIDATION_FAILED")
        self.assertEqual(ctx.exception.status, 400)

    def test_non_2xx_envelope_error(self):
        client, _ = self._client([_resp(404, {"code": 404, "message": "审批单不存在", "data": None, "error": "NOT_FOUND"}, status_text="Not Found")])
        with self.assertRaises(GovernanceApiError) as ctx:
            client.get_approval("missing")
        self.assertEqual(ctx.exception.status, 404)
        self.assertEqual(ctx.exception.error_code, "NOT_FOUND")

    def test_non_2xx_raw_http_error(self):
        client, _ = self._client([_resp(401, b"", status_text="Unauthorized")])
        with self.assertRaises(GovernanceApiError) as ctx:
            client.metrics()
        self.assertEqual(ctx.exception.status, 401)
        self.assertEqual(ctx.exception.error_code, "HTTP_401")

    def test_transport_exception_becomes_network_error(self):
        client, _ = self._client([ConnectionError("refused")])
        with self.assertRaises(GovernanceApiError) as ctx:
            client.metrics()
        self.assertEqual(ctx.exception.error_code, "NETWORK_ERROR")

    def test_base_url_required(self):
        with self.assertRaises(ValueError):
            GovernanceRestClient(RestClientConfig(base_url=""))


if __name__ == "__main__":
    unittest.main()
