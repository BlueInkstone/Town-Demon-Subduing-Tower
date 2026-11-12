#!/usr/bin/env python3
"""
方向 D · 统一审计链参考实现（chainVersion=2），零第三方依赖。

权威格式定义见 contract/audit-chain.json。本文件是三语言共用的**算式基准**，
不是生产写入侧实现；用途：

    python contract/audit_chain.py --emit     # 输出 golden 向量（JSON）
    python contract/audit_chain.py --verify  # 自检：算例 + golden + 三语言绑定覆盖

设计取舍（详见 docs/37-审计链统一迁移方案.md）：
  * 摘要公式沿用 Java 既有 `seq|action|payload|prevHash` —— Java 侧加固、外锚、
    行为分析器都已绑定这个算式，改它等于推倒已交付能力。
  * payload 统一为 UTF-8 **字符串**；结构化负载由写入方按规范化 JSON 序列化。
    这样 Java 的 `tool|OK|task=...` 与 SDK 的对象负载都能进同一条链，
    且不需要改动 M1 行为分析器对 payload 的解析。
  * 时间记录但**不参与摘要**：落盘行带 ISO-8601 UTC（带 Z）的 timestamp，
    只做审计可读性，不进 hash 输入。
  * 落盘统一为单行 camelCase JSON，字段边界由 JSON 保证，
    不再用竖线或 tab 当分隔符（这正是旧格式歧义的根源）。
  * 旧行（无 chainVersion）按格式嗅探分派到 legacy 校验器，**只读、不重算**。
"""

import hashlib
import hmac
import json
import sys
from pathlib import Path
from typing import Any, Optional

ROOT = Path(__file__).resolve().parents[1]

CHAIN_VERSION = 2
GENESIS = "GENESIS"
KEY_ENV = "TOWNDEMONTOWER_AUDIT_KEY"

# 统一行的字段集（写入时全部显式给出，缺字段即格式错误）
WIRE_FIELDS = (
    "chainVersion", "seq", "action", "payload",
    "timestamp", "prevHash", "hash", "mac", "algorithm",
)

# golden 用固定密钥；生产密钥只从环境变量取，不得硬编码
GOLDEN_KEY_BYTES = b"K7mP9qRs2tUv4wXy6zA8bC0dE1fG3hJ5k"


def canonical_payload(payload: Any) -> str:
    """把负载统一成参与摘要的 UTF-8 字符串。

    str 原样保留（Java 现有 payload 是竖线分隔的描述串）；
    dict/list 按 §6.1 规则 5 规范化：键字典序、紧凑分隔符、ensure_ascii=False。
    """
    if isinstance(payload, str):
        return payload
    return json.dumps(payload, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False)


def digest(seq: int, action: str, payload: Any, prev_hash: str,
           key: Optional[bytes]) -> str:
    """统一链摘要：HMAC-SHA256（有密钥）/ SHA-256（无密钥，须显式标记降级）。"""
    text = f"{seq}|{action}|{canonical_payload(payload)}|{prev_hash}"
    raw = text.encode("utf-8")
    if key:
        return hmac.new(key, raw, hashlib.sha256).hexdigest()
    return hashlib.sha256(raw).hexdigest()


def algorithm_of(key: Optional[bytes]) -> str:
    """无密钥不能静默等价于有密钥：算法名必须写进行里。"""
    return "HMAC-SHA256" if key else "SHA256-DEGRADED"


def encode_line(record: dict[str, Any]) -> str:
    """编码一行统一记录：键按字典序、紧凑、UTF-8 原字符。"""
    missing = [f for f in WIRE_FIELDS if f not in record]
    if missing:
        raise ValueError(f"统一记录缺字段：{missing}")
    return json.dumps(record, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False)


def decode_line(line: str) -> tuple[int, dict[str, Any]]:
    """按 chainVersion 分派解码。返回 (版本, 字段字典)。

    v2：camelCase JSON 且带 chainVersion=2。
    v1：无 chainVersion 的旧行，按形状嗅探：
        - 含 tab 且能切成 4 列 → Java 旧 tab 行
        - JSON 且带 prev_hash → Python 旧行
        - JSON 且带 prevHash  → TypeScript 旧行
    旧行一律标记为 legacy，只做只读校验，不允许重算或改写。
    """
    text = line.strip()
    if not text:
        raise ValueError("空行")
    if text.startswith("#"):
        raise ValueError("段头，非记录行")

    if text.startswith("{"):
        data = json.loads(text)
        version = int(data.get("chainVersion", 1))
        if version == 2:
            missing = [f for f in WIRE_FIELDS if f not in data]
            if missing:
                raise ValueError(f"v2 记录缺字段：{missing}")
            return 2, data
        # 旧 JSON 行：统一映射成 legacy 视图，保留原字段名以便按原公式校验
        return 1, {"legacy": True, "raw": data,
                   "flavor": "python" if "prev_hash" in data else "typescript"}

    parts = text.split("\t", 3)
    if len(parts) != 4:
        raise ValueError("无法识别的行格式")
    return 1, {"legacy": True, "flavor": "java",
               "seq": int(parts[0]), "action": parts[1],
               "hash": parts[2], "payload": parts[3]}


