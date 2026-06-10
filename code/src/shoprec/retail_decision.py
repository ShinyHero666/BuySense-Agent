from __future__ import annotations

import hashlib
import json
import math
import itertools
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable

from .retail_models import RetailCatalogItem, RetailCatalogSnapshot, load_retail_catalog
from .retail_domain import DEFAULT_BUNDLE_CATEGORIES, PRIMARY_CATEGORY, PRODUCT_CATEGORIES
from .validation import ValidationError, reject_unknown_fields, require_mapping


@dataclass(frozen=True)
class CompatibilityRule:
    rule_id: str
    primary_category: str
    accessory_category: str
    required_shared_connectors: tuple[str, ...]
    required_shared_protocols: tuple[str, ...]


def _data_path(filename: str) -> Path:
    return Path(__file__).resolve().parent / "data" / filename


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


class ReviewAspectRepository:
    def __init__(self, path: str | Path | None = None) -> None:
        raw = json.loads(
            (Path(path) if path else _data_path("normal_3c_review_aspects_v1.json"))
            .read_text(encoding="utf-8")
        )
        self.version = _string(raw.get("review_snapshot_version"), "review_snapshot_version")
        products = raw.get("products")
        if not isinstance(products, list):
            raise ValueError("review products must be an array")
        self.products: dict[str, dict[str, Any]] = {}
        for index, product in enumerate(products):
            if not isinstance(product, dict) or not isinstance(product.get("aspects"), list):
                raise ValueError(f"review products[{index}] must contain an aspects array")
            product_id = _string(product.get("product_id"), f"products[{index}].product_id")
            if product_id in self.products:
                raise ValueError(f"duplicate review product_id: {product_id}")
            sample_size = product.get("sample_size")
            if isinstance(sample_size, bool) or not isinstance(sample_size, int) or sample_size < 0:
                raise ValueError(f"{product_id}.sample_size must be a non-negative integer")
            aspects: list[dict[str, Any]] = []
            for aspect_index, aspect in enumerate(product["aspects"]):
                field = f"{product_id}.aspects[{aspect_index}]"
                if not isinstance(aspect, dict):
                    raise ValueError(f"{field} must be an object")
                sentiment = aspect.get("sentiment")
                confidence = aspect.get("confidence")
                mentions = aspect.get("mention_count")
                if (
                    isinstance(sentiment, bool)
                    or not isinstance(sentiment, (int, float))
                    or not math.isfinite(sentiment)
                    or not -1 <= sentiment <= 1
                ):
                    raise ValueError(f"{field}.sentiment must be between -1 and 1")
                if (
                    isinstance(confidence, bool)
                    or not isinstance(confidence, (int, float))
                    or not math.isfinite(confidence)
                    or not 0 <= confidence <= 1
                ):
                    raise ValueError(f"{field}.confidence must be between 0 and 1")
                if isinstance(mentions, bool) or not isinstance(mentions, int) or mentions < 0:
                    raise ValueError(f"{field}.mention_count must be non-negative")
                aspects.append(
                    {
                        "aspect": _string(aspect.get("aspect"), f"{field}.aspect"),
                        "sentiment": float(sentiment),
                        "mention_count": mentions,
                        "confidence": float(confidence),
                        "summary": _string(aspect.get("summary"), f"{field}.summary"),
                    }
                )
            self.products[product_id] = {
                "product_id": product_id,
                "sample_size": sample_size,
                "aspects": aspects,
                "source": "synthetic_review_snapshot",
            }

    def get(self, payload: dict[str, Any]) -> dict[str, Any]:
        payload = require_mapping(payload)
        reject_unknown_fields(payload, {"product_ids"})
        product_ids = _string_list(payload.get("product_ids"), "product_ids")
        found = [self.products[product_id] for product_id in product_ids if product_id in self.products]
        missing = [product_id for product_id in product_ids if product_id not in self.products]
        return {
            "review_snapshot_version": self.version,
            "products": found,
            "missing_product_ids": missing,
        }


