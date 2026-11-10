"""
策略模块 - 对齐 core/policy/ 模块

定义风险闸门策略。
"""

from abc import ABC, abstractmethod
from typing import Optional

from .contract import (
    IntentObject,
    ExecutionContext,
    RiskLevel,
    SkillDescriptor,
    ApprovalTicket,
)


class RiskGate(ABC):
    """
    风险闸门协议 - 对齐 core RiskGate.java
    
    评估意图对象的风险级别，决定是否需要审批。
    """
    
    @abstractmethod
    def evaluate(
        self,
        intent: IntentObject,
        descriptor: Optional[SkillDescriptor],
        context: ExecutionContext
    ) -> tuple[RiskLevel, Optional[ApprovalTicket]]:
        """
        评估风险
        
        Args:
            intent: 要评估的意图
            descriptor: 技能描述符（可选）
            context: 执行上下文
            
        Returns:
            (风险级别, 审批票据[R2/R3时非空])
        """
        pass


class DefaultRiskGate(RiskGate):
    """
    默认风险闸门 - 对齐 core DefaultRiskGate.java
    
    风险分级规则：
    - R0: 放行（只读操作）
    - R1: 轻确认（低风险写操作）
    - R2: 人工审批（中风险操作）
    - R3: 默认拒绝（高风险操作，带 --force 也不放行）
    """
    
    # 只读操作前缀
    READONLY_PREFIXES = {"get", "list", "query", "search", "read", "view", "show"}
    
    # 高风险操作关键词
    HIGH_RISK_KEYWORDS = {"delete", "destroy", "drop", "truncate", "rm", "format", "reset"}
    
    def evaluate(
        self,
        intent: IntentObject,
        descriptor: Optional[SkillDescriptor],
        context: ExecutionContext
    ) -> tuple[RiskLevel, Optional[ApprovalTicket]]:
        """
        评估风险级别
        
        优先级：
        1. 如果描述符明确指定了风险级别，使用该级别
        2. 如果工具名匹配只读前缀，R0
        3. 如果工具名匹配高风险关键词，R3
        4. 默认 R1
        """
        # 1. 描述符优先级最高
        if descriptor:
            risk = descriptor.risk
            ticket = None
            
            if risk == RiskLevel.R2:
                ticket = ApprovalTicket(
                    ticket_id=self._generate_ticket_id(),
                    tool=intent.tool,
                    risk_level=risk,
                    reason="Medium risk operation requires approval",
                )
            elif risk == RiskLevel.R3:
                ticket = ApprovalTicket(
                    ticket_id=self._generate_ticket_id(),
                    tool=intent.tool,
                    risk_level=risk,
                    reason="High risk operation requires explicit approval",
                )
            
            return risk, ticket
        
        # 2. 只读操作 R0
        tool_lower = intent.tool.lower()
        if any(tool_lower.startswith(prefix) for prefix in self.READONLY_PREFIXES):
            return RiskLevel.R0, None
        
        # 3. 高风险操作 R3
        if any(keyword in tool_lower for keyword in self.HIGH_RISK_KEYWORDS):
            ticket = ApprovalTicket(
                ticket_id=self._generate_ticket_id(),
                tool=intent.tool,
                risk_level=RiskLevel.R3,
                reason=f"High risk keyword detected in tool: {intent.tool}",
            )
            return RiskLevel.R3, ticket
        
        # 4. 默认 R1
        return RiskLevel.R1, None
    
    def _generate_ticket_id(self) -> str:
        """生成审批票据 ID"""
        import uuid
        return f"TKT-{uuid.uuid4().hex[:12]}"


class CommandTemplateValidator:
    """
    命令模板验证器 - 对齐 core CommandTemplate.java

    验证命令是否符合声明的模板形状。

    模板语法：
      - 字面量词：必须精确匹配
      - `{name}`：单值槽位（允许一个词，不允许 `-` 开头）
      - `{name*}`：尾部参数区（允许多个词，允许 `-` 开头，仍禁控制符）
    """

    # 禁止的控制字符 (移除了 \t\n\r 因为 split() 已经处理了空白，且通常 shell 注入更关注 ;|&$ 等)
    FORBIDDEN_CHARS = set(";|&$`<>(){}!'\"\\")

    @classmethod
    def validate(
        cls,
        command: str,
        template: str
    ) -> tuple[bool, Optional[str]]:
        """
        验证命令是否符合模板
        
        Args:
            command: 要验证的命令
            template: 声明的模板
            
        Returns:
            (是否通过, 错误信息)
        """
        if not command:
            return False, "Command is empty"
        
        if len(command) > 2048:
            return False, "Command too long (max 2048)"
        
        if not template:
            return True, None
        
        template_parts = template.split()
        command_parts = command.split()

        # 检测尾部通配符
        has_trailing_wildcard = (
            len(template_parts) > 0 and
            template_parts[-1].startswith("{") and
            template_parts[-1].endswith("*}")
        )

        # 字面量+单值槽位的最大长度
        fixed_len = len(template_parts) - 1 if has_trailing_wildcard else len(template_parts)

        # 先检查多余参数（非通配符模式下）
        if not has_trailing_wildcard and len(command_parts) > len(template_parts):
            return False, f"Extra arguments: {' '.join(command_parts[len(template_parts):])}"

        # 再检查缺少参数
        if has_trailing_wildcard:
            if len(command_parts) < fixed_len:
                return False, f"Missing required argument: {template_parts[len(command_parts)]}"
        else:
            if len(command_parts) < len(template_parts):
                return False, f"Missing required argument: {template_parts[len(command_parts)]}"

        # 逐词匹配
        for i in range(fixed_len):
            t_part = template_parts[i]
            c_part = command_parts[i]

            if t_part.startswith("{") and t_part.endswith("}") and not t_part.endswith("*}"):
                # 单值槽位：禁止控制字符，禁止 `-` 开头
                if cls._has_forbidden_chars(c_part):
                    return False, f"Parameter contains forbidden characters: {c_part}"
                if c_part.startswith("-"):
                    return False, f"Single-value slot cannot start with -: {c_part}"
            else:
                # 字面量：精确匹配
                if t_part != c_part:
                    return False, f"Expected '{t_part}' but got '{c_part}'"

        # 尾部通配符槽：只检查禁止字符，允许 `-` 开头
        if has_trailing_wildcard:
            for i in range(fixed_len, len(command_parts)):
                if cls._has_forbidden_chars(command_parts[i]):
                    return False, f"Parameter contains forbidden characters: {command_parts[i]}"

        return True, None

    @classmethod
    def _has_forbidden_chars(cls, value: str) -> bool:
        """检查是否包含禁止字符"""
        return any(c in cls.FORBIDDEN_CHARS for c in value)