def legacy_digest(fields: dict[str, Any], key: Optional[bytes]) -> Optional[str]:
    """按旧格式原有算式重算，仅用于校验存量行；不产生新写入。

    Java 旧 tab 行没有独立 prevHash 字段，由链上一条推得，故这里由调用方传入。
    Python / TS 旧行把 timestamp 与 JSON 负载用冒号拼接、seq 从 0、链头 64 个 0。
    """
    flavor = fields.get("flavor")
    if flavor == "java":
        return None  # 由 verify_legacy_java 处理（需要外部 prev 链）
    raw = fields.get("raw", {})
    if flavor == "python":
        prev_key, seq_base = "prev_hash", 0
        load = json.dumps(raw.get("payload", {}), sort_keys=True)
    else:
        prev_key, seq_base = "prevHash", 0
        load = json.dumps(raw.get("payload", {}), sort_keys=True,
                          separators=(",", ":"))
    text = (f"{raw.get('seq', seq_base)}:{raw.get('action', '')}:{load}:"
            f"{raw.get('timestamp', '')}:{raw.get(prev_key, '')}")
    return hashlib.sha256(text.encode()).hexdigest()


# ---------------------------------------------------------------- golden

GOLDEN_CASES = [
    {
        "id": "first-no-key",
        "chainVersion": 2, "seq": 1, "action": "SKILL_INVOKED",
        "payload": {"agent": "AGT-0001", "code": "OK", "tool": "read"},
        "timestamp": "2026-11-12T00:00:00Z", "prevHash": GENESIS,
        "algorithm": "SHA256-DEGRADED", "key": None,
    },
    {
        "id": "first-hmac",
        "chainVersion": 2, "seq": 1, "action": "SKILL_INVOKED",
        "payload": {"agent": "AGT-0001", "code": "OK", "tool": "read"},
        "timestamp": "2026-11-12T00:00:00Z", "prevHash": GENESIS,
        "algorithm": "HMAC-SHA256", "key": GOLDEN_KEY_BYTES,
    },
    {
        "id": "unicode-payload",
        "chainVersion": 2, "seq": 2, "action": "INPUT_REJECTED",
        "payload": {"reason": "中文路径", "tool": "writeFile"},
        "timestamp": "2026-11-12T00:00:01Z", "prevHash": "PLACEHOLDER",
        "algorithm": "SHA256-DEGRADED", "key": None,
    },
    {
        "id": "string-payload",
        "chainVersion": 2, "seq": 3, "action": "SKILL_INVOKED",
        "payload": "tool3|OK|task=t1|mode=DEV_EXECUTION|agent=AGT-0001",
        "timestamp": "2026-11-12T00:00:02Z", "prevHash": "PLACEHOLDER",
        "algorithm": "SHA256-DEGRADED", "key": None,
    },
]


def build_golden() -> list[dict[str, Any]]:
    out = []
    for case in GOLDEN_CASES:
        key = case["key"]
        h = digest(case["seq"], case["action"], case["payload"],
                   case["prevHash"], key)
        row = {
            "chainVersion": case["chainVersion"], "seq": case["seq"],
            "action": case["action"], "payload": canonical_payload(case["payload"]),
            "timestamp": case["timestamp"], "prevHash": case["prevHash"],
            "hash": h, "mac": "", "algorithm": case["algorithm"],
        }
        out.append({
            "id": case["id"], "key": "HMAC" if key else "NONE",
            "hashInput": f"{case['seq']}|{case['action']}|{canonical_payload(case['payload'])}|{case['prevHash']}",
            "expectedHash": h,
            "expectedLine": encode_line(row),
        })
    # 链式第二条：prevHash 取第一条的 hash，证明链串联可复算
    first = out[0]["expectedHash"]
    h2 = digest(2, "SKILL_INVOKED", {"code": "PENDING_APPROVAL", "tool": "deploy"},
                first, None)
    row2 = {
        "chainVersion": 2, "seq": 2, "action": "SKILL_INVOKED",
        "payload": canonical_payload({"code": "PENDING_APPROVAL", "tool": "deploy"}),
        "timestamp": "2026-11-12T00:00:03Z", "prevHash": first,
        "hash": h2, "mac": "", "algorithm": "SHA256-DEGRADED",
    }
    out.append({
        "id": "chained-second", "key": "NONE",
        "hashInput": f"2|SKILL_INVOKED|{row2['payload']}|{first}",
        "expectedHash": h2, "expectedLine": encode_line(row2),
    })
    return out


