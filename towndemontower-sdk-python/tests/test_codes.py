"""
方向 D 跨语言错误码口径验收（Python 侧）。

权威数据直接读仓库根 ``contract/error-codes.json``，逐条跑 golden，
保证 Python 绑定与已定稿口径同源；跨语言一致性另见 ``contract/verify_codes.py``。
"""

import json
import unittest
from pathlib import Path

from towndemontower import (
    ALIASES,
    CANONICAL_CODES,
    UNMAPPED_REQUIRES_REVIEW,
    canonical_code,
    counts_as_denied,
    is_canonical,
)

CONTRACT = Path(__file__).resolve().parents[2] / "contract" / "error-codes.json"


class TestCanonicalCodes(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with CONTRACT.open("r", encoding="utf-8") as f:
            cls.spec = json.load(f)

    def test_ratified(self):
        self.assertEqual(self.spec["status"], "ratified")
        self.assertEqual(self.spec["version"], "1.0.0")

    def test_canonical_set_matches_authority(self):
        self.assertEqual(set(CANONICAL_CODES), set(self.spec["canonical"]))
        self.assertEqual(len(CANONICAL_CODES), 30)

    def test_aliases_match_authority(self):
        self.assertEqual(dict(ALIASES), self.spec["aliases"])
        self.assertTrue(all(v in CANONICAL_CODES for v in ALIASES.values()))

    def test_unmapped_match_authority(self):
        self.assertEqual(set(UNMAPPED_REQUIRES_REVIEW),
                         set(self.spec["unmappedRequiresReview"]))

    def test_golden_all(self):
        """逐条跑权威文件的 golden 输入，验证归一结果。"""
        for case in self.spec["golden"]:
            with self.subTest(input=case["input"]):
                self.assertEqual(canonical_code(case["input"]), case["expected"])

    def test_canonical_pass_through(self):
        self.assertEqual(canonical_code("PENDING_APPROVAL"), "PENDING_APPROVAL")
        self.assertEqual(canonical_code("  RISK_REJECTED  "), "R3_DENIED")
        self.assertIsNone(canonical_code(None))
        self.assertEqual(canonical_code(""), "")

    def test_is_canonical(self):
        self.assertTrue(is_canonical("OK"))
        self.assertFalse(is_canonical("APPROVAL_REQUIRED"))
        self.assertFalse(is_canonical("SOME_FUTURE_CODE"))


class TestStatistics(unittest.TestCase):
    """与 Java 侧 ErrorCodeCatalog.countsAsDenied / AuditDecorator 口径一致。"""

    def test_denied_excludes(self):
        self.assertFalse(counts_as_denied("OK"))
        self.assertFalse(counts_as_denied("PENDING_APPROVAL"))
        self.assertTrue(counts_as_denied("R3_DENIED"))
        self.assertTrue(counts_as_denied("PERMISSION_DENIED"))
        self.assertTrue(counts_as_denied("INTERNAL_ERROR"))
        self.assertFalse(counts_as_denied(None))

    def test_normalize_before_count(self):
        self.assertFalse(counts_as_denied(canonical_code("APPROVAL_REQUIRED")))
        self.assertTrue(counts_as_denied(canonical_code("RISK_REJECTED")))
        self.assertTrue(counts_as_denied(canonical_code("EXECUTION_ERROR")))

    def test_denied_excludes_declaration(self):
        with CONTRACT.open("r", encoding="utf-8") as f:
            excludes = set(json.load(f)["statistics"]["deniedExcludes"])
        for code in CANONICAL_CODES:
            with self.subTest(code=code):
                self.assertEqual(counts_as_denied(code), code not in excludes)


if __name__ == "__main__":
    unittest.main()
