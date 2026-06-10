from __future__ import annotations

import math
import os
from dataclasses import asdict
from pathlib import Path
from typing import Any

from .experiments import ExperimentManager
from .models import (
    DeviceValuationRequest,
    RecommendRequest,
    SearchFilters,
    SearchRequest,
    UserProfile,
)
from .recommend import RecommendationEngine
from .rankers import ModelReranker
from .runtime import Clock, IdGenerator, SystemClock, UUIDIdGenerator
from .sample_data import demo_products, demo_users
from .search import SearchEngine
from .state import InMemoryExposureStore, InMemoryUserStore
from .valuation import DeviceValuationEngine
from .validation import (
    ValidationError,
    boolean_value,
    integer_value,
    reject_unknown_fields,
    require_mapping,
    string_value,
)


class CommerceDiscoveryService:
    """Application facade shared by CLI and HTTP adapters."""

    def __init__(
        self,
        products,
        users: dict[str, UserProfile],
        experiments: ExperimentManager | None = None,
        clock: Clock | None = None,
        id_generator: IdGenerator | None = None,
        exposure_store: InMemoryExposureStore | None = None,
        model_reranker: ModelReranker | None = None,
    ) -> None:
        self.clock = clock or SystemClock()
        self.id_generator = id_generator or UUIDIdGenerator()
        self.products = products
        self.experiments = experiments or ExperimentManager.default()
        self.user_store = InMemoryUserStore(users)
        self.exposure_store = exposure_store or InMemoryExposureStore(self.clock)
        self.product_ids = {product.product_id for product in products}
        self.valuation_engine = DeviceValuationEngine(self.id_generator)
        self.search_engine = SearchEngine(
            products,
            self.experiments,
            clock=self.clock,
            id_generator=self.id_generator,
        )
        self.recommendation_engine = RecommendationEngine(
            products,
            self.experiments,
            self.exposure_store,
            clock=self.clock,
            id_generator=self.id_generator,
            model_reranker=model_reranker,
        )

    def get_user(self, user_id: str) -> UserProfile:
        return self.user_store.get(user_id)

    @staticmethod
    def _string_tuple(filters: dict[str, Any], field: str) -> tuple[str, ...]:
        value = filters.get(field, ())
        if value is None:
            return ()
        if not isinstance(value, (list, tuple)):
            raise ValidationError(f"filters.{field}", "must be an array of strings")
        if not all(isinstance(item, str) and item.strip() for item in value):
            raise ValidationError(
                f"filters.{field}", "must contain only non-empty strings"
            )
        return tuple(item.strip() for item in value)

    @staticmethod
    def _optional_number(filters: dict[str, Any], field: str) -> float | None:
        value = filters.get(field)
        if value is None:
            return None
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValidationError(f"filters.{field}", "must be a number")
        number = float(value)
        if not math.isfinite(number):
            raise ValidationError(f"filters.{field}", "must be a finite number")
        if number < 0:
            raise ValidationError(f"filters.{field}", "must not be negative")
        return number

    @classmethod
    def _search_filters(cls, payload: dict[str, Any]) -> SearchFilters:
        raw = payload.get("filters", {})
        if raw is None:
            raw = {}
        if not isinstance(raw, dict):
            raise ValidationError("filters", "must be an object")
        allowed = {
            "categories",
            "brands",
            "city",
            "min_price",
            "max_price",
            "conditions",
            "service_modes",
            "inspection_grades",
            "min_battery_health",
            "warranty_required",
        }
        unknown = set(raw) - allowed
        if unknown:
            raise ValidationError(
                "filters", "unknown fields: " + ", ".join(sorted(unknown))
            )
        city = raw.get("city")
        if city is not None and not isinstance(city, str):
            raise ValidationError("filters.city", "must be a string or null")
        minimum = cls._optional_number(raw, "min_price")
        maximum = cls._optional_number(raw, "max_price")
        if minimum is not None and maximum is not None and minimum > maximum:
            raise ValidationError(
                "filters", "min_price must be less than or equal to max_price"
            )
        service_modes = cls._string_tuple(raw, "service_modes")
        allowed_modes = {
            "platform_inspected",
            "recycle_inventory",
            "consignment",
            "store_inventory",
        }
        unknown_modes = set(service_modes) - allowed_modes
        if unknown_modes:
            raise ValidationError(
                "filters.service_modes",
                "unknown values: " + ", ".join(sorted(unknown_modes)),
            )
        inspection_grades = cls._string_tuple(raw, "inspection_grades")
        unknown_grades = set(inspection_grades) - {"A", "B", "C", "D"}
        if unknown_grades:
            raise ValidationError(
                "filters.inspection_grades",
                "unknown values: " + ", ".join(sorted(unknown_grades)),
            )
        battery = raw.get("min_battery_health")
        if battery is not None:
            if isinstance(battery, bool) or not isinstance(battery, int):
                raise ValidationError(
                    "filters.min_battery_health", "must be an integer or null"
                )
            if battery < 0 or battery > 100:
                raise ValidationError(
                    "filters.min_battery_health", "must be between 0 and 100"
                )
        warranty_required = raw.get("warranty_required", False)
        if not isinstance(warranty_required, bool):
            raise ValidationError("filters.warranty_required", "must be a boolean")
        return SearchFilters(
            categories=cls._string_tuple(raw, "categories"),
            brands=cls._string_tuple(raw, "brands"),
            city=city.strip() if isinstance(city, str) and city.strip() else None,
            min_price=minimum,
            max_price=maximum,
            conditions=cls._string_tuple(raw, "conditions"),
            service_modes=service_modes,
            inspection_grades=inspection_grades,
            min_battery_health=battery,
            warranty_required=warranty_required,
        )

    def search(self, payload: dict[str, Any]) -> dict[str, Any]:
        payload = require_mapping(payload)
        reject_unknown_fields(
            payload,
            {
                "query",
                "user_id",
                "filters",
                "sort",
                "page",
                "page_size",
                "request_id",
                "experiment_token",
                "debug",
            },
        )
        sort = string_value(payload, "sort", "relevance", max_length=32)
        allowed_sorts = {"relevance", "price_asc", "price_desc", "newest"}
        if sort not in allowed_sorts:
            raise ValidationError(
                "sort", "must be one of: " + ", ".join(sorted(allowed_sorts))
            )
        request = SearchRequest(
            query=string_value(payload, "query", required=True, max_length=128),
            user_id=string_value(payload, "user_id", "guest", required=True, max_length=64),
            filters=self._search_filters(payload),
            sort=sort,
            page=integer_value(payload, "page", 1, minimum=1, maximum=100),
            page_size=integer_value(
                payload, "page_size", 10, minimum=1, maximum=100
            ),
            request_id=string_value(payload, "request_id", "", max_length=128),
            experiment_token=string_value(
                payload, "experiment_token", "", max_length=128
            ),
            debug=boolean_value(payload, "debug", True),
        )
        response = self.search_engine.search(request, self.get_user(request.user_id))
        return asdict(response)

    def recommend(self, payload: dict[str, Any]) -> dict[str, Any]:
        payload = require_mapping(payload)
        reject_unknown_fields(
            payload,
            {
                "user_id",
                "scene",
                "size",
                "request_id",
                "experiment_token",
                "exclude_seen",
                "debug",
            },
        )
        request = RecommendRequest(
            user_id=string_value(payload, "user_id", "guest", required=True, max_length=64),
            scene=string_value(payload, "scene", "homepage", required=True, max_length=64),
            size=integer_value(payload, "size", 10, minimum=1, maximum=100),
            request_id=string_value(payload, "request_id", "", max_length=128),
            experiment_token=string_value(
                payload, "experiment_token", "", max_length=128
            ),
            exclude_seen=boolean_value(payload, "exclude_seen", True),
            debug=boolean_value(payload, "debug", True),
        )
        response = self.recommendation_engine.recommend(
            request, self.get_user(request.user_id)
        )
        return asdict(response)

    def value_device(self, payload: dict[str, Any]) -> dict[str, Any]:
        payload = require_mapping(payload)
        reject_unknown_fields(
            payload,
            {
                "brand",
                "model",
                "category",
                "storage_gb",
                "age_months",
                "condition_grade",
                "battery_health",
                "functional_issues",
                "inspection_method",
            },
        )
        condition_grade = string_value(
            payload, "condition_grade", "B", required=True, max_length=1
        ).upper()
        if condition_grade not in {"A", "B", "C", "D"}:
            raise ValidationError("condition_grade", "must be one of: A, B, C, D")
        inspection_method = string_value(
            payload, "inspection_method", "mail", required=True, max_length=16
        )
        if inspection_method not in {"mail", "door", "store"}:
            raise ValidationError(
                "inspection_method", "must be one of: door, mail, store"
            )
        issues = payload.get("functional_issues", [])
        if not isinstance(issues, (list, tuple)) or not all(
            isinstance(issue, str) and issue for issue in issues
        ):
            raise ValidationError(
                "functional_issues", "must be an array of non-empty strings"
            )
        if len(issues) != len(set(issues)):
            raise ValidationError(
                "functional_issues", "must not contain duplicate values"
            )
        allowed_issues = set(DeviceValuationEngine.ISSUE_FACTORS)
        unknown_issues = set(issues) - allowed_issues
        if unknown_issues:
            raise ValidationError(
                "functional_issues",
                "unknown values: " + ", ".join(sorted(unknown_issues)),
            )
        battery_health = (
            integer_value(
                payload,
                "battery_health",
                0,
                minimum=0,
                maximum=100,
            )
            if payload.get("battery_health") is not None
            else None
        )
        request = DeviceValuationRequest(
            brand=string_value(
                payload, "brand", required=True, max_length=64
            ),
            model=string_value(
                payload, "model", required=True, max_length=128
            ),
            category=string_value(
                payload, "category", "手机", required=True, max_length=32
            ),
            storage_gb=integer_value(
                payload, "storage_gb", 128, minimum=32, maximum=2048
            ),
            age_months=integer_value(
                payload, "age_months", 24, minimum=0, maximum=240
            ),
            condition_grade=condition_grade,
            battery_health=battery_health,
            functional_issues=tuple(issues),
            inspection_method=inspection_method,
        )
        return asdict(self.valuation_engine.estimate(request))

    def record_event(self, payload: dict[str, Any]) -> dict[str, Any]:
        payload = require_mapping(payload)
        reject_unknown_fields(
            payload,
            {"user_id", "product_id", "event_type", "scene"},
        )
        user_id = string_value(payload, "user_id", required=True, max_length=64)
        product_id = string_value(payload, "product_id", required=True, max_length=64)
        event_type = string_value(payload, "event_type", required=True, max_length=32)
        scene = string_value(payload, "scene", "homepage", required=True, max_length=64)
        if product_id not in self.product_ids:
            raise ValidationError("product_id", f"unknown product: {product_id}")
        if event_type in {"click", "purchase", "dislike"}:
            self.user_store.record_event(user_id, product_id, event_type)
        elif event_type == "exposure":
            self.exposure_store.add(user_id, scene, product_id)
        else:
            raise ValidationError(
                "event_type", "must be one of: click, dislike, exposure, purchase"
            )
        return {
            "accepted": True,
            "event_type": event_type,
            "product_id": product_id,
            "scene": scene,
        }


def default_experiment_path() -> Path:
    configured = os.environ.get("SHOPREC_EXPERIMENT_CONFIG")
    if configured:
        return Path(configured).expanduser().resolve()

    source_path = Path(__file__).resolve().parents[2] / "config" / "experiments.json"
    if source_path.exists():
        return source_path

    working_path = Path.cwd() / "config" / "experiments.json"
    if working_path.exists():
        return working_path

    return Path(__file__).resolve().parent / "default_experiments.json"


def create_demo_service(
    experiment_path: str | Path | None = None,
    *,
    clock: Clock | None = None,
    id_generator: IdGenerator | None = None,
    model_reranker: ModelReranker | None = None,
) -> CommerceDiscoveryService:
    runtime_clock = clock or SystemClock()
    config_path = Path(experiment_path) if experiment_path else default_experiment_path()
    experiments = ExperimentManager.from_json(config_path)
    return CommerceDiscoveryService(
        demo_products(runtime_clock.now()),
        demo_users(),
        experiments,
        clock=runtime_clock,
        id_generator=id_generator,
        model_reranker=model_reranker,
    )
