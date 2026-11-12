"""
契约层 - 对齐 core/contract/ 模块

定义治理链中的核心数据结构，保持与 Java 版本字段一致。
"""

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Optional
from types import MappingProxyType

from .codes import SUCCESS_CODE, canonical_code


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
    技能执行结果 - 对齐已定稿的 contract/ 中 $defs/SkillResult。

    wire 字段集固定为 success / code / message / output / approvalTicketId（camelCase），
    见 :meth:`to_wire`。下面三组 snake_case 字段是 SDK 本地扩展，保留以兼容既有调用方：
      - error_code / error_message：code / message 的旧名
      - audit_id：语言本地扩展，schema 明确禁止进入 wire
    """
    success: bool
    output: Any = None
    # 兼容字段（既有调用方与 118 项测试仍在用，不改变语义）
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    audit_id: Optional[str] = None
    # 规范字段（方向 D 定稿；内部用 snake_case，wire 输出 camelCase）
    code: Optional[str] = None
    message: Optional[str] = None
    approval_ticket_id: Optional[str] = None

    def __post_init__(self):
        # 规范字段缺省时从兼容字段推导：成功固定 OK，失败把别名归一为规范码
        if self.code is None:
            object.__setattr__(
                self, "code",
                SUCCESS_CODE if self.success else canonical_code(self.error_code),
            )
        if self.message is None:
            object.__setattr__(self, "message", self.error_message)

    def to_dict(self) -> dict[str, Any]:
        """SDK 本地字典（snake_case，保持既有行为不变）。跨语言传输请用 to_wire()。"""
        return {
            "success": self.success,
            "output": self.output,
            "error_code": self.error_code,
            "error_message": self.error_message,
            "audit_id": self.audit_id,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "SkillResult":
        """从本地字典创建（保持既有行为不变）。"""
        return cls(
            success=data.get("success", False),
            output=data.get("output"),
            error_code=data.get("error_code"),
            error_message=data.get("error_message"),
            audit_id=data.get("audit_id"),
        )

    def to_wire(self) -> dict[str, Any]:
        """输出统一 wire：恰好 5 个 camelCase 字段，缺失项显式为 None，不含本地扩展。"""
        return {
            "success": self.success,
            "code": canonical_code(self.code) if self.code is not None else None,
            "message": self.message,
            "output": self.output,
            "approvalTicketId": self.approval_ticket_id,
        }

    @classmethod
    def from_wire(cls, data: dict[str, Any]) -> "SkillResult":
        """从统一 wire 还原；只认 5 个规范字段，同时补齐本地兼容字段。"""
        success = bool(data.get("success", False))
        code = data.get("code")
        message = data.get("message")
        return cls(
            success=success,
            output=data.get("output"),
            error_code=code if not success else None,
            error_message=message,
            # auditId 是语言本地扩展，wire 里没有，保持 None
            code=code,
            message=message,
            approval_ticket_id=data.get("approvalTicketId"),
        )

    @classmethod
    def ok(cls, output: Any, audit_id: Optional[str] = None) -> "SkillResult":
        """创建成功结果；规范码固定 OK。"""
        return cls(success=True, output=output, audit_id=audit_id, code=SUCCESS_CODE)

    @classmethod
    def fail(
        cls,
        error_code: str,
        error_message: str,
        audit_id: Optional[str] = None,
        approval_ticket_id: Optional[str] = None,
    ) -> "SkillResult":
        """创建失败结果；error_code 保留输入别名，code/wire 输出用规范码。"""
        return cls(
            success=False,
            error_code=error_code,
            error_message=error_message,
            audit_id=audit_id,
            code=canonical_code(error_code),
            message=error_message,
            approval_ticket_id=approval_ticket_id,
        )
