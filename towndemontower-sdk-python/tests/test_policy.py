"""
测试策略模块（风险Gate）
"""

import unittest
import sys
import os

# 添加 src 到路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from towndemontower.contract import (
    IntentObject,
    ExecutionContext,
    SkillDescriptor,
    RiskLevel,
)
from towndemontower.policy import DefaultRiskGate, CommandTemplateValidator


class TestDefaultRiskGate(unittest.TestCase):
    """DefaultRiskGate 测试"""
    
    def setUp(self):
        """设置测试"""
        self.gate = DefaultRiskGate()
        self.context = ExecutionContext()
    
    def test_readonly_r0(self):
        """测试只读操作为 R0"""
        intent = IntentObject(tool="get_user")
        risk, ticket = self.gate.evaluate(intent, None, self.context)
        
        self.assertEqual(risk, RiskLevel.R0)
        self.assertIsNone(ticket)
    
    def test_list_r0(self):
        """测试 list 前缀为 R0"""
        intent = IntentObject(tool="list_items")
        risk, ticket = self.gate.evaluate(intent, None, self.context)
        
        self.assertEqual(risk, RiskLevel.R0)
    
    def test_query_r0(self):
        """测试 query 前缀为 R0"""
        intent = IntentObject(tool="query_data")
        risk, ticket = self.gate.evaluate(intent, None, self.context)
        
        self.assertEqual(risk, RiskLevel.R0)
    
    def test_delete_r3(self):
        """测试 delete 关键词为 R3"""
        intent = IntentObject(tool="delete_user")
        risk, ticket = self.gate.evaluate(intent, None, self.context)
        
        self.assertEqual(risk, RiskLevel.R3)
        self.assertIsNotNone(ticket)
        self.assertEqual(ticket.risk_level, RiskLevel.R3)
    
    def test_destroy_r3(self):
        """测试 destroy 关键词为 R3"""
        intent = IntentObject(tool="destroy_system")
        risk, ticket = self.gate.evaluate(intent, None, self.context)
        
        self.assertEqual(risk, RiskLevel.R3)
    
    def test_default_r1(self):
        """测试默认为 R1"""
        intent = IntentObject(tool="update_config")
        risk, ticket = self.gate.evaluate(intent, None, self.context)
        
        self.assertEqual(risk, RiskLevel.R1)
        self.assertIsNone(ticket)
    
    def test_descriptor_override(self):
        """测试描述符覆盖默认风险"""
        intent = IntentObject(tool="get_something")  # 通常 R0
        descriptor = SkillDescriptor(name="get_something", risk=RiskLevel.R2)
        
        risk, ticket = self.gate.evaluate(intent, descriptor, self.context)
        
        self.assertEqual(risk, RiskLevel.R2)
        self.assertIsNotNone(ticket)
    
    def test_r2_ticket_generated(self):
        """测试 R2 生成审批票据"""
        intent = IntentObject(tool="modify_data")
        descriptor = SkillDescriptor(name="modify_data", risk=RiskLevel.R2)
        
        risk, ticket = self.gate.evaluate(intent, descriptor, self.context)
        
        self.assertEqual(risk, RiskLevel.R2)
        self.assertIsNotNone(ticket)
        self.assertEqual(ticket.tool, "modify_data")


class TestCommandTemplateValidator(unittest.TestCase):
    """CommandTemplateValidator 测试"""
    
    def test_exact_match(self):
        """测试精确匹配"""
        valid, error = CommandTemplateValidator.validate(
            "ls -la",
            "ls -la"
        )
        self.assertTrue(valid)
        self.assertIsNone(error)
    
    def test_placeholder_match(self):
        """测试占匹配"""
        valid, error = CommandTemplateValidator.validate(
            "ls /home/user",
            "ls {path}"
        )
        self.assertTrue(valid)
    
    def test_extra_args(self):
        """测试多余参数（无尾部通配符时数量不符即拒）"""
        valid, error = CommandTemplateValidator.validate(
            "ls -la /home extra",
            "ls {path}"
        )
        self.assertFalse(valid)
        self.assertIn("Extra", error)
    
    def test_missing_literal(self):
        """测试缺少字面量"""
        valid, error = CommandTemplateValidator.validate(
            "ls",
            "ls -la"
        )
        self.assertFalse(valid)
        # 命令词数少于模板，报 Missing
        self.assertIn("Missing", error)
    
    def test_forbidden_chars(self):
        """测试禁止字符（无空格分隔的注入尝试会被槽位检查捕获）"""
        valid, error = CommandTemplateValidator.validate(
            "ls /home;rm",
            "ls {path}"
        )
        self.assertFalse(valid)
        self.assertIn("forbidden", error.lower())
    
    def test_forbidden_chars_with_extra_args(self):
        """测试带分号的注入在参数数量超限时先被 Extra 捕获"""
        valid, error = CommandTemplateValidator.validate(
            "ls /home; rm -rf /",
            "ls {path}"
        )
        self.assertFalse(valid)
        # 命令词数超出模板 → Extra 优先于 forbidden
        self.assertIn("Extra", error)
    
    def test_option_injection(self):
        """测试选项注入"""
        valid, error = CommandTemplateValidator.validate(
            "ls --dangerous",
            "ls {path}"
        )
        self.assertFalse(valid)
        self.assertIn("cannot start with -", error)
    
    def test_wildcard_placeholder(self):
        """测试通配符尾部槽位（{args*} 消费所有剩余词）"""
        valid, error = CommandTemplateValidator.validate(
            "cmd arg1 arg2 arg3",
            "cmd {args*}"
        )
        self.assertTrue(valid)
    
    def test_wildcard_placeholder_minimal(self):
        """测试通配符尾部槽位最小匹配（尾部槽可为空）"""
        valid, error = CommandTemplateValidator.validate(
            "git commit",
            "git commit {args*}"
        )
        self.assertTrue(valid)
    
    def test_wildcard_forbidden_chars(self):
        """测试通配符槽仍禁止控制字符"""
        valid, error = CommandTemplateValidator.validate(
            "cmd arg1;evil",
            "cmd {args*}"
        )
        self.assertFalse(valid)
        self.assertIn("forbidden", error.lower())
    
    def test_empty_command(self):
        """测试空命令"""
        valid, error = CommandTemplateValidator.validate("", "ls")
        self.assertFalse(valid)
        self.assertIn("empty", error.lower())
    
    def test_long_command(self):
        """测试超长命令"""
        valid, error = CommandTemplateValidator.validate(
            "ls " + "a" * 3000,
            "ls {path}"
        )
        self.assertFalse(valid)
        self.assertIn("too long", error.lower())
    
    def test_no_template(self):
        """测试无模板"""
        valid, error = CommandTemplateValidator.validate(
            "any command here",
            ""
        )
        self.assertTrue(valid)


if __name__ == '__main__':
    unittest.main()
