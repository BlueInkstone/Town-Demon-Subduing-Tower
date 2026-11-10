"""
测试装饰器模块（GovernancePass）
"""

import unittest
import sys
import os

# 添加 src 到路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from towndemontower.decorator import (
    GovernancePass,
    GovernanceMetrics,
    UngovernedInvocationException,
)


class TestGovernancePass(unittest.TestCase):
    """GovernancePass 测试"""
    
    def setUp(self):
        """设置测试环境"""
        # 清空令牌栈
        while GovernancePass.current() is not None:
            GovernancePass.clear()
    
    def test_issue_and_require(self):
        """测试发放和要求"""
        token = GovernancePass.issue(audit_id="1", tool="test")
        
        # 应该能够要求
        current = GovernancePass.require("test")
        self.assertEqual(current.audit_id, "1")
        self.assertEqual(current.tool, "test")
    
    def test_clear(self):
        """测试清除"""
        GovernancePass.issue(audit_id="1", tool="test")
        GovernancePass.clear()
        
        # 应该没有
        with self.assertRaises(UngovernedInvocationException):
            GovernancePass.require("test")
    
    def test_ungoverned_exception(self):
        """测试未治理异常"""
        with self.assertRaises(UngovernedInvocationException):
            GovernancePass.require("test")
    
    def test_scope_context_manager(self):
        """测试上下文管理器"""
        with GovernancePass.scope(audit_id="1", tool="test") as token:
            # 在范围内应该可以要求
            current = GovernancePass.require("test")
            self.assertEqual(current.audit_id, "1")
        
        # 范围外应该失败
        with self.assertRaises(UngovernedInvocationException):
            GovernancePass.require("test")
    
    def test_nested_scopes(self):
        """测试嵌套范围"""
        with GovernancePass.scope(audit_id="1", tool="outer"):
            self.assertEqual(GovernancePass.require("outer").audit_id, "1")
            
            with GovernancePass.scope(audit_id="2", tool="inner"):
                self.assertEqual(GovernancePass.require("inner").audit_id, "2")
            
            # 内层退出后应该回到外层
            self.assertEqual(GovernancePass.require("outer").audit_id, "1")
    
    def test_consume(self):
        """测试消费"""
        token = GovernancePass.issue(audit_id="1", tool="test")
        
        # 第一次消费成功
        self.assertTrue(token.consume())
        
        # 第二次消费失败
        self.assertFalse(token.consume())
    
    def test_max_stack_depth(self):
        """测试最大栈深度"""
        # 创建很多
        for i in range(GovernancePass._MAX_STACK_DEPTH):
            GovernancePass.issue(audit_id=str(i), tool=f"tool_{i}")
        
        # 再一个应该失败
        with self.assertRaises(RuntimeError):
            GovernancePass.issue(audit_id="overflow", tool="overflow")


class TestGovernanceMetrics(unittest.TestCase):
    """GovernanceMetrics 测试"""
    
    def setUp(self):
        """设置测试"""
        self.metrics = GovernanceMetrics()
    
    def test_record_r0(self):
        """测试记录 R0"""
        self.metrics.record_r0()
        
        snapshot = self.metrics.snapshot()
        self.assertEqual(snapshot["total_invocations"], 1)
        self.assertEqual(snapshot["r0_pass"], 1)
    
    def test_record_r1(self):
        """测试记录 R1"""
        self.metrics.record_r1()
        
        snapshot = self.metrics.snapshot()
        self.assertEqual(snapshot["r1_pass"], 1)
    
    def test_record_r2_pending(self):
        """测试记录 R2 待"""
        self.metrics.record_r2_pending()
        
        snapshot = self.metrics.snapshot()
        self.assertEqual(snapshot["r2_pending"], 1)
    
    def test_record_r2_approved(self):
        """测试记录 R2 批准"""
        self.metrics.record_r2_approved()
        
        snapshot = self.metrics.snapshot()
        self.assertEqual(snapshot["r2_approved"], 1)
    
    def test_record_r3(self):
        """测试记录 R3"""
        self.metrics.record_r3()
        
        snapshot = self.metrics.snapshot()
        self.assertEqual(snapshot["r3_rejected"], 1)
    
    def test_record_permission_denied(self):
        """测试记录权限拒绝"""
        self.metrics.record_permission_denied()
        
        snapshot = self.metrics.snapshot()
        self.assertEqual(snapshot["permission_denied"], 1)
    
    def test_record_ungoverned(self):
        """测试记录未治理"""
        self.metrics.record_ungoverned()
        
        snapshot = self.metrics.snapshot()
        self.assertEqual(snapshot["ungoverned_invocations"], 1)
    
    def test_snapshot_immutable(self):
        """测试快照不可"""
        self.metrics.record_r0()
        
        snapshot1 = self.metrics.snapshot()
        self.metrics.record_r1()
        snapshot2 = self.metrics.snapshot()
        
        # 快照应该独立
        self.assertEqual(snapshot1["r1_pass"], 0)
        self.assertEqual(snapshot2["r1_pass"], 1)


if __name__ == '__main__':
    unittest.main()
