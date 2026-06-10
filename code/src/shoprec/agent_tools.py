from __future__ import annotations

import hashlib
import statistics
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from .agent_data import load_agent_products
from .agent_grounding import contains_prompt_injection, product_citations
from .experiments import ExperimentManager
from .runtime import Clock, IdGenerator, SystemClock
from .sample_data import demo_users
from .service import CommerceDiscoveryService, default_experiment_path
from .validation import ValidationError


@dataclass(frozen=True)
class ToolContext:
    session_id: str
    user_id: str
    confirmed: bool = False


@dataclass(frozen=True)
class RegisteredTool:
    name: str
    description: str
    parameters: dict[str, Any]
    handler: Callable[[dict[str, Any], ToolContext], dict[str, Any]]
    side_effecting: bool = False

    def model_definition(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            },
        }


class InMemoryShortlistStore:
    def __init__(self) -> None:
        self._items: dict[tuple[str, str], tuple[str, ...]] = {}
        self._idempotency: dict[str, dict[str, Any]] = {}
        self._lock = threading.RLock()

    def save(
        self,
        user_id: str,
        session_id: str,
        product_ids: list[str],
        idempotency_key: str,
    ) -> dict[str, Any]:
        with self._lock:
            prior = self._idempotency.get(idempotency_key)
            fingerprint = hashlib.sha256(
                f"{user_id}|{session_id}|{','.join(product_ids)}".encode("utf-8")
            ).hexdigest()
            if prior:
                if prior["fingerprint"] != fingerprint:
                    raise ValidationError(
                        "idempotency_key", "was already used with different input"
                    )
                return dict(prior["result"])
            result = {
                "saved": True,
                "user_id": user_id,
                "session_id": session_id,
                "product_ids": list(product_ids),
                "replayed": False,
            }
            self._items[(user_id, session_id)] = tuple(product_ids)
            self._idempotency[idempotency_key] = {
                "fingerprint": fingerprint,
                "result": result,
            }
            return dict(result)

    def get(self, user_id: str, session_id: str) -> list[str]:
        with self._lock:
            return list(self._items.get((user_id, session_id), ()))


def create_agent_service(
    experiment_path: str | Path | None = None,
    *,
    clock: Clock | None = None,
    id_generator: IdGenerator | None = None,
) -> CommerceDiscoveryService:
    runtime_clock = clock or SystemClock()
    config = Path(experiment_path) if experiment_path else default_experiment_path()
    return CommerceDiscoveryService(
        load_agent_products(),
        demo_users(),
        ExperimentManager.from_json(config),
        clock=runtime_clock,
        id_generator=id_generator,
    )


