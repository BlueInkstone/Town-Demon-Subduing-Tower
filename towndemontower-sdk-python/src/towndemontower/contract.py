"""
契约层 - 对齐 core/contract/ 模块

定义治理链中的核心数据结构，保持与 Java 版本字段一致。
"""

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Optional
from types import MappingProxyType


class RiskLevel(Enum):
    """风险等级 - 对齐 core RiskLevel.java"""
    R0 = "R0"  # 放行
    R1 = "R1"  # 轻确认
    R2 = "R2"  # 人工审批
    R3 = "R3"  # 默认拒绝（带 --force 也不放行）


class ErrorCodes:
    """错误码 - 对齐 core ErrorCodes.java"""
    PERMISSION_DENIED = "PERMISSION_DENIED"
    RISK_REJECTED = "RISK_REJECTED"
    CAPABILITY_EXCEEDED = "CAPABILITY_EXCEEDED"
    SCHEMA_VIOLATION = "SCHEMA_VIOLATION"
    INTENT_FORMAT_ERROR = "INTENT_FORMAT_ERROR"
    SANDBOX_VIOLATION = "SANDBOX_VIOLATION"
    TAINT_BLOCKED = "TAINT_BLOCKED"
    APPROVAL_REQUIRED = "APPROVAL_REQUIRED"
    APPROVAL_EXPIRED = "APPROVAL_EXPIRED"
    APPROVAL_SELF_DENIED = "APPROVAL_SELF_DENIED"
    MODE_NOT_ALLOWED = "MODE_NOT_ALLOWED"
    INPUT_REJECTED = "INPUT_REJECTED"
    INTERNAL_ERROR = "INTERNAL_ERROR"
    UNGOVERNED_INVOCATION = "UNGOVERNED_INVOCATION"
    COMMAND_TEMPLATE_MISMATCH = "COMMAND_TEMPLATE_MISMATCH"
    PLAN_APPROVAL_REQUIRED = "PLAN_APPROVAL_REQUIRED"
    PLAN_REJECTED = "PLAN_REJECTED"


@dataclass(frozen=True)
class IntentObject:
    """
    意图对象 - 对齐 core IntentObject.java
    
    模型输出的结构化意图，包含工具名、参数、置信度等。
    不可变对象，创建后不可修改。
    """
    tool: str
    args: dict[str, Any] = field(default_factory=dict)
    confidence: float = 1.0
    text: str = ""
    
    def __post_init__(self):
        if not self.tool:
            raise ValueError("tool is required")
        if not 0 <= self.confidence <= 1:
            raise ValueError("confidence must be between 0 and 1")
        # 用 MappingProxyType 冻结 args 字典，不可修改内容
        object.__setattr__(self, 'args', MappingProxyType(self.args))
    
    def to_dict(self) -> dict[str, Any]:
        """转换为 JSON 兼容字典"""
        return {
            "tool": self.tool,
            "args": self.args,
            "confidence": self.confidence,
            "text": self.text,
        }
    
    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "IntentObject":
        """从字典创建意图对象"""
        return cls(
            tool=data.get("tool", ""),
            args=data.get("args", {}),
            confidence=data.get("confidence", 1.0),
            text=data.get("text", ""),
        )


