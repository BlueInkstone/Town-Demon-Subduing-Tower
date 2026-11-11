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
from .codes import (
    CANONICAL_CODES,
    ALIASES,
    UNMAPPED_REQUIRES_REVIEW,
    SUCCESS_CODE,
    STAT_PENDING_APPROVAL,
    STAT_R3_DENIED,
    canonical_code,
    is_canonical,
    counts_as_denied,
)
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
    # Cross-language error codes (方向 D / 定稿口径)
    "CANONICAL_CODES",
    "ALIASES",
    "UNMAPPED_REQUIRES_REVIEW",
    "SUCCESS_CODE",
    "STAT_PENDING_APPROVAL",
    "STAT_R3_DENIED",
    "canonical_code",
    "is_canonical",
    "counts_as_denied",
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
