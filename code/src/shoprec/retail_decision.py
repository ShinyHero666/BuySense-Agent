from __future__ import annotations

import json
import math
import itertools
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Callable

from .generated_contracts_v2 import (
    BundleOptimizationWireRequest,
    FusionWireRequest,
    PricingQuoteWireRequest,
    PricingQuoteWireResponse,
    ReviewEvidenceWireRequest,
    ReviewEvidenceWireResponse,
)
from .retail_data_ports import (
    PricingDataPort,
    ReviewDataPort,
    StaticPricingDataPort,
    StaticReviewDataPort,
)
from .retail_models import RetailCatalogItem, RetailCatalogSnapshot, load_retail_catalog
from .retail_domain import NORMAL_3C_DOMAIN_PACK_MODEL, RetailDomainPack
from .validation import ValidationError, reject_unknown_fields, require_mapping

# Backward-compatible names now share the single validated data-port implementation.
ReviewAspectRepository = StaticReviewDataPort
PricingQuoteService = StaticPricingDataPort


@dataclass(frozen=True)
class CompatibilityRule:
    rule_id: str
    primary_category: str
    accessory_category: str
    required_shared_connectors: tuple[str, ...]
    required_shared_protocols: tuple[str, ...]


def _string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be a non-empty string")
    return value.strip()


def _string_list(value: Any, field: str) -> list[str]:
    if not isinstance(value, list) or not all(
        isinstance(item, str) and item.strip() for item in value
    ):
        raise ValidationError(field, "must be an array of non-empty strings")
    if len(value) > 100:
        raise ValidationError(field, "must contain at most 100 values")
    return [item.strip() for item in value]


