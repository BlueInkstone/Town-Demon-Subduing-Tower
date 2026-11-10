"""
审计模块 - 对齐 core/audit/ 模块

定义审计记录和审计存储协议。
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Optional, List
from datetime import datetime
import hashlib
import json
import threading
import os


@dataclass(frozen=True)
class AuditRecord:
    """
    审计记录 - 对齐 core AuditRecord.java
    
    不可变的审计事件记录。
    """
    seq: int
    action: str
    payload: dict[str, Any]
    timestamp: str = field(default_factory=lambda: datetime.utcnow().isoformat())
    prev_hash: str = ""
    hash: str = ""
    mac: str = ""
    
    def to_dict(self) -> dict[str, Any]:
        """转换为字典"""
        return {
            "seq": self.seq,
            "action": self.action,
            "payload": self.payload,
            "timestamp": self.timestamp,
            "prev_hash": self.prev_hash,
            "hash": self.hash,
            "mac": self.mac,
        }
    
    def to_json(self) -> str:
        """转换为 JSON 字符串"""
        return json.dumps(self.to_dict(), ensure_ascii=False)
    
    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "AuditRecord":
        """从字典创建审计记录"""
        return cls(
            seq=data.get("seq", 0),
            action=data.get("action", ""),
            payload=data.get("payload", {}),
            timestamp=data.get("timestamp", datetime.utcnow().isoformat()),
            prev_hash=data.get("prev_hash", ""),
            hash=data.get("hash", ""),
            mac=data.get("mac", ""),
        )


class AuditStore(ABC):
    """
    审计存储协议 - 对齐 core AuditStore.java
    
    定义审计记录的存储接口。
    """
    
    @abstractmethod
    def append_auto(
        self,
        action: str,
        payload: dict[str, Any]
    ) -> str:
        """
        自动追加审计记录（由存储计算 seq/hash/mac）
        
        Args:
            action: 动作名称
            payload: 事件负载
            
        Returns:
            审计 ID（seq 的字符串形式）
        """
        pass
    
    @abstractmethod
    def all(self) -> List[AuditRecord]:
        """获取所有审计记录"""
        pass
    
    @abstractmethod
    def size(self) -> int:
        """获取审计记录数量"""
        pass
    
    @abstractmethod
    def verify(self) -> bool:
        """验证审计链完整性"""
        pass


class InMemoryAuditStore(AuditStore):
    """
    内存审计存储 - 对齐 core 测试用存储
    
    用于测试和演示场景。
    """
    
    def __init__(self):
        self._records: List[AuditRecord] = []
        self._lock = threading.RLock()
        self._prev_hash = "0" * 64  # 初始哈希
    
    def append_auto(
        self,
        action: str,
        payload: dict[str, Any]
    ) -> str:
        """追加审计记录"""
        with self._lock:
            seq = len(self._records)
            timestamp = datetime.utcnow().isoformat()
            
            # 计算哈希
            content = f"{seq}:{action}:{json.dumps(payload, sort_keys=True)}:{timestamp}:{self._prev_hash}"
            hash_value = hashlib.sha256(content.encode()).hexdigest()
            
            record = AuditRecord(
                seq=seq,
                action=action,
                payload=payload,
                timestamp=timestamp,
                prev_hash=self._prev_hash,
                hash=hash_value,
                mac="",  # 简化版不计算 MAC
            )
            
            self._records.append(record)
            self._prev_hash = hash_value
            
            return str(seq)
    
    def all(self) -> List[AuditRecord]:
        """获取所有记录"""
        with self._lock:
            return list(self._records)
    
    def size(self) -> int:
        """获取记录数量"""
        with self._lock:
            return len(self._records)
    
    def verify(self) -> bool:
        """验证链完整性"""
        with self._lock:
            prev_hash = "0" * 64
            for record in self._records:
                if record.prev_hash != prev_hash:
                    return False
                # 重新计算哈希
                content = f"{record.seq}:{record.action}:{json.dumps(record.payload, sort_keys=True)}:{record.timestamp}:{record.prev_hash}"
                expected_hash = hashlib.sha256(content.encode()).hexdigest()
                if record.hash != expected_hash:
                    return False
                prev_hash = record.hash
            return True


class FileAuditStore(AuditStore):
    """
    文件审计存储 - 对齐 core FileAuditStore.java
    
    追加写入文件，支持分段校验。
    """
    
    def __init__(self, file_path: str):
        self._file_path = file_path
        self._records: List[AuditRecord] = []
        self._lock = threading.RLock()
        self._prev_hash = "0" * 64
        self._segment_size = 1000  # 每 1000 条一个分段
        self._load_existing()
    
    def _load_existing(self) -> None:
        """加载已有审计记录"""
        if not os.path.exists(self._file_path):
            return
        
        with self._lock:
            with open(self._file_path, 'r', encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith('#'):
                        try:
                            data = json.loads(line)
                            record = AuditRecord.from_dict(data)
                            self._records.append(record)
                            self._prev_hash = record.hash
                        except json.JSONDecodeError:
                            pass  # 跳过损坏的行
    
    def append_auto(
        self,
        action: str,
        payload: dict[str, Any]
    ) -> str:
        """追加审计记录"""
        with self._lock:
            seq = len(self._records)
            timestamp = datetime.utcnow().isoformat()
            
            # 计算哈希
            content = f"{seq}:{action}:{json.dumps(payload, sort_keys=True)}:{timestamp}:{self._prev_hash}"
            hash_value = hashlib.sha256(content.encode()).hexdigest()
            
            record = AuditRecord(
                seq=seq,
                action=action,
                payload=payload,
                timestamp=timestamp,
                prev_hash=self._prev_hash,
                hash=hash_value,
                mac="",
            )
            
            self._records.append(record)
            self._prev_hash = hash_value
            
            # 追加写入文件
            with open(self._file_path, 'a', encoding='utf-8') as f:
                f.write(record.to_json() + '\n')
            
            return str(seq)
    
    def all(self) -> List[AuditRecord]:
        """获取所有记录"""
        with self._lock:
            return list(self._records)
    
    def size(self) -> int:
        """获取记录数量"""
        with self._lock:
            return len(self._records)
    
    def verify(self) -> bool:
        """验证链完整性"""
        with self._lock:
            prev_hash = "0" * 64
            for record in self._records:
                if record.prev_hash != prev_hash:
                    return False
                # 重新计算哈希
                content = f"{record.seq}:{record.action}:{json.dumps(record.payload, sort_keys=True)}:{record.timestamp}:{record.prev_hash}"
                expected_hash = hashlib.sha256(content.encode()).hexdigest()
                if record.hash != expected_hash:
                    return False
                prev_hash = record.hash
            return True
