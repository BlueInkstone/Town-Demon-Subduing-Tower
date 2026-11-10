"""
测试审计模块
"""

import unittest
import sys
import os
import tempfile
import shutil

# 添加 src 到路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from towndemontower.audit import (
    AuditRecord,
    InMemoryAuditStore,
    FileAuditStore,
)


class TestAuditRecord(unittest.TestCase):
    """AuditRecord 测试"""
    
    def test_create(self):
        """测试创建"""
        record = AuditRecord(
            seq=0,
            action="TEST",
            payload={"key": "value"},
            timestamp="2026-01-01T00:00:00",
            prev_hash="0" * 64,
            hash="a" * 64,
        )
        self.assertEqual(record.seq, 0)
        self.assertEqual(record.action, "TEST")
    
    def test_to_dict(self):
        """测试字典转换"""
        record = AuditRecord(
            seq=0,
            action="TEST",
            payload={},
        )
        d = record.to_dict()
        self.assertEqual(d["seq"], 0)
        self.assertEqual(d["action"], "TEST")
    
    def test_to_json(self):
        """测试 JSON 转换"""
        record = AuditRecord(
            seq=0,
            action="TEST",
            payload={"key": "value"},
        )
        json_str = record.to_json()
        self.assertIn("TEST", json_str)
        self.assertIn("key", json_str)
    
    def test_from_dict(self):
        """测试从字典创建"""
        data = {
            "seq": 1,
            "action": "LOAD",
            "payload": {},
            "timestamp": "2026-01-01T00:00:00",
            "prev_hash": "0" * 64,
            "hash": "a" * 64,
            "mac": "",
        }
        record = AuditRecord.from_dict(data)
        self.assertEqual(record.seq, 1)
        self.assertEqual(record.action, "LOAD")


class TestInMemoryAuditStore(unittest.TestCase):
    """InMemoryAuditStore 测试"""
    
    def setUp(self):
        """设置测试"""
        self.store = InMemoryAuditStore()
    
    def test_append_auto(self):
        """测试自动追加"""
        audit_id = self.store.append_auto(
            action="TEST",
            payload={"key": "value"}
        )
        self.assertEqual(audit_id, "0")
        self.assertEqual(self.store.size(), 1)
    
    def test_multiple_appends(self):
        """测试多次追加"""
        self.store.append_auto(action="TEST1", payload={})
        self.store.append_auto(action="TEST2", payload={})
        self.store.append_auto(action="TEST3", payload={})
        
        self.assertEqual(self.store.size(), 3)
    
    def test_all(self):
        """测试获取所有"""
        self.store.append_auto(action="TEST1", payload={})
        self.store.append_auto(action="TEST2", payload={})
        
        records = self.store.all()
        self.assertEqual(len(records), 2)
        self.assertEqual(records[0].action, "TEST1")
        self.assertEqual(records[1].action, "TEST2")
    
    def test_verify_empty(self):
        """测试空"""
        self.assertTrue(self.store.verify())
    
    def test_verify_with_records(self):
        """测试有记录时验证"""
        self.store.append_auto(action="TEST1", payload={})
        self.store.append_auto(action="TEST2", payload={})
        
        self.assertTrue(self.store.verify())
    
    def test_hash_chain(self):
        """测试哈希链"""
        self.store.append_auto(action="TEST1", payload={})
        self.store.append_auto(action="TEST2", payload={})
        
        records = self.store.all()
        # 第二条的 prev_hash 应该等于第一条的 hash
        self.assertEqual(records[1].prev_hash, records[0].hash)


class TestFileAuditStore(unittest.TestCase):
    """FileAuditStore 测试"""
    
    def setUp(self):
        """设置测试环境"""
        self.temp_dir = tempfile.mkdtemp()
        self.audit_file = os.path.join(self.temp_dir, "audit.log")
        self.store = FileAuditStore(self.audit_file)
    
    def tearDown(self):
        """清理测试目录"""
        shutil.rmtree(self.temp_dir)
    
    def test_append_auto(self):
        """测试自动追加"""
        audit_id = self.store.append_auto(
            action="TEST",
            payload={"key": "value"}
        )
        self.assertEqual(audit_id, "0")
        self.assertEqual(self.store.size(), 1)
    
    def test_persistence(self):
        """测试持久化"""
        self.store.append_auto(action="TEST1", payload={})
        self.store.append_auto(action="TEST2", payload={})
        
        # 创建新实例，应该加载已有记录
        new_store = FileAuditStore(self.audit_file)
        self.assertEqual(new_store.size(), 2)
    
    def test_verify(self):
        """测试验证"""
        self.store.append_auto(action="TEST1", payload={})
        self.store.append_auto(action="TEST2", payload={})
        
        self.assertTrue(self.store.verify())
    
    def test_file_created(self):
        """测试文件创建"""
        self.store.append_auto(action="TEST", payload={})
        
        self.assertTrue(os.path.exists(self.audit_file))


if __name__ == '__main__':
    unittest.main()
