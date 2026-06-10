from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime
from typing import Any


@dataclass(frozen=True)
class Product:
    product_id: str
    title: str
    category: str
    brand: str
    price: float
    condition: str
    city: str
    seller_id: str
    stock: int
    publish_time: datetime
    tags: tuple[str, ...] = ()
    quality_score: float = 0.5
    historical_ctr: float = 0.02
    historical_cvr: float = 0.01
    service_mode: str = "platform_inspected"
    inspection_status: str = "passed"
    inspection_grade: str = "B"
    warranty_days: int = 365
    return_window_days: int = 7
    battery_health: int | None = None
    inspection_findings: tuple[str, ...] = ()
    model_name: str = ""
    storage_gb: int | None = None
    color: str = ""
    repair_history: tuple[str, ...] = ()


@dataclass
class UserProfile:
    user_id: str
    city: str
    category_interests: dict[str, float] = field(default_factory=dict)
    recent_clicks: list[str] = field(default_factory=list)
    purchased: set[str] = field(default_factory=set)
    disliked_products: set[str] = field(default_factory=set)


@dataclass(frozen=True)
class SearchFilters:
    categories: tuple[str, ...] = ()
    brands: tuple[str, ...] = ()
    city: str | None = None
    min_price: float | None = None
    max_price: float | None = None
    conditions: tuple[str, ...] = ()
    service_modes: tuple[str, ...] = ()
    inspection_grades: tuple[str, ...] = ()
    min_battery_health: int | None = None
    warranty_required: bool = False


@dataclass(frozen=True)
class SearchRequest:
    query: str
    user_id: str = "guest"
    filters: SearchFilters = field(default_factory=SearchFilters)
    sort: str = "relevance"
    page: int = 1
    page_size: int = 10
    request_id: str = ""
    experiment_token: str = ""
    debug: bool = True


@dataclass(frozen=True)
class RecommendRequest:
    user_id: str
    scene: str = "homepage"
    size: int = 10
    request_id: str = ""
    experiment_token: str = ""
    exclude_seen: bool = True
    debug: bool = True


@dataclass(frozen=True)
class DeviceValuationRequest:
    brand: str
    model: str
    category: str = "手机"
    storage_gb: int = 128
    age_months: int = 24
    condition_grade: str = "B"
    battery_health: int | None = None
    functional_issues: tuple[str, ...] = ()
    inspection_method: str = "mail"


@dataclass
class Candidate:
    product: Product
    recall_sources: set[str] = field(default_factory=set)
    features: dict[str, float] = field(default_factory=dict)
    scores: dict[str, float] = field(default_factory=dict)
    pool: str | None = None
    before_position: int | None = None
    after_position: int | None = None
    rerank_reasons: list[str] = field(default_factory=list)

    def explain(self) -> dict[str, Any]:
        return {
            "product_id": self.product.product_id,
            "title": self.product.title,
            "category": self.product.category,
            "brand": self.product.brand,
            "price": self.product.price,
            "condition": self.product.condition,
            "city": self.product.city,
            "seller_id": self.product.seller_id,
            "stock": self.product.stock,
            "publish_time": self.product.publish_time.isoformat(),
            "model_name": self.product.model_name,
            "storage_gb": self.product.storage_gb,
            "color": self.product.color,
            "service": {
                "mode": self.product.service_mode,
                "inspection_status": self.product.inspection_status,
                "inspection_grade": self.product.inspection_grade,
                "warranty_days": self.product.warranty_days,
                "return_window_days": self.product.return_window_days,
                "battery_health": self.product.battery_health,
                "findings": list(self.product.inspection_findings),
                "repair_history": list(self.product.repair_history),
            },
            "recall_sources": sorted(self.recall_sources),
            "pool": self.pool,
            "features": {k: round(v, 6) for k, v in self.features.items()},
            "scores": {k: round(v, 6) for k, v in self.scores.items()},
            "rerank": {
                "before_position": self.before_position,
                "after_position": self.after_position,
                "reasons": self.rerank_reasons,
            },
        }


@dataclass
class QueryContext:
    raw_query: str
    normalized_query: str
    rewritten_query: str
    terms: set[str]
    category_intents: list[str]
    brand_intents: list[str]
    is_broad: bool
    blocked: bool
    reasons: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["terms"] = sorted(self.terms)
        return result


@dataclass
class SearchResponse:
    request_id: str
    total: int
    items: list[dict[str, Any]]
    facets: dict[str, dict[str, int]]
    query_info: dict[str, Any]
    experiments: dict[str, str]
    trace: list[dict[str, Any]]
    filter_reasons: dict[str, int]


@dataclass
class RecommendResponse:
    request_id: str
    items: list[dict[str, Any]]
    experiments: dict[str, str]
    trace: list[dict[str, Any]]
    filter_reasons: dict[str, int]
    pool_counts: dict[str, int]
    pool_targets: dict[str, int]


@dataclass(frozen=True)
class DeviceValuationResponse:
    request_id: str
    quote_type: str
    reference_source: str
    currency: str
    estimated_low: int
    estimated_high: int
    estimated_mid: int
    final_price_requires_inspection: bool
    factors: dict[str, float]
    risk_flags: list[str]
    next_steps: list[str]