class AgentToolRegistry:
    def __init__(
        self,
        service: CommerceDiscoveryService,
        shortlist_store: InMemoryShortlistStore | None = None,
    ) -> None:
        self.service = service
        self.products = {product.product_id: product for product in service.products}
        self.shortlist_store = shortlist_store or InMemoryShortlistStore()
        self._tools = self._build_tools()

    @property
    def names(self) -> set[str]:
        return set(self._tools)

    def definitions(self, allowed: set[str] | None = None) -> tuple[dict[str, Any], ...]:
        names = sorted(allowed if allowed is not None else self._tools)
        return tuple(self._tools[name].model_definition() for name in names)

    def is_side_effecting(self, name: str) -> bool:
        tool = self._tools.get(name)
        return bool(tool and tool.side_effecting)

    def call(self, name: str, arguments: dict[str, Any], context: ToolContext) -> dict[str, Any]:
        tool = self._tools.get(name)
        if tool is None:
            raise ValidationError("tool", f"unknown tool: {name}")
        if not isinstance(arguments, dict):
            raise ValidationError("tool.arguments", "must be an object")
        allowed_fields = set(tool.parameters.get("properties", {}))
        unknown = set(arguments) - allowed_fields
        if unknown:
            raise ValidationError(
                "tool.arguments", "unknown fields: " + ", ".join(sorted(unknown))
            )
        required = set(tool.parameters.get("required", []))
        missing = required - set(arguments)
        if missing:
            raise ValidationError(
                "tool.arguments", "missing fields: " + ", ".join(sorted(missing))
            )
        for field, value in arguments.items():
            self._validate_value(field, value, tool.parameters["properties"][field])
        if tool.side_effecting and not context.confirmed:
            raise ValidationError("confirmation", f"{name} requires explicit confirmation")
        return tool.handler(arguments, context)

    @classmethod
    def _validate_value(cls, field: str, value: Any, schema: dict[str, Any]) -> None:
        allowed = schema.get("type")
        allowed_types = set(allowed if isinstance(allowed, list) else [allowed])
        if value is None:
            if "null" not in allowed_types:
                raise ValidationError(field, "must not be null")
            return
        actual = (
            "boolean"
            if isinstance(value, bool)
            else "integer"
            if isinstance(value, int)
            else "string"
            if isinstance(value, str)
            else "array"
            if isinstance(value, list)
            else "unknown"
        )
        if actual not in allowed_types:
            raise ValidationError(field, f"must have JSON type {sorted(allowed_types)}")
        if actual == "integer":
            if "minimum" in schema and value < schema["minimum"]:
                raise ValidationError(field, f"must be at least {schema['minimum']}")
            if "maximum" in schema and value > schema["maximum"]:
                raise ValidationError(field, f"must be at most {schema['maximum']}")
        elif actual == "string":
            if not value.strip():
                raise ValidationError(field, "must be a non-empty string")
            if "minLength" in schema and len(value) < schema["minLength"]:
                raise ValidationError(field, f"must contain at least {schema['minLength']} characters")
            if "maxLength" in schema and len(value) > schema["maxLength"]:
                raise ValidationError(field, f"must contain at most {schema['maxLength']} characters")
            if "enum" in schema and value not in schema["enum"]:
                raise ValidationError(field, "must be one of: " + ", ".join(schema["enum"]))
        elif actual == "array":
            if "minItems" in schema and len(value) < schema["minItems"]:
                raise ValidationError(field, f"must contain at least {schema['minItems']} items")
            if "maxItems" in schema and len(value) > schema["maxItems"]:
                raise ValidationError(field, f"must contain at most {schema['maxItems']} items")
            item_schema = schema.get("items")
            if isinstance(item_schema, dict):
                for index, item in enumerate(value):
                    cls._validate_value(f"{field}[{index}]", item, item_schema)

    def _build_tools(self) -> dict[str, RegisteredTool]:
        object_schema = {"type": "object", "additionalProperties": False}
        return {
            "search_products": RegisteredTool(
                "search_products",
                "Search the synthetic Moyuan iPhone catalog using confirmed buyer constraints.",
                {
                    **object_schema,
                    "properties": {
                        "budget_max": {"type": ["integer", "null"], "minimum": 0},
                        "storage_min_gb": {"type": ["integer", "null"], "minimum": 0},
                        "battery_min": {"type": ["integer", "null"], "minimum": 0, "maximum": 100},
                        "warranty_required": {"type": "boolean"},
                        "accepts_repair": {"type": "boolean"},
                        "city": {"type": ["string", "null"]},
                        "preferred_models": {"type": "array", "items": {"type": "string"}},
                        "size": {"type": "integer", "minimum": 1, "maximum": 5},
                    },
                    "required": ["warranty_required", "accepts_repair", "size"],
                },
                self._search_products,
            ),
            "recommend_products": RegisteredTool(
                "recommend_products",
                "Get personalized synthetic iPhone candidates and enforce confirmed constraints.",
                {
                    **object_schema,
                    "properties": {
                        "budget_max": {"type": ["integer", "null"], "minimum": 0},
                        "storage_min_gb": {"type": ["integer", "null"], "minimum": 0},
                        "battery_min": {"type": ["integer", "null"], "minimum": 0, "maximum": 100},
                        "warranty_required": {"type": "boolean"},
                        "accepts_repair": {"type": "boolean"},
                        "city": {"type": ["string", "null"]},
                        "preferred_models": {"type": "array", "items": {"type": "string"}},
                        "size": {"type": "integer", "minimum": 1, "maximum": 5},
                    },
                    "required": ["warranty_required", "accepts_repair", "size"],
                },
                self._recommend_products,
            ),
            "compare_products": RegisteredTool(
                "compare_products",
                "Compare up to three products using catalog evidence and comparable listing prices.",
                {
                    **object_schema,
                    "properties": {
                        "product_ids": {
                            "type": "array",
                            "items": {"type": "string"},
                            "minItems": 1,
                            "maxItems": 3,
                        }
                    },
                    "required": ["product_ids"],
                },
                self._compare_products,
            ),
            "suggest_relaxations": RegisteredTool(
                "suggest_relaxations",
                "Suggest minimal, explicit relaxations after a zero-result search; never changes safety constraints.",
                {
                    **object_schema,
                    "properties": {
                        "budget_max": {"type": ["integer", "null"]},
                        "storage_min_gb": {"type": ["integer", "null"]},
                        "battery_min": {"type": ["integer", "null"]},
                        "warranty_required": {"type": "boolean"},
                        "city": {"type": ["string", "null"]},
                    },
                    "required": ["warranty_required"],
                },
                self._suggest_relaxations,
            ),
            "record_feedback": RegisteredTool(
                "record_feedback",
                "Record a click or dislike after the user explicitly expresses it.",
                {
                    **object_schema,
                    "properties": {
                        "product_id": {"type": "string"},
                        "event_type": {"type": "string", "enum": ["click", "dislike"]},
                    },
                    "required": ["product_id", "event_type"],
                },
                self._record_feedback,
                side_effecting=True,
            ),
            "save_shortlist": RegisteredTool(
                "save_shortlist",
                "Save one to three shortlisted products only after explicit user confirmation.",
                {
                    **object_schema,
                    "properties": {
                        "product_ids": {
                            "type": "array",
                            "items": {"type": "string"},
                            "minItems": 1,
                            "maxItems": 3,
                        },
                        "idempotency_key": {"type": "string", "minLength": 8, "maxLength": 128},
                    },
                    "required": ["product_ids", "idempotency_key"],
                },
                self._save_shortlist,
                side_effecting=True,
            ),
        }

    @staticmethod
    def _integer(arguments: dict[str, Any], field: str) -> int | None:
        value = arguments.get(field)
        if value is None:
            return None
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValidationError(field, "must be an integer or null")
        return value

    def _search_products(self, arguments: dict[str, Any], context: ToolContext) -> dict[str, Any]:
        budget = self._integer(arguments, "budget_max")
        battery = self._integer(arguments, "battery_min")
        storage = self._integer(arguments, "storage_min_gb")
        warranty = bool(arguments.get("warranty_required", False))
        accepts_repair = bool(arguments.get("accepts_repair", False))
        city = arguments.get("city")
        size = int(arguments.get("size", 3))
        preferred_models = [str(value) for value in arguments.get("preferred_models", [])]
        result = self.service.search(
            {
                "query": "iphone",
                "user_id": context.user_id,
                "page_size": 100,
                "filters": {
                    "categories": ["手机"],
                    "brands": ["Apple"],
                    "city": city,
                    "max_price": budget,
                    "service_modes": ["platform_inspected"],
                    "inspection_grades": ["A", "B"],
                    "min_battery_health": battery,
                    "warranty_required": warranty,
                },
            }
        )
        items: list[dict[str, Any]] = []
        blocked_untrusted = 0
        for item in result["items"]:
            if storage and (item.get("storage_gb") or 0) < storage:
                continue
            if preferred_models and item.get("model_name") not in preferred_models:
                continue
            repair_history = item.get("service", {}).get("repair_history", [])
            if {"water_damage", "mainboard_repair"}.intersection(repair_history):
                continue
            if not accepts_repair and repair_history:
                continue
            if contains_prompt_injection(str(item.get("title", ""))):
                blocked_untrusted += 1
                continue
            items.append(item)
            if len(items) >= size:
                break
        citations = [citation for item in items for citation in product_citations(item)]
        return {
            "items": items,
            "total_after_agent_constraints": len(items),
            "search_total_before_agent_constraints": result["total"],
            "filter_reasons": result["filter_reasons"],
            "blocked_untrusted_content": blocked_untrusted,
            "citations": citations,
            "request_id": result["request_id"],
        }

    def _recommend_products(self, arguments: dict[str, Any], context: ToolContext) -> dict[str, Any]:
        size = int(arguments.get("size", 3))
        budget = self._integer(arguments, "budget_max")
        storage = self._integer(arguments, "storage_min_gb")
        battery = self._integer(arguments, "battery_min")
        warranty = bool(arguments.get("warranty_required", False))
        accepts_repair = bool(arguments.get("accepts_repair", False))
        city = arguments.get("city")
        preferred_models = [str(value) for value in arguments.get("preferred_models", [])]
        result = self.service.recommend(
            {
                "user_id": context.user_id,
                "size": min(size * 20, 100),
                "exclude_seen": False,
            }
        )
        items = []
        blocked_untrusted = 0
        for item in result["items"]:
            service = item.get("service", {})
            repair_history = service.get("repair_history", [])
            if contains_prompt_injection(str(item.get("title", ""))):
                blocked_untrusted += 1
                continue
            if item.get("brand") != "Apple" or service.get("mode") != "platform_inspected":
                continue
            if service.get("inspection_status") != "passed" or service.get("inspection_grade") not in {"A", "B"}:
                continue
            if budget is not None and item.get("price", 0) > budget:
                continue
            if storage is not None and (item.get("storage_gb") or 0) < storage:
                continue
            if battery is not None and (service.get("battery_health") or 0) < battery:
                continue
            if warranty and (service.get("warranty_days") or 0) <= 0:
                continue
            if {"water_damage", "mainboard_repair"}.intersection(repair_history):
                continue
            if not accepts_repair and repair_history:
                continue
            if city and item.get("city") != city:
                continue
            if preferred_models and item.get("model_name") not in preferred_models:
                continue
            items.append(item)
            if len(items) >= size:
                break
        return {
            "items": items,
            "citations": [citation for item in items for citation in product_citations(item)],
            "request_id": result["request_id"],
            "blocked_untrusted_content": blocked_untrusted,
        }

    def _price_reference(self, product_id: str) -> dict[str, Any]:
        product = self.products[product_id]
        comparable = [
            item.price
            for item in self.products.values()
            if item.stock > 0
            and item.inspection_status == "passed"
            and item.model_name == product.model_name
            and item.storage_gb == product.storage_gb
            and item.inspection_grade == product.inspection_grade
        ]
        median = round(statistics.median(comparable), 2) if comparable else product.price
        delta = round(product.price - median, 2)
        return {
            "comparable_count": len(comparable),
            "median_price": median,
            "listing_delta": delta,
            "interpretation": "above_median" if delta > 0 else "at_or_below_median",
            "caveat": "synthetic comparable listings; not a market valuation",
        }

    def _compare_products(self, arguments: dict[str, Any], _context: ToolContext) -> dict[str, Any]:
        product_ids = arguments.get("product_ids")
        if not isinstance(product_ids, list) or not 1 <= len(product_ids) <= 3:
            raise ValidationError("product_ids", "must contain between 1 and 3 IDs")
        if len(product_ids) != len(set(product_ids)):
            raise ValidationError("product_ids", "must not contain duplicates")
        items: list[dict[str, Any]] = []
        citations: list[dict[str, Any]] = []
        for product_id in product_ids:
            product = self.products.get(str(product_id))
            if product is None:
                raise ValidationError("product_ids", f"unknown product: {product_id}")
            item = {
                "product_id": product.product_id,
                "title": product.title,
                "model_name": product.model_name,
                "storage_gb": product.storage_gb,
                "price": product.price,
                "condition": product.condition,
                "city": product.city,
                "battery_health": product.battery_health,
                "inspection_grade": product.inspection_grade,
                "warranty_days": product.warranty_days,
                "repair_history": list(product.repair_history),
                "price_reference": self._price_reference(product.product_id),
                "untrusted_content_detected": contains_prompt_injection(product.title),
            }
            items.append(item)
            citations.extend(
                product_citations(
                    {
                        **item,
                        "service": {
                            "battery_health": product.battery_health,
                            "inspection_grade": product.inspection_grade,
                            "inspection_status": product.inspection_status,
                            "warranty_days": product.warranty_days,
                            "repair_history": list(product.repair_history),
                        },
                    }
                )
            )
        return {"items": items, "citations": citations}

    def _suggest_relaxations(self, arguments: dict[str, Any], context: ToolContext) -> dict[str, Any]:
        options: list[dict[str, Any]] = []
        budget = self._integer(arguments, "budget_max")
        battery = self._integer(arguments, "battery_min")
        storage = self._integer(arguments, "storage_min_gb")
        city = arguments.get("city")
        if budget is not None:
            options.append({"field": "budget_max", "from": budget, "to": budget + 200})
        if battery is not None and battery > 80:
            options.append({"field": "battery_min", "from": battery, "to": max(80, battery - 3)})
        if storage is not None and storage > 128:
            options.append({"field": "storage_min_gb", "from": storage, "to": 128})
        if city:
            options.append({"field": "city", "from": city, "to": None})
        if arguments.get("warranty_required"):
            options.append(
                {
                    "field": "warranty_required",
                    "from": True,
                    "to": False,
                    "requires_explicit_confirmation": True,
                }
            )
        return {
            "options": options,
            "never_relaxed": [
                "stock",
                "inspection_status",
                "water_damage",
                "mainboard_repair",
            ],
            "applied": False,
            "session_id": context.session_id,
        }

    def _record_feedback(self, arguments: dict[str, Any], context: ToolContext) -> dict[str, Any]:
        return self.service.record_event(
            {
                "user_id": context.user_id,
                "product_id": str(arguments["product_id"]),
                "event_type": str(arguments["event_type"]),
                "scene": "buyer_agent",
            }
        )

    def _save_shortlist(self, arguments: dict[str, Any], context: ToolContext) -> dict[str, Any]:
        product_ids = arguments.get("product_ids")
        if not isinstance(product_ids, list) or not 1 <= len(product_ids) <= 3:
            raise ValidationError("product_ids", "must contain between 1 and 3 IDs")
        normalized = [str(value) for value in product_ids]
        unknown = set(normalized) - set(self.products)
        if unknown:
            raise ValidationError("product_ids", "unknown IDs: " + ", ".join(sorted(unknown)))
        return self.shortlist_store.save(
            context.user_id,
            context.session_id,
            normalized,
            str(arguments["idempotency_key"]),
        )