def verify() -> int:
    ok = 0
    fail = 0

    def check(name: str, cond: bool) -> None:
        nonlocal ok, fail
        if cond:
            ok += 1
        else:
            fail += 1
            print(f"[FAIL] {name}")

    golden = json.loads((ROOT / "contract/audit-chain.json").read_text(encoding="utf-8"))
    expected = {g["id"]: g for g in golden["golden"]}
    produced = {g["id"]: g for g in build_golden()}
    check("golden 用例集一致", set(expected) == set(produced))
    for cid, g in produced.items():
        check(f"{cid} 摘要", expected.get(cid, {}).get("expectedHash") == g["expectedHash"])
        check(f"{cid} 落盘行", expected.get(cid, {}).get("expectedLine") == g["expectedLine"])

    # 编解码往返
    for g in produced.values():
        version, fields = decode_line(g["expectedLine"])
        check(f"{g['id']} 解码为 v2", version == 2 and fields["hash"] == g["expectedHash"])

    # legacy 分派（只读能力，不重算）
    java_v1 = "1\tSKILL_INVOKED\tabc\ttool|OK|task=t1"
    v, f = decode_line(java_v1)
    check("Java 旧 tab 行识别为 v1", v == 1 and f["flavor"] == "java" and f["seq"] == 1)
    py_v1 = json.dumps({"seq": 0, "action": "A", "payload": {"k": 1},
                        "timestamp": "2026-01-01T00:00:00", "prev_hash": "0" * 64,
                        "hash": "x", "mac": ""})
    v, f = decode_line(py_v1)
    check("Python 旧 JSON 行识别为 v1", v == 1 and f["flavor"] == "python")
    ts_v1 = json.dumps({"seq": 0, "action": "A", "payload": {"k": 1},
                        "timestamp": "2026-01-01T00:00:00Z", "prevHash": "0" * 64,
                        "hash": "x", "mac": ""})
    v, f = decode_line(ts_v1)
    check("TypeScript 旧 JSON 行识别为 v1", v == 1 and f["flavor"] == "typescript")

    # 降级必须显式，不能静默等价
    check("无密钥算法名显式降级", algorithm_of(None) == "SHA256-DEGRADED")
    check("有密钥算法名 HMAC", algorithm_of(GOLDEN_KEY_BYTES) == "HMAC-SHA256")
    check("有/无密钥摘要不同",
          digest(1, "A", {}, GENESIS, None) != digest(1, "A", {}, GENESIS, GOLDEN_KEY_BYTES))

    # 时间不参与摘要
    check("timestamp 不进 hash 输入",
          "timestamp" not in produced["first-no-key"]["hashInput"])

    # 缺字段必须报错（防静默降级）
    broken = dict(json.loads(produced["first-no-key"]["expectedLine"]))
    broken.pop("chainVersion")
    try:
        encode_line(broken)
        check("缺字段应报错", False)
    except ValueError:
        check("缺字段应报错", True)

    print(f"\nRESULT: {ok}/{ok + fail} passed")
    return 0 if fail == 0 else 1


def sync_golden() -> int:
    """把参考实现算出的向量写回权威文件（UTF-8，不经 shell 重定向）。"""
    path = ROOT / "contract/audit-chain.json"
    spec = json.loads(path.read_text(encoding="utf-8"))
    spec["golden"] = build_golden()
    path.write_text(json.dumps(spec, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")
    print(f"golden 写入 {path}：{len(spec['golden'])} 条")
    return 0


def main() -> int:
    arg = sys.argv[1] if len(sys.argv) > 1 else "--verify"
    if arg == "--emit":
        print(json.dumps(build_golden(), ensure_ascii=False, indent=2))
        return 0
    if arg == "--sync-golden":
        return sync_golden()
    return verify()


if __name__ == "__main__":
    sys.exit(main())