class CompatibilityGraphService:
    def __init__(
        self,
        catalog: RetailCatalogSnapshot,
        path: str | Path | None = None,
        *,
        pack: RetailDomainPack | None = None,
    ) -> None:
        runtime_pack = pack or NORMAL_3C_DOMAIN_PACK_MODEL
        raw = json.loads(
            (Path(path) if path else runtime_pack.asset_path("compatibility")).read_text(
                encoding="utf-8"
            )
        )
        self.version = _string(raw.get("graph_version"), "graph_version")
        raw_rules = raw.get("rules")
        if not isinstance(raw_rules, list):
            raise ValueError("compatibility rules must be an array")
        self.rules = tuple(
            CompatibilityRule(
                rule_id=_string(rule.get("rule_id"), f"rules[{index}].rule_id"),
                primary_category=_string(
                    rule.get("primary_category"), f"rules[{index}].primary_category"
                ),
                accessory_category=_string(
                    rule.get("accessory_category"), f"rules[{index}].accessory_category"
                ),
                required_shared_connectors=tuple(
                    _string_list(
                        rule.get("required_shared_connectors"),
                        f"rules[{index}].required_shared_connectors",
                    )
                ),
                required_shared_protocols=tuple(
                    _string_list(
                        rule.get("required_shared_protocols"),
                        f"rules[{index}].required_shared_protocols",
                    )
                ),
            )
            for index, rule in enumerate(raw_rules)
            if isinstance(rule, dict)
        )
        self.items_by_sku = {item.sku.sku_id: item for item in catalog.sellable_items()}

    def _evaluate(self, primary: RetailCatalogItem, accessory: RetailCatalogItem) -> dict[str, Any]:
        rule = next(
            (
                item
                for item in self.rules
                if item.primary_category == primary.spu.category
                and item.accessory_category == accessory.spu.category
            ),
            None,
        )
        if rule is None:
            return {
                "status": "unknown",
                "reasons": ["no_graph_rule_for_category_pair"],
                "rule_id": None,
                "paths": [],
            }
        shared_connectors = sorted(
            set(primary.sku.connectors) & set(accessory.sku.connectors)
            & set(rule.required_shared_connectors)
        )
        shared_protocols = sorted(
            set(primary.sku.protocols) & set(accessory.sku.protocols)
            & set(rule.required_shared_protocols)
        )
        connector_ok = not rule.required_shared_connectors or bool(shared_connectors)
        protocol_ok = not rule.required_shared_protocols or bool(shared_protocols)
        paths = [
            f"{primary.sku.sku_id}-[HAS_CONNECTOR]->{value}<-[HAS_CONNECTOR]-{accessory.sku.sku_id}"
            for value in shared_connectors
        ] + [
            f"{primary.sku.sku_id}-[SUPPORTS_PROTOCOL]->{value}<-[SUPPORTS_PROTOCOL]-{accessory.sku.sku_id}"
            for value in shared_protocols
        ]
        status = (
            "compatible"
            if connector_ok and protocol_ok
            else "unknown"
            if connector_ok
            else "incompatible"
        )
        reasons = [
            "connector_graph_match" if connector_ok else "connector_graph_mismatch",
            "protocol_graph_match" if protocol_ok else "protocol_graph_unknown",
        ]
        return {"status": status, "reasons": reasons, "rule_id": rule.rule_id, "paths": paths}

    def check(self, payload: dict[str, Any]) -> dict[str, Any]:
        payload = require_mapping(payload)
        reject_unknown_fields(payload, {"pairs"})
        pairs = payload.get("pairs")
        if not isinstance(pairs, list) or len(pairs) > 50:
            raise ValidationError("pairs", "must be an array with at most 50 values")
        results: list[dict[str, Any]] = []
        for index, pair in enumerate(pairs):
            if not isinstance(pair, dict):
                raise ValidationError(f"pairs[{index}]", "must be an object")
            reject_unknown_fields(
                pair,
                {"product_sku_id", "accessory_sku_id"},
                field=f"pairs[{index}]",
            )
            product_sku_id = _string(pair.get("product_sku_id"), f"pairs[{index}].product_sku_id")
            accessory_sku_id = _string(
                pair.get("accessory_sku_id"), f"pairs[{index}].accessory_sku_id"
            )
            primary = self.items_by_sku.get(product_sku_id)
            accessory = self.items_by_sku.get(accessory_sku_id)
            if primary is None or accessory is None:
                evaluated = {
                    "status": "unknown",
                    "reasons": ["sku_not_found_in_graph"],
                    "rule_id": None,
                    "paths": [],
                }
            else:
                evaluated = self._evaluate(primary, accessory)
            results.append(
                {
                    "product_sku_id": product_sku_id,
                    "accessory_sku_id": accessory_sku_id,
                    **evaluated,
                    "graph_version": self.version,
                }
            )
        return {"graph_version": self.version, "results": results}


