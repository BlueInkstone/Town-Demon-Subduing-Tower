"""
客户端模块 - 提供统一的 SDK 使用接口

封装治理链的各个组件，提供简单易用的 API。
"""

from typing import Any, Optional, Callable, Dict
from dataclasses import dataclass
import threading

from .contract import (
    IntentObject,
    ExecutionContext,
    SkillResult,
    SkillDescriptor,
    RiskLevel,
    ErrorCodes,
)
from .executor import Executor, SkillRegistry, PythonFunctionExecutor
from .policy import RiskGate, DefaultRiskGate, CommandTemplateValidator
from .audit import AuditStore, InMemoryAuditStore, FileAuditStore
from .decorator import GovernancePass, GovernanceMetrics, UngovernedInvocationException


@dataclass
class ClientConfig:
    """
    客户端配置
    
    Attributes:
        audit_file: 审计文件路径（设置则使用文件存储，否则内存存储）
        default_mode: 默认执行模式
        default_agent_id: 默认 Agent ID
        metrics_enabled: 是否启用指标收集
        strict_mode: 是否启用严格模式（未注册工具拒绝执行）
    """
    audit_file: Optional[str] = None
    default_mode: str = "NORMAL"
    default_agent_id: Optional[str] = None
    metrics_enabled: bool = True
    strict_mode: bool = True


