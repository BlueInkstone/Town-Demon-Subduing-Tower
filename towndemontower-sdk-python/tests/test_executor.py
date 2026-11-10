"""
测试执行器模块
"""

import unittest
import sys
import os

# 添加 src 到路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from towndemontower.contract import (
    IntentObject,
    ExecutionContext,
    SkillResult,
    SkillDescriptor,
    RiskLevel,
    ErrorCodes,
)
from towndemontower.executor import (
    Executor,
    SkillRegistry,
    PythonFunctionExecutor,
    RejectAllExecutor,
)


class TestPythonFunctionExecutor(unittest.TestCase):
    """PythonFunctionExecutor 测试"""
    
    def setUp(self):
        """设置测试环境"""
        self.executor = PythonFunctionExecutor()
    
    def test_register_and_execute(self):
        """测试注册和执行"""
        def greet(name: str) -> str:
            return f"Hello, {name}!"
        
        self.executor.register("greet", greet)
        
        intent = IntentObject(tool="greet", args={"name": "World"})
        context = ExecutionContext()
        
        result = self.executor.execute(intent, context)
        
        self.assertTrue(result.success)
        self.assertEqual(result.output, "Hello, World!")
    
    def test_unknown_tool(self):
        """测试未知工具"""
        intent = IntentObject(tool="unknown")
        context = ExecutionContext()
        
        result = self.executor.execute(intent, context)
        
        self.assertFalse(result.success)
        self.assertEqual(result.error_code, ErrorCodes.CAPABILITY_EXCEEDED)
    
    def test_unregister(self):
        """测试注销"""
        def test_func():
            return "test"
        
        self.executor.register("test", test_func)
        self.assertIn("test", self.executor.list_skills())
        
        self.executor.unregister("test")
        self.assertNotIn("test", self.executor.list_skills())
    
    def test_register_with_descriptor(self):
        """测试带描述符注册"""
        def safe_func():
            return "safe"
        
        descriptor = SkillDescriptor(
            name="safe",
            risk=RiskLevel.R0
        )
        
        self.executor.register("safe", safe_func, descriptor)
        
        retrieved = self.executor.get_descriptor("safe")
        self.assertIsNotNone(retrieved)
        self.assertEqual(retrieved.name, "safe")
        self.assertEqual(retrieved.risk, RiskLevel.R0)
    
    def test_exception_handling(self):
        """测试异常处理"""
        def failing_func():
            raise ValueError("Test error")
        
        self.executor.register("failing", failing_func)
        
        intent = IntentObject(tool="failing")
        context = ExecutionContext()
        
        result = self.executor.execute(intent, context)
        
        self.assertFalse(result.success)
        self.assertEqual(result.error_code, ErrorCodes.INTERNAL_ERROR)
        self.assertIn("Test error", result.error_message)


class TestSkillRegistry(unittest.TestCase):
    """SkillRegistry 测试"""
    
    def setUp(self):
        """设置测试"""
        self.registry = SkillRegistry()
    
    def test_register_and_get(self):
        """测试注册和获取"""
        descriptor = SkillDescriptor(name="test", risk=RiskLevel.R1)
        
        self.registry.register(descriptor)
        
        retrieved = self.registry.get("test")
        self.assertIsNotNone(retrieved)
        self.assertEqual(retrieved.name, "test")
    
    def test_contains(self):
        """测试包含检查"""
        descriptor = SkillDescriptor(name="test")
        self.registry.register(descriptor)
        
        self.assertTrue(self.registry.contains("test"))
        self.assertFalse(self.registry.contains("other"))
    
    def test_names(self):
        """测试获取所有名称列表"""
        self.registry.register(SkillDescriptor(name="skill1"))
        self.registry.register(SkillDescriptor(name="skill2"))
        
        names = self.registry.names()
        self.assertIn("skill1", names)
        self.assertIn("skill2", names)
    
    def test_all(self):
        """测试获取所有描述符"""
        self.registry.register(SkillDescriptor(name="skill1"))
        self.registry.register(SkillDescriptor(name="skill2"))
        
        all_desc = self.registry.all()
        self.assertEqual(len(all_desc), 2)
    
    def test_unregister(self):
        """测试注销"""
        descriptor = SkillDescriptor(name="test")
        self.registry.register(descriptor)
        
        self.registry.unregister("test")
        self.assertIsNone(self.registry.get("test"))


class TestRejectAllExecutor(unittest.TestCase):
    """RejectAllExecutor 测试"""
    
    def test_reject_all(self):
        """测试全部拒绝"""
        executor = RejectAllExecutor()
        
        intent = IntentObject(tool="any")
        context = ExecutionContext()
        
        result = executor.execute(intent, context)
        
        self.assertFalse(result.success)
        self.assertEqual(result.error_code, ErrorCodes.INTERNAL_ERROR)
        self.assertIn("disabled", result.error_message.lower())


if __name__ == '__main__':
    unittest.main()