class RetailDecisionService:
    def __init__(
        self,
        catalog: RetailCatalogSnapshot | None = None,
        *,
        pack: RetailDomainPack | None = None,
        now: Callable[[], datetime] | None = None,
        reviews: ReviewDataPort | None = None,
        pricing: PricingDataPort | None = None,
    ) -> None:
        self.pack = pack or NORMAL_3C_DOMAIN_PACK_MODEL
        runtime_catalog = catalog or load_retail_catalog(pack=self.pack)
        self.catalog = runtime_catalog
        self.reviews = reviews or StaticReviewDataPort(pack=self.pack)
        self.compatibility = CompatibilityGraphService(runtime_catalog, pack=self.pack)
        self.pricing = pricing or StaticPricingDataPort(
            runtime_catalog, pack=self.pack, now=now
        )

    def review_aspects(
        self, payload: ReviewEvidenceWireRequest
    ) -> ReviewEvidenceWireResponse:
        return self.reviews.get(payload)

    def check_compatibility(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self.compatibility.check(payload)

    def quote(self, payload: PricingQuoteWireRequest) -> PricingQuoteWireResponse:
        return self.pricing.quote(payload)

    def _candidate(
        self, item: Any, field: str
    ) -> dict[str, Any]:
        if not isinstance(item, dict):
            raise ValidationError(field, "must be an object")
        required_strings = (
            "spu_id",
            "product_id",
            "sku_id",
            "offer_id",
            "title",
            "category",
            "brand",
        )
        for name in required_strings:
            if not isinstance(item.get(name), str):
                raise ValidationError(f"{field}.{name}", "must be a string")
        if item["category"] not in self.pack.product_categories:
            raise ValidationError(f"{field}.category", "is outside the selected domain pack")
        for name in ("price", "channel_score", "normalized_score"):
            value = item.get(name)
            if (
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or not math.isfinite(value)
            ):
                raise ValidationError(f"{field}.{name}", "must be a finite number")
        stock = item.get("stock")
        if isinstance(stock, bool) or not isinstance(stock, int):
            raise ValidationError(f"{field}.stock", "must be an integer")
        if item.get("currency") != "CNY":
            raise ValidationError(f"{field}.currency", "must equal CNY")
        if item.get("channel") not in {"search", "recommendation", "ads"}:
            raise ValidationError(f"{field}.channel", "is unsupported")
        if not isinstance(item.get("reasons"), list) or not all(
            isinstance(reason, str) for reason in item["reasons"]
        ):
            raise ValidationError(f"{field}.reasons", "must be an array of strings")
        if not isinstance(item.get("sponsored"), bool):
            raise ValidationError(f"{field}.sponsored", "must be a boolean")
        for optional_number in ("ad_quality", "ad_bid"):
            value = item.get(optional_number)
            if value is not None and (
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or not math.isfinite(value)
            ):
                raise ValidationError(
                    f"{field}.{optional_number}", "must be a finite number"
                )
        return dict(item)

    def _candidate_list(
        self, payload: dict[str, Any], field: str = "items"
    ) -> list[dict[str, Any]]:
        value = payload.get(field)
        if not isinstance(value, list) or len(value) > 200:
            raise ValidationError(field, "must be an array with at most 200 values")
        return [
            self._candidate(item, f"{field}[{index}]")
            for index, item in enumerate(value)
        ]

    def fuse(self, payload: FusionWireRequest) -> dict[str, Any]:
        """Weighted reciprocal-rank fusion with organic-quality ad protection."""
        payload = require_mapping(payload)
        reject_unknown_fields(payload, {"channels", "limit"})
        channels = payload.get("channels")
        if not isinstance(channels, list) or len(channels) > 3:
            raise ValidationError("channels", "must be an array with at most 3 values")
        limit = payload.get("limit", 8)
        if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 50:
            raise ValidationError("limit", "must be an integer between 1 and 50")

        weights = {"search": 1.0, "recommendation": 0.9, "ads": 0.55}
        natural: dict[str, dict[str, Any]] = {}
        natural_scores: dict[str, float] = {}
        ads: list[tuple[float, dict[str, Any]]] = []
        for channel_index, channel_result in enumerate(channels):
            if not isinstance(channel_result, dict):
                raise ValidationError(f"channels[{channel_index}]", "must be an object")
            reject_unknown_fields(
                channel_result,
                {"channel", "items", "catalog_version", "quote_version", "data_source"},
                field=f"channels[{channel_index}]",
            )
            for version_field in ("catalog_version", "quote_version"):
                version = channel_result.get(version_field)
                if version is not None and (
                    not isinstance(version, str) or len(version) == 0
                ):
                    raise ValidationError(
                        f"channels[{channel_index}].{version_field}",
                        "must be a non-empty string",
                    )
            channel = channel_result.get("channel")
            if channel not in weights:
                raise ValidationError(f"channels[{channel_index}].channel", "is unsupported")
            items = channel_result.get("items")
            if not isinstance(items, list) or len(items) > 100:
                raise ValidationError(f"channels[{channel_index}].items", "must be an array")
            for rank, original in enumerate(items, start=1):
                candidate = self._candidate(
                    original,
                    f"channels[{channel_index}].items[{rank - 1}]",
                )
                sku_id = _string(candidate.get("sku_id"), "sku_id")
                rrf = weights[channel] / (60 + rank)
                if channel == "ads":
                    ads.append((rrf, candidate))
                    continue
                if sku_id not in natural:
                    natural[sku_id] = candidate
                    natural[sku_id]["sources"] = [channel]
                    natural_scores[sku_id] = 0.0
                elif channel not in natural[sku_id]["sources"]:
                    natural[sku_id]["sources"].append(channel)
                natural_scores[sku_id] += rrf

        ordered_natural = sorted(
            natural.values(),
            key=lambda item: (-natural_scores[str(item["sku_id"])], str(item["sku_id"])),
        )
        fused_scores = list(natural_scores.values())
        minimum = min(fused_scores, default=0.0)
        maximum = max(fused_scores, default=1.0)
        for item in ordered_natural:
            raw_score = natural_scores[str(item["sku_id"])]
            item["normalized_score"] = round(
                1.0 if maximum == minimum else (raw_score - minimum) / (maximum - minimum),
                6,
            )
            item["reasons"] = list(dict.fromkeys([
                *item.get("reasons", []),
                f"weighted_rrf={raw_score:.6f}",
            ]))

        slate = ordered_natural[:limit]
        best_natural_by_category: dict[str, float] = {}
        for item in ordered_natural:
            category = str(item.get("category", ""))
            best_natural_by_category[category] = max(
                best_natural_by_category.get(category, 0.0),
                float(item.get("channel_score", 0.0)),
            )
        eligible_ads = []
        for rrf, item in sorted(ads, key=lambda pair: (-pair[0], str(pair[1].get("sku_id", "")))):
            if any(existing.get("sku_id") == item.get("sku_id") for existing in slate):
                continue
            category = str(item.get("category", ""))
            organic_floor = 0.85 * best_natural_by_category.get(category, 0.0)
            if float(item.get("channel_score", 0.0)) < organic_floor:
                continue
            if float(item.get("ad_quality", 0.0)) < 0.5:
                continue
            item["normalized_score"] = round(min(1.0, rrf * 61), 6)
            item["sources"] = ["ads"]
            item["reasons"] = list(dict.fromkeys([
                *item.get("reasons", []),
                "organic_quality_floor_passed",
            ]))
            eligible_ads.append(item)
        if eligible_ads and limit > 1:
            slate.insert(1, eligible_ads[0])
        return {
            "fusion_version": "weighted-rrf-v2",
            "items": slate[:limit],
            "organic_count": sum(not bool(item.get("sponsored")) for item in slate[:limit]),
            "sponsored_count": sum(bool(item.get("sponsored")) for item in slate[:limit]),
        }

    def optimize_bundles(
        self, payload: BundleOptimizationWireRequest
    ) -> dict[str, Any]:
        """Enumerate bounded category combinations and return globally scored Top-N bundles."""
        payload = require_mapping(payload)
        reject_unknown_fields(
            payload,
            {"items", "requested_categories", "intent", "budget_max", "top_n"},
        )
        items = self._candidate_list(payload)
        requested = _string_list(payload.get("requested_categories"), "requested_categories")
        if any(
            category != category.strip()
            for category in payload.get("requested_categories", [])
        ):
            raise ValidationError(
                "requested_categories", "values must not contain surrounding whitespace"
            )
        if not requested:
            raise ValidationError(
                "requested_categories", "must contain at least one category"
            )
        unknown_categories = sorted(set(requested) - self.pack.product_categories)
        if unknown_categories:
            raise ValidationError(
                "requested_categories",
                "contains categories outside the selected domain pack: "
                + ", ".join(unknown_categories),
            )
        intent = payload.get("intent")
        if not isinstance(intent, str) or not intent or intent != intent.strip():
            raise ValidationError(
                "intent", "must be a non-empty value without surrounding whitespace"
            )
        if intent not in {"precise", "catalog", "exploratory", "bundle", "compare"}:
            raise ValidationError("intent", "is unsupported")
        top_n = payload.get("top_n", 3)
        if isinstance(top_n, bool) or not isinstance(top_n, int) or not 1 <= top_n <= 10:
            raise ValidationError("top_n", "must be an integer between 1 and 10")
        budget_raw = payload.get("budget_max")
        if budget_raw is not None and (
            isinstance(budget_raw, bool)
            or not isinstance(budget_raw, (int, float))
            or not math.isfinite(budget_raw)
            or budget_raw < 0
        ):
            raise ValidationError("budget_max", "must be a non-negative number or null")
        budget = float(budget_raw) if budget_raw is not None else None
        primary_category = (
            self.pack.primary_category
            if self.pack.primary_category in requested
            else requested[0]
        )
        required_categories = (
            [
                category for category in requested
                if category in self.pack.product_categories
                and category in self.pack.default_bundle_categories
            ]
            if intent == "bundle"
            else [primary_category]
        )

        by_category: dict[str, list[dict[str, Any]]] = {}
        for category in required_categories:
            category_items = [item for item in items if item.get("category") == category]
            natural_best = max(
                (float(item.get("normalized_score", 0.0)) for item in category_items if not item.get("sponsored")),
                default=0.0,
            )
            protected = [
                item
                for item in category_items
                if not item.get("sponsored")
                or float(item.get("normalized_score", 0.0)) >= 0.85 * natural_best
            ]
            by_category[category] = sorted(
                protected,
                key=lambda item: (-float(item.get("normalized_score", 0.0)), str(item.get("sku_id", ""))),
            )[:8]

        bundles: list[dict[str, Any]] = []
        category_lists = [by_category.get(category, []) for category in required_categories]
        if all(category_lists):
            for combination in itertools.product(*category_lists):
                if len({str(item["sku_id"]) for item in combination}) != len(combination):
                    continue
                total = sum(float(item["price"]) for item in combination)
                if budget is not None and total > budget:
                    continue
                primary = next(item for item in combination if item["category"] == primary_category)
                primary_catalog = self.compatibility.items_by_sku.get(str(primary["sku_id"]))
                compatibility: list[dict[str, Any]] = []
                compatible = True
                for accessory in combination:
                    if accessory is primary:
                        continue
                    accessory_catalog = self.compatibility.items_by_sku.get(str(accessory["sku_id"]))
                    evaluated = (
                        self.compatibility._evaluate(primary_catalog, accessory_catalog)
                        if primary_catalog is not None and accessory_catalog is not None
                        else {"status": "unknown", "reasons": ["sku_not_found_in_graph"], "paths": []}
                    )
                    compatibility.append({
                        "product_id": primary["product_id"],
                        "accessory_id": accessory["product_id"],
                        "status": evaluated["status"],
                        "reasons": evaluated["reasons"],
                        "rule_version": self.compatibility.version,
                        "paths": evaluated["paths"],
                    })
                    if evaluated["status"] != "compatible":
                        compatible = False
                if not compatible:
                    continue
                relevance = sum(float(item.get("normalized_score", 0.0)) for item in combination)
                sponsored_count = sum(bool(item.get("sponsored")) for item in combination)
                budget_value = 0.0 if budget in (None, 0) else 1 - total / budget
                score = relevance + 0.08 * budget_value - 0.04 * sponsored_count
                bundles.append({
                    "sku_ids": [str(item["sku_id"]) for item in combination],
                    "total_price": round(total, 2),
                    "score": round(score, 6),
                    "sponsored_count": sponsored_count,
                    "compatibility": compatibility,
                })
        bundles.sort(key=lambda item: (-item["score"], item["total_price"], item["sku_ids"]))

        if not bundles:
            fallback = by_category.get(primary_category, [])[:1]
            if fallback:
                item = fallback[0]
                total = float(item["price"])
                bundles.append({
                    "sku_ids": [str(item["sku_id"])],
                    "total_price": round(total, 2),
                    "score": round(float(item.get("normalized_score", 0.0)), 6),
                    "sponsored_count": int(bool(item.get("sponsored"))),
                    "compatibility": [],
                })
        selected = bundles[:top_n]
        return {
            "optimizer_version": "constraint-enumeration-v2",
            "complete": bool(selected) and len(selected[0]["sku_ids"]) == len(required_categories),
            "bundles": selected,
        }


def create_retail_decision_service(
    catalog: RetailCatalogSnapshot | None = None,
    *,
    pack: RetailDomainPack | None = None,
    reviews: ReviewDataPort | None = None,
    pricing: PricingDataPort | None = None,
) -> RetailDecisionService:
    return RetailDecisionService(
        catalog, pack=pack, reviews=reviews, pricing=pricing
    )
