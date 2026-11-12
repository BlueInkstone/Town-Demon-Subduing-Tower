"""GW-03：Python 薄包 fail-closed 行为测试（标准库 unittest，无第三方依赖）。

覆盖：允许放行、拒绝抛 InkarmorDenied、待审批抛 InkarmorDenied、
网关不可达抛 InkarmorUnavailable、鉴权失败（401/403）抛 InkarmorUnavailable、
装饰器在拒绝时阻断原函数执行。
"""
import io
import json
import unittest
from unittest import mock

import inkarmor_guard
from inkarmor_guard import InkarmorDenied, InkarmorUnavailable, evaluate, inkarmor_guard


def _resp(payload_bytes):
    resp = mock.Mock()
    resp.read.return_value = payload_bytes
    resp.__enter__ = mock.Mock(return_value=resp)
    resp.__exit__ = mock.Mock(return_value=False)
    return resp


def _envelope(data):
    return {"code": 0, "message": "ok", "data": data, "error": None}


class TestFailClosed(unittest.TestCase):

    def test_allow(self):
        data = {"success": True, "code": "OK", "message": None, "output": None,
                "approvalTicketId": None}
        with mock.patch("urllib.request.urlopen",
                        return_value=_resp(json.dumps(_envelope(data)).encode())):
            self.assertEqual(evaluate({"tool": "runCompile"}), data)

    def test_denied(self):
        data = {"success": False, "code": "R3_DENIED", "message": "x", "output": None,
                "approvalTicketId": None}
        with mock.patch("urllib.request.urlopen",
                        return_value=_resp(json.dumps(_envelope(data)).encode())):
            with self.assertRaises(InkarmorDenied):
                evaluate({"tool": "deleteData"})

    def test_pending(self):
        data = {"success": False, "code": "PENDING_APPROVAL", "message": "x", "output": None,
                "approvalTicketId": "T1"}
        with mock.patch("urllib.request.urlopen",
                        return_value=_resp(json.dumps(_envelope(data)).encode())):
            with self.assertRaises(InkarmorDenied):
                evaluate({"tool": "deployService"})

    def test_unreachable(self):
        from urllib.error import URLError
        with mock.patch("urllib.request.urlopen",
                        side_effect=URLError("connection refused")):
            with self.assertRaises(InkarmorUnavailable):
                evaluate({"tool": "runCompile"})

    def test_auth_failure(self):
        from urllib.error import HTTPError
        err = HTTPError("url", 401, "no", {}, io.BytesIO(b""))
        with mock.patch("urllib.request.urlopen", side_effect=err):
            with self.assertRaises(InkarmorUnavailable):
                evaluate({"tool": "runCompile"})

    def test_decorator_blocks(self):
        data = {"success": False, "code": "R3_DENIED", "message": "x", "output": None,
                "approvalTicketId": None}

        @inkarmor_guard("deleteData")
        def do_thing():
            return "ran"

        with mock.patch("urllib.request.urlopen",
                        return_value=_resp(json.dumps(_envelope(data)).encode())):
            with self.assertRaises(InkarmorDenied):
                do_thing()


if __name__ == "__main__":
    unittest.main()
