"""
Town Demon-Subduing Tower - Python SDK

对齐 core 契约层，提供零 token 的治理接入能力。
"""

from .contract import (
    IntentObject,
    RiskLevel,
    SkillDescriptor,
    ExecutionContext,
    SkillResult,
    ErrorCodes,
    ApprovalTicket,
)
from .executor import Executor, SkillRegistry
from .policy import RiskGate, DefaultRiskGate
from .audit import AuditStore, AuditRecord
from .decorator import GovernancePass
from .client import TownDemontowerClient
from .rest import (
    GovernanceRestClient,
    RestClientConfig,
    GovernanceApiError,
    ApprovalStatus,
    HttpResponse,
    MetricsReport,
    IntegrityReport,
    AnchorReplicaView,
    ApprovalView,
    ApprovalListData,
    ApiEnvelope,
)

__version__ = "2.0.0"
__all__ = [
    # Contract
    "IntentObject",
    "RiskLevel",
    "SkillDescriptor",
    "ExecutionContext",
    "SkillResult",
    "ErrorCodes",
    "ApprovalTicket",
    # Executor
    "Executor",
    "SkillRegistry",
    # Policy
    "RiskGate",
    "DefaultRiskGate",
    # Audit
    "AuditStore",
    "AuditRecord",
    # Decorator
    "GovernancePass",
    # Client
    "TownDemontowerClient",
    # REST
    "GovernanceRestClient",
    "RestClientConfig",
    "GovernanceApiError",
    "ApprovalStatus",
    "HttpResponse",
    "MetricsReport",
    "IntegrityReport",
    "AnchorReplicaView",
    "ApprovalView",
    "ApprovalListData",
    "ApiEnvelope",
]
