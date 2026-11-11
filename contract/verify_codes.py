#!/usr/bin/env python3
"""
方向 D：跨语言错误码 / 统计口径一致性校验（零第三方依赖）。

校验 contract/error-codes.json 与三语言绑定是否同源，并验证统计口径一致：

    python contract/verify_codes.py

检查项：
  1. 权威 JSON 与 wire schema 均为已定稿状态且结构完整
  2. canonical 集合、别名映射、待评审码三者内部自洽
  3. 三语言绑定源码覆盖全部 canonical 与 alias（防止某语言漏映射）
  4. golden 输入经 Python 绑定归一后与期望一致
  5. deniedExcludes 与统一拒绝口径一致

退出码 0 = 全部通过，1 = 有不一致（可直接当 CI 门禁）。
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contract"

BINDINGS = {
    "Java": ROOT / "towndemontower-core/src/main/java/com/towndemontower/core/contract/ErrorCodeCatalog.java",
    "Python": ROOT / "towndemontower-sdk-python/src/towndemontower/codes.py",
    "TypeScript": ROOT / "towndemontower-sdk-typescript/src/codes.ts",
}

_passed = 0
_failed = 0


def check(name, ok, detail=""):
    global _passed, _failed
    if ok:
        _passed += 1
        print(f"[OK]   {name}")
    else:
        _failed += 1
        print(f"[FAIL] {name}" + (f"：{detail}" if detail else ""))


def load_json(path):
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def main():
    codes = load_json(CONTRACT / "error-codes.json")
    schema = load_json(CONTRACT / "towndemontower-wire.schema.json")

    # 1. 定稿状态与结构
    check("error-codes.json 状态为 ratified", codes.get("status") == "ratified")
    check("wire schema 状态为 ratified", schema.get("status") == "ratified")
    defs = schema.get("$defs", {})
    expected_defs = {
        "RiskLevel", "ApprovalStatus", "AgentMode", "ErrorCode",
        "IntentObject", "SkillDescriptor", "ExecutionContext", "SkillResult", "ApprovalTicket",
    }
    check("wire schema 覆盖 9 个定义", expected_defs.issubset(defs),
          f"缺少 {sorted(expected_defs - set(defs))}")
    rules = schema.get("x-wire-rules", {})
    check("wire 规则含 camelCase / null / canonicalJson",
          rules.get("naming", "").startswith("camelCase")
          and "null" in rules.get("optional", "")
          and "canonicalJson" in rules)

    # 2. 权威数据自洽
    canonical = codes["canonical"]
    aliases = codes["aliases"]
    review = codes["unmappedRequiresReview"]
    success = codes["successCode"]
    check("canonical 无重复", len(canonical) == len(set(canonical)))
    check("successCode 在 canonical 中", success in canonical)
    check("别名目标全部是规范码",
          all(target in canonical for target in aliases.values()),
          str([t for t in aliases.values() if t not in canonical]))
    check("别名键不与规范码重叠",
          not (set(aliases) & set(canonical)),
          str(sorted(set(aliases) & set(canonical))))
    check("待评审码未被擅自归一",
          not (set(review) & set(aliases)) and not (set(review) & set(canonical)),
          str(sorted((set(review) & set(aliases)) | (set(review) & set(canonical)))))

    # 3. 三语言绑定覆盖（源码字面量检查，防止漂移）
    texts = {}
    for lang, path in BINDINGS.items():
        check(f"{lang} 绑定文件存在", path.is_file(), str(path))
        texts[lang] = path.read_text(encoding="utf-8") if path.is_file() else ""
    tokens = canonical + list(aliases)
    for lang, text in texts.items():
        missing = [t for t in tokens if not re.search(r"\b" + re.escape(t) + r"\b", text)]
        check(f"{lang} 绑定覆盖 {len(canonical)} 规范码 + {len(aliases)} 别名",
              not missing, f"缺少 {missing[:8]}")

    # 4. golden 归一结果（Python 绑定直接执行）
    sys.path.insert(0, str(ROOT / "towndemontower-sdk-python/src"))
    from towndemontower.codes import canonical_code, counts_as_denied, is_canonical  # noqa: E402

    golden = codes["golden"]
    wrong = [
        (g["input"], g["expected"], canonical_code(g["input"]))
        for g in golden if canonical_code(g["input"]) != g["expected"]
    ]
    check(f"golden 归一 {len(golden)}/{len(golden)} 一致", not wrong, str(wrong[:5]))
    check("未映射码保留原值且不算规范码",
          canonical_code("SOME_FUTURE_CODE") == "SOME_FUTURE_CODE"
          and not is_canonical("SOME_FUTURE_CODE"))
    check("EXECUTION_ERROR 按 §6.3 保持未归一",
          canonical_code("EXECUTION_ERROR") == "EXECUTION_ERROR"
          and "EXECUTION_ERROR" in review)

    # 5. 统计口径
    stats = codes["statistics"]
    excludes = set(stats["deniedExcludes"])
    check("统计口径与 error-codes.json 声明一致",
          stats["pendingApprovalCode"] == "PENDING_APPROVAL"
          and stats["r3DeniedCode"] == "R3_DENIED"
          and excludes == {"OK", "PENDING_APPROVAL"})
    bad = [c for c in canonical if counts_as_denied(c) != (c not in excludes)]
    check("deniedExcludes 与 counts_as_denied 逐项一致", not bad, str(bad[:8]))
    check("归一后再统计（normalizeBeforeCount）",
          stats.get("normalizeBeforeCount") is True
          and counts_as_denied(canonical_code("PENDING_APPROVAL")) is False
          and counts_as_denied(canonical_code("APPROVAL_REQUIRED")) is False
          and counts_as_denied(canonical_code("RISK_REJECTED")) is True)

    print(f"\nRESULT: {_passed}/{_passed + _failed} passed")
    return 0 if _failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
