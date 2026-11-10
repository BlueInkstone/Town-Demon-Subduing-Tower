"""
执行器模块 - 对齐 core/executor/ 模块

定义执行器协议和技能注册表。
"""

from abc import ABC, abstractmethod
from typing import Callable, Any, Optional
import threading

from .contract import (
    IntentObject,
    ExecutionContext,
    SkillResult,
    SkillDescriptor,
    ErrorCodes,
)


class Executor(ABC):
    """
    执行器协议 - 对齐 core Executor.java
    
    定义执行意图对象的标准接口。
    """
    
    @abstractmethod
    def execute(
        self,
        intent: IntentObject,
        context: ExecutionContext
    ) -> SkillResult:
        """
        执行意图对象
        
        Args:
            intent: 要执行的意图
            context: 执行上下文
            
        Returns:
            执行结果
        """
        pass


class SkillInvoker(ABC):
    """
    技能调用器协议 - 对齐 core SkillInvoker.java
    
    用于桥接到具体实现（如 Python 函数、远程调用等）。
    """
    
    @abstractmethod
    def invoke(self, intent: IntentObject) -> Any:
        """
        调用技能
        
        Args:
            intent: 要调用的意图
            
        Returns:
            调用结果
        """
        pass


class CapabilityException(Exception):
    """能力超出范围异常"""
    pass


class IntentFormatException(Exception):
    """意图格式异常"""
    pass


class SchemaValidationException(Exception):
    """Schema 验证异常"""
    pass


class PermissionDeniedException(Exception):
    """权限不足异常"""
    pass


class RejectAllExecutor(Executor):
    """
    全拒绝执行器 - 对齐 core RejectAllExecutor.java
    
    当治理关闭时，拒绝所有执行请求。
    """
    
    def __init__(self, audit_store: Optional[Any] = None):
        self._audit_store = audit_store
    
    def execute(
        self,
        intent: IntentObject,
        context: ExecutionContext
    ) -> SkillResult:
        """拒绝所有执行"""
        error_msg = "Governance is disabled, all executions are rejected"
        audit_id = None
        
        if self._audit_store:
            audit_id = self._audit_store.append_auto(
                action="SKILL_REJECTED",
                payload={
                    "tool": intent.tool,
                    "reason": "GOVERNANCE_DISABLED",
                    "mode": context.mode,
                }
            )
        
        return SkillResult.fail(
            error_code=ErrorCodes.INTERNAL_ERROR,
            error_message=error_msg,
            audit_id=audit_id,
        )


class PythonFunctionExecutor(Executor):
    """
    Python 函数执行器
    
    将 IntentObject 路由到注册的 Python 函数。
    """
    
    def __init__(self):
        self._functions: dict[str, Callable] = {}
        self._descriptors: dict[str, SkillDescriptor] = {}
        self._lock = threading.RLock()
    
    def register(
        self,
        name: str,
        func: Callable,
        descriptor: Optional[SkillDescriptor] = None
    ) -> None:
        """
        注册技能函数
        
        Args:
            name: 技能名称
            func: 要注册的函数
            descriptor: 技能描述符（可选）
        """
        with self._lock:
            self._functions[name] = func
            if descriptor:
                self._descriptors[name] = descriptor
    
    def unregister(self, name: str) -> None:
        """注销技能"""
        with self._lock:
            self._functions.pop(name, None)
            self._descriptors.pop(name, None)
    
    def get_descriptor(self, name: str) -> Optional[SkillDescriptor]:
        """获取技能描述符"""
        with self._lock:
            return self._descriptors.get(name)
    
    def list_skills(self) -> list[str]:
        """列出所有已注册的技能"""
        with self._lock:
            return list(self._functions.keys())
    
    def execute(
        self,
        intent: IntentObject,
        context: ExecutionContext
    ) -> SkillResult:
        """执行意图对象"""
        with self._lock:
            func = self._functions.get(intent.tool)
            if not func:
                return SkillResult.fail(
                    error_code=ErrorCodes.CAPABILITY_EXCEEDED,
                    error_message=f"Unknown tool: {intent.tool}",
                )
        
        try:
            # 调用函数，传入意图参数
            result = func(**intent.args)
            return SkillResult.ok(output=result)
        except Exception as e:
            return SkillResult.fail(
                error_code=ErrorCodes.INTERNAL_ERROR,
                error_message=str(e),
            )


class SkillRegistry:
    """
    技能注册表 - 对齐 core SkillRegistry.java
    
    管理所有已注册的技能描述符。
    """
    
    def __init__(self):
        self._skills: dict[str, SkillDescriptor] = {}
        self._lock = threading.RLock()
    
    def register(self, descriptor: SkillDescriptor) -> None:
        """注册技能描述符"""
        with self._lock:
            self._skills[descriptor.name] = descriptor
    
    def unregister(self, name: str) -> None:
        """注销技能"""
        with self._lock:
            self._skills.pop(name, None)
    
    def get(self, name: str) -> Optional[SkillDescriptor]:
        """获取技能描述符"""
        with self._lock:
            return self._skills.get(name)
    
    def names(self) -> list[str]:
        """获取所有技能名称"""
        with self._lock:
            return list(self._skills.keys())
    
    def all(self) -> list[SkillDescriptor]:
        """获取所有技能描述符"""
        with self._lock:
            return list(self._skills.values())
    
    def contains(self, name: str) -> bool:
        """检查技能是否已注册"""
        with self._lock:
            return name in self._skills
