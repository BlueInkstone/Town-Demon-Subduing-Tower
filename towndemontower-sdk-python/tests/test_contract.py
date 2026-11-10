"""
测试契约层
"""

import unittest
import sys
import os

# 添加 src 到路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from towndemontower.contract import (
    IntentObject,
    RiskLevel,
    SkillDescriptor,
    ExecutionContext,
    SkillResult,
    ErrorCodes,
    ApprovalTicket,
)


class TestIntentObject(unittest.TestCase):
    """IntentObject 测试"""
    
    def test_create_basic(self):
        """测试基本创建"""
        intent = IntentObject(tool="test_tool")
        self.assertEqual(intent.tool, "test_tool")
        self.assertEqual(intent.args, {})
        self.assertEqual(intent.confidence, 1.0)
    
    def test_create_with_args(self):
        """测试带参数"""
        intent = IntentObject(
            tool="shell",
            args={"command": "ls -la"},
            confidence=0.9,
            text="列出文件"
        )
        self.assertEqual(intent.tool, "shell")
        self.assertEqual(intent.args["command"], "ls -la")
        self.assertEqual(intent.confidence, 0.9)
    
    def test_immutable(self):
        """测试不可变"""
        intent = IntentObject(tool="test", args={"key": "value"})
        with self.assertRaises(AttributeError):
            intent.tool = "other"
        # args 也被冻结（MappingProxyType）
        with self.assertRaises(TypeError):
            intent.args["new_key"] = "new_value"
    
    def test_to_dict(self):
        """测试字典转换"""
        intent = IntentObject(tool="test", args={"a": 1}, confidence=0.8)
        d = intent.to_dict()
        self.assertEqual(d["tool"], "test")
        self.assertEqual(d["args"], {"a": 1})
        self.assertEqual(d["confidence"], 0.8)
    
    def test_from_dict(self):
        """测试从字典创建"""
        data = {"tool": "test", "args": {"a": 1}, "confidence": 0.8, "text": ""}
        intent = IntentObject.from_dict(data)
        self.assertEqual(intent.tool, "test")
        self.assertEqual(intent.args, {"a": 1})
    
    def test_validation_tool_required(self):
        """测试工具名必填"""
        with self.assertRaises(ValueError):
            IntentObject(tool="")
    
    def test_validation_confidence_range(self):
        """测试置信度范围"""
        with self.assertRaises(ValueError):
            IntentObject(tool="test", confidence=1.5)
        with self.assertRaises(ValueError):
            IntentObject(tool="test", confidence=-0.1)


class TestRiskLevel(unittest.TestCase):
    """RiskLevel 测试"""
    
    def test_values(self):
        """测试所有值"""
        self.assertEqual(RiskLevel.R0.value, "R0")
        self.assertEqual(RiskLevel.R1.value, "R1")
        self.assertEqual(RiskLevel.R2.value, "R2")
        self.assertEqual(RiskLevel.R3.value, "R3")


class TestSkillDescriptor(unittest.TestCase):
    """SkillDescriptor 测试"""
    
    def test_create_basic(self):
        """测试基础创建"""
        desc = SkillDescriptor(name="test")
        self.assertEqual(desc.name, "test")
        self.assertEqual(desc.risk, RiskLevel.R1)
        self.assertEqual(desc.required_permissions, [])
    
    def test_create_full(self):
        """测试完整创建"""
        desc = SkillDescriptor(
            name="shell",
            risk=RiskLevel.R2,
            required_permissions=["execute"],
            required_capabilities=["shell"],
            command_template="ls {path*}",
            requires_plan_approval=True
        )
        self.assertEqual(desc.risk, RiskLevel.R2)
        self.assertTrue(desc.requires_plan_approval)
        self.assertEqual(desc.command_template, "ls {path*}")
    
    def test_to_dict(self):
        """测试字典转换"""
        desc = SkillDescriptor(name="test", risk=RiskLevel.R0)
        d = desc.to_dict()
        self.assertEqual(d["name"], "test")
        self.assertEqual(d["risk"], "R0")


class TestExecutionContext(unittest.TestCase):
    """ExecutionContext 测试"""
    
    def test_create_default(self):
        """测试默认创建"""
        ctx = ExecutionContext()
        self.assertEqual(ctx.mode, "NORMAL")
        self.assertIsNone(ctx.agent_id)
        self.assertFalse(ctx.force)
    
    def test_create_full(self):
        """测试完整创建"""
        ctx = ExecutionContext(
            mode="SAFE",
            agent_id="AGT-0001",
            owner_user_id="user1",
            force=True
        )
        self.assertEqual(ctx.mode, "SAFE")
        self.assertEqual(ctx.agent_id, "AGT-0001")
        self.assertTrue(ctx.force)


class TestSkillResult(unittest.TestCase):
    """SkillResult 测试"""
    
    def test_ok(self):
        """测试成功结果"""
        result = SkillResult.ok(output="done")
        self.assertTrue(result.success)
        self.assertEqual(result.output, "done")
        self.assertIsNone(result.error_code)
    
    def test_fail(self):
        """测试失败结果"""
        result = SkillResult.fail(
            error_code=ErrorCodes.PERMISSION_DENIED,
            error_message="No permission"
        )
        self.assertFalse(result.success)
        self.assertEqual(result.error_code, ErrorCodes.PERMISSION_DENIED)
    
    def test_to_dict(self):
        """测试字典转换"""
        result = SkillResult.ok(output="test", audit_id="1")
        d = result.to_dict()
        self.assertTrue(d["success"])
        self.assertEqual(d["output"], "test")
        self.assertEqual(d["audit_id"], "1")


class TestApprovalTicket(unittest.TestCase):
    """ApprovalTicket 测试"""
    
    def test_create(self):
        """测试创建"""
        ticket = ApprovalTicket(
            ticket_id="TKT-001",
            tool="delete",
            risk_level=RiskLevel.R2,
            reason="Dangerous operation"
        )
        self.assertEqual(ticket.ticket_id, "TKT-001")
        self.assertEqual(ticket.risk_level, RiskLevel.R2)


if __name__ == '__main__':
    unittest.main()
