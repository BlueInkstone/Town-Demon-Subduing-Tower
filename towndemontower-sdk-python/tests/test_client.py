"""
测试客户端集成
"""

import unittest
import sys
import os

# 添加 src 到路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from towndemontower import TownDemontowerClient
from towndemontower.contract import RiskLevel, ErrorCodes
from towndemontower.client import ClientConfig


class TestTownDemontowerClient(unittest.TestCase):
    """TownDemontowerClient 集成测试"""
    
    def setUp(self):
        """设置测试"""
        self.client = TownDemontowerClient()
    
    def test_register_and_invoke(self):
        """测试注册和调用"""
        @self.client.skill("greet", risk=RiskLevel.R0)
        def greet(name: str) -> str:
            return f"Hello, {name}!"
        
        result = self.client.invoke("greet", {"name": "World"})
        
        self.assertTrue(result.success)
        self.assertEqual(result.output, "Hello, World!")
    
    def test_readonly_r0(self):
        """测试只读操作为 R0"""
        @self.client.skill("get_user", risk=RiskLevel.R0)
        def get_user(user_id: str) -> dict:
            return {"id": user_id, "name": "Test"}
        
        result = self.client.invoke("get_user", {"user_id": "001"})
        
        self.assertTrue(result.success)
        self.assertEqual(result.output["name"], "Test")
    
    def test_dangerous_r3(self):
        """测试危险操作为 R3"""
        @self.client.skill("delete_system", risk=RiskLevel.R3)
        def delete_system(system_id: str) -> bool:
            return True
        
        result = self.client.invoke("delete_system", {"system_id": "001"})
        
        # R3 应该被拒绝
        self.assertFalse(result.success)
        self.assertEqual(result.error_code, ErrorCodes.RISK_REJECTED)
    
    def test_requires_approval_r2(self):
        """测试需要操作"""
        @self.client.skill("modify_config", risk=RiskLevel.R2)
        def modify_config(key: str, value: str) -> bool:
            return True
        
        result = self.client.invoke("modify_config", {"key": "timeout", "value": "30"})
        
        # R2 应该需要
        self.assertFalse(result.success)
        self.assertEqual(result.error_code, ErrorCodes.APPROVAL_REQUIRED)
    
    def test_unknown_tool(self):
        """测试未知工具"""
        result = self.client.invoke("unknown_tool", {})
        
        self.assertFalse(result.success)
        self.assertEqual(result.error_code, ErrorCodes.CAPABILITY_EXCEEDED)
    
    def test_audit_records(self):
        """测试审计记录"""
        @self.client.skill("test_audit", risk=RiskLevel.R0)
        def test_audit():
            return "audited"
        
        self.client.invoke("test_audit", {})
        
        records = self.client.get_audit_records()
        self.assertGreater(len(records), 0)
        
        # 应该包含风险
        actions = [r["action"] for r in records]
        self.assertIn("RISK_EVALUATED", actions)
        self.assertIn("SKILL_INVOKED", actions)
    
    def test_audit_chain_verify(self):
        """测试审计链验证"""
        @self.client.skill("verify_test", risk=RiskLevel.R0)
        def verify_test():
            return True
        
        self.client.invoke("verify_test", {})
        
        # 验证链应该通过
        self.assertTrue(self.client.verify_audit_chain())
    
    def test_metrics(self):
        """测试指标"""
        @self.client.skill("metric_test", risk=RiskLevel.R0)
        def metric_test():
            return "ok"
        
        self.client.invoke("metric_test", {})
        
        metrics = self.client.get_metrics()
        self.assertIsNotNone(metrics)
        self.assertEqual(metrics["total_invocations"], 1)
        self.assertEqual(metrics["r0_pass"], 1)
    
    def test_list_skills(self):
        """测试列出技能"""
        @self.client.skill("skill1", risk=RiskLevel.R0)
        def skill1():
            pass
        
        @self.client.skill("skill2", risk=RiskLevel.R1)
        def skill2():
            pass
        
        skills = self.client.list_skills()
        skill_names = [s["name"] for s in skills]
        
        self.assertIn("skill1", skill_names)
        self.assertIn("skill2", skill_names)
    
    def test_unregister_skill(self):
        """测试注销技能"""
        @self.client.skill("temp_skill", risk=RiskLevel.R0)
        def temp_skill():
            return "temp"
        
        # 应该能调用
        result = self.client.invoke("temp_skill", {})
        self.assertTrue(result.success)
        
        # 注销
        self.client.unregister_skill("temp_skill")
        
        # 再失败
        result = self.client.invoke("temp_skill", {})
        self.assertFalse(result.success)
    
    def test_command_template_validation(self):
        """测试命令模板验证（command 字段参与形状校验）"""
        @self.client.skill(
            "safe_shell",
            risk=RiskLevel.R0,
            command_template="ls {path*}"
        )
        def safe_shell(command: str) -> str:
            return f"Executed: {command}"
        
        # 正常命令（command 字段）应该通过
        result = self.client.invoke("safe_shell", {"command": "ls /home/user"})
        self.assertTrue(result.success)
        
        # command 含控制字符应该失败
        result = self.client.invoke("safe_shell", {"command": "ls /home; rm -rf /"})
        self.assertFalse(result.success)
        self.assertEqual(result.error_code, ErrorCodes.COMMAND_TEMPLATE_MISMATCH)
    
    def test_context_override(self):
        """测试上下文覆盖"""
        @self.client.skill("context_test", risk=RiskLevel.R0)
        def context_test():
            return "ok"
        
        result = self.client.invoke(
            "context_test",
            {},
            mode="SAFE",
            agent_id="AGT-TEST"
        )
        
        self.assertTrue(result.success)
        
        # 检查审计记录中的上下文
        records = self.client.get_audit_records()
        risk_record = next(r for r in records if r["action"] == "RISK_EVALUATED")
        self.assertEqual(risk_record["payload"]["mode"], "SAFE")
        self.assertEqual(risk_record["payload"]["agent_id"], "AGT-TEST")


class TestClientConfig(unittest.TestCase):
    """ClientConfig 测试"""
    
    def test_default_config(self):
        """测试默认配置"""
        config = ClientConfig()
        self.assertIsNone(config.audit_file)
        self.assertEqual(config.default_mode, "NORMAL")
        self.assertTrue(config.metrics_enabled)
    
    def test_custom_config(self):
        """测试自定义配置"""
        config = ClientConfig(
            audit_file="/tmp/audit.log",
            default_mode="SAFE",
            default_agent_id="AGT-001",
            metrics_enabled=False,
        )
        self.assertEqual(config.audit_file, "/tmp/audit.log")
        self.assertEqual(config.default_mode, "SAFE")
        self.assertFalse(config.metrics_enabled)
    
    def test_config_with_file_audit(self):
        """测试带审计配置"""
        import tempfile
        with tempfile.NamedTemporaryFile(delete=False, suffix=".log") as f:
            audit_file = f.name
        
        try:
            config = ClientConfig(audit_file=audit_file)
            client = TownDemontowerClient(config)
            
            @client.skill("file_audit_test", risk=RiskLevel.R0)
            def test_func():
                return "ok"
            
            client.invoke("file_audit_test", {})
            
            # 应该创建了审计文件
            import os
            self.assertTrue(os.path.exists(audit_file))
        finally:
            import os
            if os.path.exists(audit_file):
                os.unlink(audit_file)


if __name__ == '__main__':
    unittest.main()
