"""@inkarmor_guard —— 镇妖塔「墨铠」网关的 Python 薄客户端。

设计约束（docs/38）：零第三方依赖（只用标准库 urllib），总行数 ≤ 200，
只做三件事——打包 IntentObject JSON、POST 到网关 /api/evaluate、按响应阻断。
拒绝时抛 InkarmorDenied；网关不可达 / 超时 / 鉴权失败（401/403）按约定 fail-closed 抛 InkarmorUnavailable。

用法：
    from inkarmor_guard import inkarmor_guard

    @inkarmor_guard("deleteData", confidence=0.9, reason="清理过期表")
    def drop_table(table: str):
        ...  # 只有网关裁决为"允许"才执行
"""
from __future__ import annotations

import functools
import json
import os
import urllib.error
import urllib.request

DEFAULT_URL = os.environ.get("INKARMOR_GATEWAY_URL", "http://127.0.0.1:8080")
DEFAULT_TOKEN = os.environ.get("INKARMOR_API_TOKEN", "")
TIMEOUT = float(os.environ.get("INKARMOR_TIMEOUT", "3.0"))

_HEALTH_PATH = "/api/evaluate"


class InkarmorDenied(Exception):
    """网关裁决为拒绝 / 待审批：本次调用不应执行。"""


class InkarmorUnavailable(Exception):
    """网关不可达 / 超时 / 鉴权失败：按约定 fail-closed，不得静默放行。"""


def _build_intent(tool, args, confidence, reason, expected_effect, rollback_hint):
    return {
        "tool": tool,
        "args": args or {},
        "confidence": confidence,
        "reason": reason,
        "expectedEffect": expected_effect,
        "rollbackHint": rollback_hint,
    }


def evaluate(intent, url=DEFAULT_URL, token=DEFAULT_TOKEN, timeout=TIMEOUT, path=_HEALTH_PATH):
    """把 intent 发给网关并消费裁决。允许返回 data；否则抛异常。"""
    req = urllib.request.Request(
        url.rstrip("/") + path,
        data=json.dumps(intent).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Inkarmor-Token": token},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        # 401/403 等鉴权失败：客户端不得静默放行
        raise InkarmorUnavailable(f"gateway returned HTTP {exc.code}") from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise InkarmorUnavailable(f"gateway unreachable: {exc}") from exc

    data = payload.get("data") or {}
    if data.get("success") is True:
        return data
    code = data.get("code")
    if code == "PENDING_APPROVAL":
        raise InkarmorDenied(f"pending approval: {data.get('approvalTicketId')}")
    raise InkarmorDenied(f"denied: {code} ({data.get('message')})")


def inkarmor_guard(tool, *, confidence=1.0, reason="", expected_effect="", rollback_hint="",
                   url=DEFAULT_URL, token=DEFAULT_TOKEN, timeout=TIMEOUT):
    """装饰器：被装饰函数执行前先过网关裁决；允许才真正执行。"""
    def decorator(fn):
        @functools.wraps(fn)
        def wrapper(*args, **kwargs):
            intent = _build_intent(tool, kwargs, confidence, reason, expected_effect, rollback_hint)
            evaluate(intent, url=url, token=token, timeout=timeout)
            return fn(*args, **kwargs)
        return wrapper
    return decorator
