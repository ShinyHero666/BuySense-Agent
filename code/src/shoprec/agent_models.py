from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any


class AgentState(str, Enum):
    DISCOVER = "discover"
    CLARIFY = "clarify"
    SEARCH = "search"
    COMPARE = "compare"
    CONFIRM = "confirm"
    SAVED = "saved"
    COMPLETE = "complete"
    DEGRADED = "degraded"


@dataclass
class BuyerRequirements:
    budget_max: int | None = None
    storage_min_gb: int | None = None
    battery_min: int | None = None
    warranty_required: bool | None = None
    accepts_repair: bool | None = None
    city: str | None = None
    preferred_models: list[str] = field(default_factory=list)
    use_cases: list[str] = field(default_factory=list)
    hard_fields: set[str] = field(default_factory=set)
    assumptions: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["hard_fields"] = sorted(self.hard_fields)
        return result


@dataclass(frozen=True)
class Evidence:
    source_type: str
    source_id: str
    field: str
    value: Any
    version: str = "catalog-v1"

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class AgentTraceEvent:
    event_id: str
    run_id: str
    turn_id: str
    event_type: str
    state: str
    detail: dict[str, Any]
    occurred_at: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class AgentSession:
    session_id: str
    user_id: str
    state: AgentState = AgentState.DISCOVER
    requirements: BuyerRequirements = field(default_factory=BuyerRequirements)
    turn_count: int = 0
    clarification_count: int = 0
    tool_call_count: int = 0
    model_call_count: int = 0
    last_result_ids: list[str] = field(default_factory=list)
    pending_shortlist: list[str] = field(default_factory=list)
    pending_relaxations: list[dict[str, Any]] = field(default_factory=list)
    saved_shortlist: list[str] = field(default_factory=list)
    messages: list[dict[str, str]] = field(default_factory=list)
    evidence: list[Evidence] = field(default_factory=list)
    trace: list[AgentTraceEvent] = field(default_factory=list)
    model_degraded: bool = False
    security_flags: list[str] = field(default_factory=list)

    def to_public_dict(self) -> dict[str, Any]:
        return {
            "session_id": self.session_id,
            "user_id": self.user_id,
            "state": self.state.value,
            "requirements": self.requirements.to_dict(),
            "turn_count": self.turn_count,
            "clarification_count": self.clarification_count,
            "tool_call_count": self.tool_call_count,
            "model_call_count": self.model_call_count,
            "last_result_ids": list(self.last_result_ids),
            "pending_shortlist": list(self.pending_shortlist),
            "pending_relaxations": list(self.pending_relaxations),
            "saved_shortlist": list(self.saved_shortlist),
            "evidence": [item.to_dict() for item in self.evidence],
            "trace": [item.to_dict() for item in self.trace],
            "model_degraded": self.model_degraded,
            "security_flags": list(self.security_flags),
        }


@dataclass(frozen=True)
class AgentReply:
    session_id: str
    run_id: str
    state: str
    message: str
    requires_confirmation: bool
    items: tuple[dict[str, Any], ...] = ()
    citations: tuple[dict[str, Any], ...] = ()
    tool: dict[str, Any] | None = None
    model_degraded: bool = False
    metrics: dict[str, Any] = field(default_factory=dict)
    trace: tuple[dict[str, Any], ...] = ()

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["items"] = list(self.items)
        result["citations"] = list(self.citations)
        result["trace"] = list(self.trace)
        return result