@dataclass(frozen=True)
class SkillDescriptor:
    """
    技能描述符 - 对齐 core SkillDescriptor.java
    
    描述一个工具的能力、风险、权限需求等元数据。
    """
    name: str
    risk: RiskLevel = RiskLevel.R1
    required_permissions: list[str] = field(default_factory=list)
    required_capabilities: list[str] = field(default_factory=list)
    required_args: list[str] = field(default_factory=list)
    command_template: Optional[str] = None
    requires_plan_approval: bool = False
    
    def __post_init__(self):
        # 冻结列表
        object.__setattr__(self, 'required_permissions', list(self.required_permissions))
        object.__setattr__(self, 'required_capabilities', list(self.required_capabilities))
        object.__setattr__(self, 'required_args', list(self.required_args))
    
    def to_dict(self) -> dict[str, Any]:
        """转换为 JSON 兼容字典"""
        return {
            "name": self.name,
            "risk": self.risk.value,
            "required_permissions": self.required_permissions,
            "required_capabilities": self.required_capabilities,
            "required_args": self.required_args,
            "command_template": self.command_template,
            "requires_plan_approval": self.requires_plan_approval,
        }
    
    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "SkillDescriptor":
        """从字典创建技能描述符"""
        return cls(
            name=data.get("name", ""),
            risk=RiskLevel(data.get("risk", "R1")),
            required_permissions=data.get("required_permissions", []),
            required_capabilities=data.get("required_capabilities", []),
            required_args=data.get("required_args", []),
            command_template=data.get("command_template"),
            requires_plan_approval=data.get("requires_plan_approval", False),
        )


@dataclass(frozen=True)
class ExecutionContext:
    """
    执行上下文 - 对齐 core ExecutionContext.java
    
    包含执行时的模式、Agent 身份、调用者信息等。
    """
    mode: str = "NORMAL"
    agent_id: Optional[str] = None
    owner_user_id: Optional[str] = None
    force: bool = False
    
    def to_dict(self) -> dict[str, Any]:
        """转换为 JSON 兼容字典"""
        return {
            "mode": self.mode,
            "agent_id": self.agent_id,
            "owner_user_id": self.owner_user_id,
            "force": self.force,
        }
    
    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "ExecutionContext":
        """从字典创建执行上下文"""
        return cls(
            mode=data.get("mode", "NORMAL"),
            agent_id=data.get("agent_id"),
            owner_user_id=data.get("owner_user_id"),
            force=data.get("force", False),
        )


@dataclass(frozen=True)
class ApprovalTicket:
    """
    审批票据 - 对齐 core ApprovalTicket.java
    
    R2 风险级别需要人工审批时生成的票据。
    """
    ticket_id: str
    tool: str
    risk_level: RiskLevel
    reason: str = ""
    
    def to_dict(self) -> dict[str, Any]:
        """转换为 JSON 兼容字典"""
        return {
            "ticket_id": self.ticket_id,
            "tool": self.tool,
            "risk_level": self.risk_level.value,
            "reason": self.reason,
        }
    
    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "ApprovalTicket":
        """从字典创建审批票据"""
        return cls(
            ticket_id=data.get("ticket_id", ""),
            tool=data.get("tool", ""),
            risk_level=RiskLevel(data.get("risk_level", "R2")),
            reason=data.get("reason", ""),
        )


@dataclass(frozen=True)
class SkillResult:
    """
    技能执行结果 - 对齐 core SkillResult.java
    
    包含执行成功/失败状态、输出、错误码等。
    """
    success: bool
    output: Any = None
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    audit_id: Optional[str] = None
    
    def to_dict(self) -> dict[str, Any]:
        """转换为 JSON 兼容字典"""
        return {
            "success": self.success,
            "output": self.output,
            "error_code": self.error_code,
            "error_message": self.error_message,
            "audit_id": self.audit_id,
        }
    
    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "SkillResult":
        """从字典创建技能结果"""
        return cls(
            success=data.get("success", False),
            output=data.get("output"),
            error_code=data.get("error_code"),
            error_message=data.get("error_message"),
            audit_id=data.get("audit_id"),
        )
    
    @classmethod
    def ok(cls, output: Any, audit_id: Optional[str] = None) -> "SkillResult":
        """创建成功结果"""
        return cls(success=True, output=output, audit_id=audit_id)
    
    @classmethod
    def fail(cls, error_code: str, error_message: str, audit_id: Optional[str] = None) -> "SkillResult":
        """创建失败结果"""
        return cls(
            success=False,
            error_code=error_code,
            error_message=error_message,
            audit_id=audit_id,
        )