class CompatibilityGraphService:
    def __init__(
        self,
        catalog: RetailCatalogSnapshot,
        path: str | Path | None = None,
    ) -> None:
        raw = json.loads(
            (Path(path) if path else _data_path("normal_3c_compatibility_graph_v1.json"))
            .read_text(encoding="utf-8")
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


class PricingQuoteService:
    def __init__(
        self,
        catalog: RetailCatalogSnapshot,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self.catalog = catalog
        self.now = now or (lambda: datetime.now(timezone.utc))
        self.items_by_offer = {item.offer.offer_id: item for item in catalog.sellable_items()}

    def quote(self, payload: dict[str, Any]) -> dict[str, Any]:
        payload = require_mapping(payload)
        reject_unknown_fields(payload, {"offer_ids"})
        offer_ids = _string_list(payload.get("offer_ids"), "offer_ids")
        issued_at = self.now().astimezone(timezone.utc)
        ttl_end = issued_at + timedelta(minutes=5)
        quotes: list[dict[str, Any]] = []
        for offer_id in offer_ids:
            item = self.items_by_offer.get(offer_id)
            if item is None:
                quotes.append(
                    {
                        "offer_id": offer_id,
                        "status": "unavailable",
                        "amount": None,
                        "currency": "CNY",
                        "stock": 0,
                        "valid_until": issued_at.isoformat(),
                        "reason": "offer_not_found",
                    }
                )
                continue
            catalog_expiry = datetime.fromisoformat(
                item.offer.valid_until.replace("Z", "+00:00")
            )
            valid_until = min(ttl_end, catalog_expiry)
            active = item.offer.stock > 0 and valid_until > issued_at
            quotes.append(
                {
                    "offer_id": offer_id,
                    "status": "active" if active else "unavailable",
                    "amount": item.offer.price if active else None,
                    "currency": item.offer.currency,
                    "stock": item.offer.stock if active else 0,
                    "valid_until": valid_until.isoformat(),
                    "reason": "live_offer_snapshot" if active else "out_of_stock_or_expired",
                }
            )
        digest = hashlib.sha256(
            ("|".join(offer_ids) + issued_at.isoformat()).encode("utf-8")
        ).hexdigest()[:16]
        return {
            "quote_batch_id": f"quote-batch-{digest}",
            "quote_version": f"realtime-{self.catalog.quote_version}",
            "issued_at": issued_at.isoformat(),
            "quotes": quotes,
        }


class RetailDecisionService:
    def __init__(
        self,
        catalog: RetailCatalogSnapshot | None = None,
        *,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        runtime_catalog = catalog or load_retail_catalog()
        self.catalog = runtime_catalog
        self.reviews = ReviewAspectRepository()
        self.compatibility = CompatibilityGraphService(runtime_catalog)
        self.pricing = PricingQuoteService(runtime_catalog, now=now)

    def review_aspects(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self.reviews.get(payload)

    def check_compatibility(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self.compatibility.check(payload)

    def quote(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self.pricing.quote(payload)

    @staticmethod
    def _candidate_list(payload: dict[str, Any], field: str = "items") -> list[dict[str, Any]]:
        value = payload.get(field)
        if not isinstance(value, list) or len(value) > 200:
            raise ValidationError(field, "must be an array with at most 200 values")
        candidates: list[dict[str, Any]] = []
        for index, item in enumerate(value):
            if not isinstance(item, dict):
                raise ValidationError(f"{field}[{index}]", "must be an object")
            for required in ("sku_id", "product_id", "category", "price", "channel_score"):
                if required not in item:
                    raise ValidationError(f"{field}[{index}].{required}", "is required")
            candidates.append(dict(item))
        return candidates

    def fuse(self, payload: dict[str, Any]) -> dict[str, Any]:
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
            channel = channel_result.get("channel")
            if channel not in weights:
                raise ValidationError(f"channels[{channel_index}].channel", "is unsupported")
            items = channel_result.get("items")
            if not isinstance(items, list) or len(items) > 100:
                raise ValidationError(f"channels[{channel_index}].items", "must be an array")
            for rank, original in enumerate(items, start=1):
                if not isinstance(original, dict):
                    raise ValidationError("items", "candidate must be an object")
                candidate = dict(original)
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

    def optimize_bundles(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Enumerate bounded category combinations and return globally scored Top-N bundles."""
        payload = require_mapping(payload)
        reject_unknown_fields(
            payload,
            {"items", "requested_categories", "intent", "budget_max", "top_n"},
        )
        items = self._candidate_list(payload)
        requested = _string_list(payload.get("requested_categories"), "requested_categories")
        intent = _string(payload.get("intent"), "intent")
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
        primary_category = PRIMARY_CATEGORY if PRIMARY_CATEGORY in requested else requested[0]
        required_categories = (
            [
                category for category in requested
                if category in PRODUCT_CATEGORIES and category in DEFAULT_BUNDLE_CATEGORIES
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
) -> RetailDecisionService:
    return RetailDecisionService(catalog)
