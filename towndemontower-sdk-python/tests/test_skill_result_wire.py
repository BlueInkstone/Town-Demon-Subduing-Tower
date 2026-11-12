"""
方向 D 第③步：SkillResult wire 结构统一验收（Python 侧）。

golden 直接读仓库根 ``contract/skill-result.golden.json``，
保证 Python 的 to_wire() 与 Java / TypeScript 产出同一份结构。
"""

import json
import unittest
from pathlib import Path

from towndemontower import ErrorCodes, SkillResult
from towndemontower.codes import CANONICAL_CODES, SUCCESS_CODE

CONTRACT = Path(__file__).resolve().parents[2] / "contract"


class TestSkillResultWire(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with (CONTRACT / "skill-result.golden.json").open("r", encoding="utf-8") as f:
            cls.golden = json.load(f)
        with (CONTRACT / "towndemontower-wire.schema.json").open("r", encoding="utf-8") as f:
            cls.schema = json.load(f)

    def _wire(self, case_id):
        return next(c["wire"] for c in self.golden["cases"] if c["id"] == case_id)

    def test_schema_fields_match_golden(self):
        required = self.schema["$defs"]["SkillResult"]["required"]
        self.assertEqual(set(self.golden["wireFields"]), set(required))
        self.assertIs(self.schema["$defs"]["SkillResult"]["additionalProperties"], False)

    def test_golden_all_cases(self):
        """5 个 golden case：本地结果 → to_wire() 逐字段比对。"""
        results = {
            "ok": SkillResult.ok(output="done"),
            "ok-object-output": SkillResult.ok(output={"items": [1, 2], "label": "batch"}),
            "denied-permission": SkillResult.fail("PERMISSION_DENIED", "No permission"),
            "pending-approval-alias": SkillResult.fail(
                ErrorCodes.APPROVAL_REQUIRED,
                "awaiting human approval",
                approval_ticket_id="TKT-1",
            ),
            "r3-denied-alias": SkillResult.fail(
                ErrorCodes.RISK_REJECTED, "irreversible action blocked"
            ),
        }
        for case in self.golden["cases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(results[case["id"]].to_wire(), case["wire"])

    def test_no_local_extensions_in_wire(self):
        w = SkillResult.ok(output="x", audit_id="7").to_wire()
        self.assertEqual(set(w), set(self.golden["wireFields"]))
        for local in self.golden["languageLocalOnly"]:
            self.assertNotIn(local, w)

    def test_alias_normalized_in_wire(self):
        w = SkillResult.fail(ErrorCodes.APPROVAL_REQUIRED, "x").to_wire()
        self.assertEqual(w["code"], "PENDING_APPROVAL")
        self.assertIn(w["code"], CANONICAL_CODES)

    def test_success_code_ok(self):
        self.assertEqual(SkillResult.ok("x").to_wire()["code"], SUCCESS_CODE)
        self.assertEqual(SkillResult.ok("x").code, SUCCESS_CODE)

    def test_round_trip(self):
        original = SkillResult.fail(
            ErrorCodes.RISK_REJECTED, "irreversible action blocked",
            approval_ticket_id="TKT-2", audit_id="9",
        )
        restored = SkillResult.from_wire(original.to_wire())
        self.assertEqual(restored.to_wire(), original.to_wire())
        self.assertEqual(restored.code, "R3_DENIED")
        self.assertIsNone(restored.audit_id)  # 本地扩展不进 wire

    def test_local_fields_unchanged(self):
        """既有 118 项测试依赖的本地字段与 to_dict() 行为不变（零回归）。"""
        r = SkillResult.fail(ErrorCodes.APPROVAL_REQUIRED, "No permission", audit_id="1")
        self.assertEqual(r.error_code, ErrorCodes.APPROVAL_REQUIRED)
        self.assertEqual(r.error_message, "No permission")
        d = r.to_dict()
        self.assertEqual(d["audit_id"], "1")
        self.assertEqual(set(d), {"success", "output", "error_code", "error_message", "audit_id"})
        ok = SkillResult.ok(output="done")
        self.assertIsNone(ok.error_code)
        self.assertTrue(ok.to_dict()["success"])

    def test_from_dict_unchanged(self):
        r = SkillResult.from_dict({"success": False, "error_code": "RISK_REJECTED",
                                  "error_message": "x", "audit_id": "2"})
        self.assertEqual(r.error_code, "RISK_REJECTED")
        self.assertEqual(r.to_wire()["code"], "R3_DENIED")


if __name__ == "__main__":
    unittest.main()
