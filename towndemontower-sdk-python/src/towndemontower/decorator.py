"""
装饰器模块 - 对齐 core/decorator/ 模块

实现 GovernancePass 一次性令牌机制。
"""

from typing import Optional, Any
from contextlib import contextmanager
import threading


class UngovernedInvocationException(Exception):
    """
    未受治理的调用异常 - 对齐 core UngovernedInvocationException.java
    
    当没有持有 GovernancePass 令牌时抛出。
    """
    
    def __init__(self, tool: str, reason: str = "UNGOVERNED_INVOCATION"):
        self.tool = tool
        self.reason = reason
        super().__init__(f"Ungoverned invocation of tool: {tool}")


class GovernancePass:
    """
    治理通行证 - 对齐 core GovernancePass.java
    
    一次性令牌，确保调用栈正在被治理。
    使用 ThreadLocal 存储，不跨异步线程。
    """
    
    _local = threading.local()
    _MAX_STACK_DEPTH = 32
    
    @classmethod
    def _get_stack(cls) -> list:
        """获取当前线程的令牌栈"""
        if not hasattr(cls._local, 'stack'):
            cls._local.stack = []
        return cls._local.stack
    
    @classmethod
    def issue(
        cls,
        audit_id: str,
        tool: str,
        metrics: Optional[Any] = None
    ) -> "GovernancePass":
        """
        发放令牌（包内可见，只能由装饰器链尾调用）
        
        Args:
            audit_id: 审计 ID
            tool: 工具名称
            metrics: 指标收集器（可选）
            
        Returns:
            GovernancePass 实例
        """
        stack = cls._get_stack()
        
        if len(stack) >= cls._MAX_STACK_DEPTH:
            raise RuntimeError(f"GovernancePass stack depth exceeded: {cls._MAX_STACK_DEPTH}")
        
        token = cls(audit_id, tool, metrics)
        stack.append(token)
        return token
    
    @classmethod
    def require(cls, tool: str) -> "GovernancePass":
        """
        消费令牌（方法首行调用）
        
        Args:
            tool: 工具名称
            
        Returns:
            GovernancePass 实例
            
        Raises:
            UngovernedInvocationException: 未持有令牌时抛出
        """
        stack = cls._get_stack()
        
        if not stack:
            raise UngovernedInvocationException(tool)
        
        return stack[-1]
    
    @classmethod
    def clear(cls) -> None:
        """清除栈顶令牌"""
        stack = cls._get_stack()
        if stack:
            stack.pop()
    
    @classmethod
    def current(cls) -> Optional["GovernancePass"]:
        """获取当前令牌（不清除）"""
        stack = cls._get_stack()
        return stack[-1] if stack else None
    
    @classmethod
    @contextmanager
    def scope(cls, audit_id: str, tool: str, metrics: Optional[Any] = None):
        """
        上下文管理器形式的令牌
        
        Usage:
            with GovernancePass.scope(audit_id, tool) as token:
                # 在治理范围内执行
                pass
        """
        token = cls.issue(audit_id, tool, metrics)
        try:
            yield token
        finally:
            cls.clear()
    
    def __init__(self, audit_id: str, tool: str, metrics: Optional[Any] = None):
        self._audit_id = audit_id
        self._tool = tool
        self._metrics = metrics
        self._consumed = False
    
    @property
    def audit_id(self) -> str:
        """获取审计 ID"""
        return self._audit_id
    
    @property
    def tool(self) -> str:
        """获取工具名称"""
        return self._tool
    
    @property
    def metrics(self) -> Optional[Any]:
        """获取指标收集器"""
        return self._metrics
    
    def consume(self) -> bool:
        """
        消费令牌（一次性）
        
        Returns:
            是否成功消费（False 表示已消费过）
        """
        if self._consumed:
            return False
        self._consumed = True
        return True
    
    def is_consumed(self) -> bool:
        """检查是否已消费"""
        return self._consumed


class GovernanceMetrics:
    """
    治理指标 - 对齐 core GovernanceMetrics.java
    
    记录治理链的关键指标。
    """
    
    def __init__(self):
        self._lock = threading.RLock()
        self.total_invocations = 0
        self.r0_pass = 0
        self.r1_pass = 0
        self.r2_pending = 0
        self.r2_approved = 0
        self.r2_rejected = 0
        self.r3_rejected = 0
        self.permission_denied = 0
        self.capability_exceeded = 0
        self.schema_violations = 0
        self.ungoverned_invocations = 0
        self.explicit_bypass = 0
    
    def record_r0(self) -> None:
        """记录 R0 放行"""
        with self._lock:
            self.total_invocations += 1
            self.r0_pass += 1
    
    def record_r1(self) -> None:
        """记录 R1 放行"""
        with self._lock:
            self.total_invocations += 1
            self.r1_pass += 1
    
    def record_r2_pending(self) -> None:
        """记录 R2 待审批"""
        with self._lock:
            self.total_invocations += 1
            self.r2_pending += 1
    
    def record_r2_approved(self) -> None:
        """记录 R2 已批准"""
        with self._lock:
            self.r2_approved += 1
    
    def record_r2_rejected(self) -> None:
        """记录 R2 已拒绝"""
        with self._lock:
            self.r2_rejected += 1
    
    def record_r3(self) -> None:
        """记录 R3 拒绝"""
        with self._lock:
            self.total_invocations += 1
            self.r3_rejected += 1
    
    def record_permission_denied(self) -> None:
        """记录权限不足"""
        with self._lock:
            self.permission_denied += 1
    
    def record_capability_exceeded(self) -> None:
        """记录能力超出"""
        with self._lock:
            self.capability_exceeded += 1
    
    def record_ungoverned(self) -> None:
        """记录未受治理的调用"""
        with self._lock:
            self.ungoverned_invocations += 1
    
    def record_bypass(self) -> None:
        """记录显式绕过"""
        with self._lock:
            self.explicit_bypass += 1
    
    def snapshot(self) -> dict[str, int]:
        """获取指标快照"""
        with self._lock:
            return {
                "total_invocations": self.total_invocations,
                "r0_pass": self.r0_pass,
                "r1_pass": self.r1_pass,
                "r2_pending": self.r2_pending,
                "r2_approved": self.r2_approved,
                "r2_rejected": self.r2_rejected,
                "r3_rejected": self.r3_rejected,
                "permission_denied": self.permission_denied,
                "capability_exceeded": self.capability_exceeded,
                "schema_violations": self.schema_violations,
                "ungoverned_invocations": self.ungoverned_invocations,
                "explicit_bypass": self.explicit_bypass,
            }