class TownDemontowerClient:
    """
    Town Demon-Subduing Tower 客户端
    
    封装完整的治理链，提供统一的使用接口。
    
    Usage:
        # 创建客户端
        client = TownDemontowerClient()
        
        # 注册技能
        @client.skill("greet", risk=RiskLevel.R0)
        def greet(name: str) -> str:
            return f"Hello, {name}!"
        
        # 执行技能
        result = client.invoke("greet", {"name": "World"})
        print(result.output)  # Hello, World!
    """
    
    def __init__(self, config: Optional[ClientConfig] = None):
        self._config = config or ClientConfig()
        self._lock = threading.RLock()
        
        # 初始化审计存储
        if self._config.audit_file:
            self._audit_store: AuditStore = FileAuditStore(self._config.audit_file)
        else:
            self._audit_store = InMemoryAuditStore()
        
        # 初始化组件
        self._executor = PythonFunctionExecutor()
        self._skill_registry = SkillRegistry()
        self._risk_gate = DefaultRiskGate()
        self._metrics = GovernanceMetrics() if self._config.metrics_enabled else None
        
        # 函数注册表
        self._functions: Dict[str, Callable] = {}
    
    @property
    def audit_store(self) -> AuditStore:
        """获取审计存储"""
        return self._audit_store
    
    @property
    def metrics(self) -> Optional[GovernanceMetrics]:
        """获取指标收集器"""
        return self._metrics
    
    def skill(
        self,
        name: str,
        risk: RiskLevel = RiskLevel.R1,
        required_permissions: Optional[list[str]] = None,
        required_capabilities: Optional[list[str]] = None,
        command_template: Optional[str] = None,
        requires_plan_approval: bool = False,
    ) -> Callable:
        """
        注册技能函数（装饰器形式）
        
        Args:
            name: 技能名称
            risk: 风险级别
            required_permissions: 所需权限列表
            required_capabilities: 所需能力列表
            command_template: 命令模板（可选）
            requires_plan_approval: 是否需要计划审批
            
        Returns:
            装饰器函数
            
        Usage:
            @client.skill("greet", risk=RiskLevel.R0)
            def greet(name: str) -> str:
                return f"Hello, {name}!"
        """
        def decorator(func: Callable) -> Callable:
            # 创建技能描述符
            descriptor = SkillDescriptor(
                name=name,
                risk=risk,
                required_permissions=required_permissions or [],
                required_capabilities=required_capabilities or [],
                command_template=command_template,
                requires_plan_approval=requires_plan_approval,
            )
            
            # 注册
            with self._lock:
                self._skill_registry.register(descriptor)
                self._executor.register(name, func, descriptor)
                self._functions[name] = func
            
            return func
        
        return decorator
    
    def register_skill(
        self,
        name: str,
        func: Callable,
        descriptor: Optional[SkillDescriptor] = None
    ) -> None:
        """
        注册技能函数（直接形式）
        
        Args:
            name: 技能名称
            func: 要注册的函数
            descriptor: 技能描述符（可选）
        """
        with self._lock:
            if descriptor:
                self._skill_registry.register(descriptor)
                self._executor.register(name, func, descriptor)
            else:
                self._executor.register(name, func)
            self._functions[name] = func
    
    def unregister_skill(self, name: str) -> None:
        """注销技能"""
        with self._lock:
            self._skill_registry.unregister(name)
            self._executor.unregister(name)
            self._functions.pop(name, None)
    
    def invoke(
        self,
        tool: str,
        args: Optional[Dict[str, Any]] = None,
        mode: Optional[str] = None,
        agent_id: Optional[str] = None,
        force: bool = False,
    ) -> SkillResult:
        """
        调用技能（经过完整治理链）
        
        Args:
            tool: 工具/技能名称
            args: 参数字典
            mode: 执行模式（覆盖默认）
            agent_id: Agent ID（覆盖默认）
            force: 是否强制模式
            
        Returns:
            执行结果
        """
        args = args or {}
        
        # 构建意图对象
        intent = IntentObject(
            tool=tool,
            args=args,
            confidence=1.0,
        )
        
        # 构建执行上下文
        context = ExecutionContext(
            mode=mode or self._config.default_mode,
            agent_id=agent_id or self._config.default_agent_id,
            force=force,
        )
        
        return self.execute(intent, context)
    
    def execute(
        self,
        intent: IntentObject,
        context: ExecutionContext
    ) -> SkillResult:
        """
        执行意图对象（经过完整治理链）
        
        治理链流程：
        1. 命令模板验证（如果声明了模板）
        2. 风险闸门评估
        3. 权限检查（R2/R3 需要审批）
        4. 执行
        5. 审计记录
        
        Args:
            intent: 意图对象
            context: 执行上下文
            
        Returns:
            执行结果
        """
        # 获取技能描述符
        descriptor = self._skill_registry.get(intent.tool)
        
        # 1. 命令模板验证
        if descriptor and descriptor.command_template:
            command = intent.args.get("command", "")
            valid, error = CommandTemplateValidator.validate(
                command,
                descriptor.command_template
            )
            if not valid:
                audit_id = self._audit_store.append_auto(
                    action="COMMAND_TEMPLATE_MISMATCH",
                    payload={
                        "tool": intent.tool,
                        "command": command,
                        "error": error,
                        "mode": context.mode,
                    }
                )
                if self._metrics:
                    self._metrics.record_capability_exceeded()
                return SkillResult.fail(
                    error_code=ErrorCodes.COMMAND_TEMPLATE_MISMATCH,
                    error_message=error or "Command template validation failed",
                    audit_id=audit_id,
                )
        
        # 2. 风险闸门评估
        risk_level, approval_ticket = self._risk_gate.evaluate(intent, descriptor, context)
        
        # 记录风险级别到审计
        audit_id = self._audit_store.append_auto(
            action="RISK_EVALUATED",
            payload={
                "tool": intent.tool,
                "risk_level": risk_level.value,
                "mode": context.mode,
                "agent_id": context.agent_id,
            }
        )
        
        # 3. 根据风险级别处理
        if risk_level == RiskLevel.R3:
            # R3: 默认拒绝
            if self._metrics:
                self._metrics.record_r3()
            return SkillResult.fail(
                error_code=ErrorCodes.RISK_REJECTED,
                error_message=f"High risk operation rejected: {intent.tool}",
                audit_id=audit_id,
            )
        
        if risk_level == RiskLevel.R2:
            # R2: 需要审批（简化处理：直接拒绝，实际应等待审批）
            if self._metrics:
                self._metrics.record_r2_pending()
            return SkillResult.fail(
                error_code=ErrorCodes.APPROVAL_REQUIRED,
                error_message=f"Operation requires approval: {intent.tool}",
                audit_id=audit_id,
            )
        
        # 4. R0/R1: 执行
        # 发放治理通行证
        with GovernancePass.scope(audit_id, intent.tool, self._metrics):
            result = self._executor.execute(intent, context)
        
        # 记录执行结果
        self._audit_store.append_auto(
            action="SKILL_INVOKED",
            payload={
                "tool": intent.tool,
                "success": result.success,
                "error_code": result.error_code,
                "mode": context.mode,
                "agent": context.agent_id or "",
                "owner": context.owner_user_id or "",
            }
        )
        
        # 更新指标
        if self._metrics:
            if risk_level == RiskLevel.R0:
                self._metrics.record_r0()
            else:
                self._metrics.record_r1()
            
            if not result.success:
                if result.error_code == ErrorCodes.PERMISSION_DENIED:
                    self._metrics.record_permission_denied()
                elif result.error_code == ErrorCodes.CAPABILITY_EXCEEDED:
                    self._metrics.record_capability_exceeded()
        
        return result
    
    def list_skills(self) -> list[dict[str, Any]]:
        """列出所有已注册的技能"""
        with self._lock:
            return [desc.to_dict() for desc in self._skill_registry.all()]
    
    def get_audit_records(self) -> list[dict[str, Any]]:
        """获取所有审计记录"""
        return [record.to_dict() for record in self._audit_store.all()]
    
    def verify_audit_chain(self) -> bool:
        """验证审计链完整性"""
        return self._audit_store.verify()
    
    def get_metrics(self) -> Optional[dict[str, int]]:
        """获取指标快照"""
        if self._metrics:
            return self._metrics.snapshot()
        return None
