from __future__ import annotations

import math
import hashlib
import re
from dataclasses import dataclass
from typing import Any

from .retail_models import (
    PRODUCT_CATEGORIES,
    RetailCatalogItem,
    RetailCatalogSnapshot,
    load_retail_catalog,
)
from .retail_domain import COMMERCE_TERMS, DEFAULT_CATEGORY, PRIMARY_CATEGORY
from .validation import (
    ValidationError,
    boolean_value,
    integer_value,
    reject_unknown_fields,
    require_mapping,
    string_value,
)


CHANNELS = frozenset({"search", "recommendation", "ads"})


@dataclass(frozen=True)
class RetailDiscoveryRequest:
    query: str
    requested_categories: tuple[str, ...]
    use_cases: tuple[str, ...]
    preferred_brands: tuple[str, ...]
    primary_product_ids: tuple[str, ...]
    max_price: float | None
    limit: int
    sponsored_allowed: bool
    identity_id: str
    session_id: str
    personalization_enabled: bool
    recent_product_ids: tuple[str, ...]
    excluded_product_ids: tuple[str, ...]
    ad_exposure_product_ids: tuple[str, ...]


def _clamp(value: float) -> float:
    return max(0.0, min(1.0, value))


class RetailDiscoveryService:
    """Read-only normal-commerce search, recommendation and ads data plane."""

    def __init__(self, catalog: RetailCatalogSnapshot | None = None) -> None:
        self.catalog = catalog or load_retail_catalog()
        self.items = self.catalog.sellable_items()
        self.items_by_product_id = {item.spu.spu_id: item for item in self.items}

    @staticmethod
    def _string_tuple(payload: dict[str, Any], field: str) -> tuple[str, ...]:
        value = payload.get(field, [])
        if not isinstance(value, list) or not all(
            isinstance(item, str) and item.strip() for item in value
        ):
            raise ValidationError(field, "must be an array of non-empty strings")
        return tuple(item.strip() for item in value)

    @staticmethod
    def _optional_price(payload: dict[str, Any]) -> float | None:
        value = payload.get("max_price")
        if value is None:
            return None
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValidationError("max_price", "must be a number or null")
        result = float(value)
        if not math.isfinite(result) or result < 0:
            raise ValidationError("max_price", "must be a finite non-negative number")
        return result

    @classmethod
    def _request(cls, payload: dict[str, Any]) -> RetailDiscoveryRequest:
        payload = require_mapping(payload)
        reject_unknown_fields(
            payload,
            {
                "query",
                "requested_categories",
                "use_cases",
                "preferred_brands",
                "primary_product_ids",
                "max_price",
                "limit",
                "sponsored_allowed",
                "identity_id",
                "session_id",
                "personalization_enabled",
                "recent_product_ids",
                "excluded_product_ids",
                "ad_exposure_product_ids",
            },
        )
        categories = cls._string_tuple(payload, "requested_categories")
        unknown_categories = set(categories) - PRODUCT_CATEGORIES
        if unknown_categories:
            raise ValidationError(
                "requested_categories",
                "unknown values: " + ", ".join(sorted(unknown_categories)),
            )
        return RetailDiscoveryRequest(
            query=string_value(payload, "query", "", max_length=256),
            requested_categories=categories or (DEFAULT_CATEGORY,),
            use_cases=cls._string_tuple(payload, "use_cases"),
            preferred_brands=cls._string_tuple(payload, "preferred_brands"),
            primary_product_ids=cls._string_tuple(payload, "primary_product_ids"),
            max_price=cls._optional_price(payload),
            limit=integer_value(payload, "limit", 8, minimum=1, maximum=100),
            sponsored_allowed=boolean_value(payload, "sponsored_allowed", True),
            identity_id=string_value(payload, "identity_id", "", max_length=128),
            session_id=string_value(payload, "session_id", "", max_length=128),
            personalization_enabled=boolean_value(
                payload, "personalization_enabled", True
            ),
            recent_product_ids=cls._string_tuple(payload, "recent_product_ids"),
            excluded_product_ids=cls._string_tuple(payload, "excluded_product_ids"),
            ad_exposure_product_ids=cls._string_tuple(
                payload, "ad_exposure_product_ids"
            ),
        )

    @staticmethod
    def _term_fit(item: RetailCatalogItem, request: RetailDiscoveryRequest) -> float:
        haystack = " ".join(
            (item.spu.title, item.sku.title, item.spu.brand, *item.spu.tags)
        ).lower()
        normalized_query = request.query.lower()
        query_terms = [term for term in COMMERCE_TERMS if term in normalized_query]
        query_terms.extend(
            token
            for token in re.findall(r"[a-z][a-z0-9-]{1,20}|\d{2,3}w", normalized_query)
            if token not in {"gb"}
        )
        terms = tuple(dict.fromkeys((*query_terms, *request.use_cases, *request.preferred_brands)))
        if not terms:
            return 0.5
        return sum(term.lower() in haystack for term in terms) / len(terms)

    @staticmethod
    def _stable_affinity(identity_id: str, value: str) -> float:
        if not identity_id:
            return 0.0
        digest = hashlib.sha256(f"{identity_id}|{value}".encode("utf-8")).digest()
        return int.from_bytes(digest[:2], "big") / 65535

    def _personalization_fit(
        self, item: RetailCatalogItem, request: RetailDiscoveryRequest
    ) -> tuple[float, list[str]]:
        if not request.personalization_enabled:
            return 0.0, ["personalization_opted_out"]
        recent = [
            self.items_by_product_id[product_id]
            for product_id in request.recent_product_ids
            if product_id in self.items_by_product_id
        ]
        short_term = 0.0
        if recent:
            if any(previous.spu.brand == item.spu.brand for previous in recent):
                short_term += 0.08
            if any(previous.spu.category == item.spu.category for previous in recent):
                short_term += 0.04
        long_term = 0.06 * self._stable_affinity(request.identity_id, item.spu.brand)
        reasons = [f"personalization={short_term + long_term:.3f}"]
        if short_term:
            reasons.append("session_or_recent_affinity")
        return short_term + long_term, reasons

    @staticmethod
    def _eligible(item: RetailCatalogItem, request: RetailDiscoveryRequest) -> bool:
        return (
            item.offer.stock > 0
            and item.spu.category in request.requested_categories
            and (request.max_price is None or item.offer.price <= request.max_price)
            and item.spu.spu_id not in request.excluded_product_ids
        )

    @staticmethod
    def _primary_accessory_eligible(
        item: RetailCatalogItem, request: RetailDiscoveryRequest
    ) -> bool:
        if PRIMARY_CATEGORY not in request.requested_categories:
            return True
        if item.spu.category not in {"charger", "cable"}:
            return True
        return "usb-c" in item.sku.connectors and any(
            protocol in {"usb-pd", "pps"} for protocol in item.sku.protocols
        )

    def _candidate(
        self,
        item: RetailCatalogItem,
        channel: str,
        score: float,
        reasons: list[str],
    ) -> dict[str, Any]:
        return {
            "spu_id": item.spu.spu_id,
            "product_id": item.spu.spu_id,
            "sku_id": item.sku.sku_id,
            "offer_id": item.offer.offer_id,
            "title": item.sku.title,
            "category": item.spu.category,
            "brand": item.spu.brand,
            "price": item.offer.price,
            "currency": item.offer.currency,
            "stock": item.offer.stock,
            "tags": list(item.spu.tags),
            "ecosystem": item.sku.ecosystem,
            "connectors": list(item.sku.connectors),
            "protocols": list(item.sku.protocols),
            "max_power_watts": item.sku.max_power_watts,
            "catalog_version": self.catalog.catalog_version,
            "quote_version": self.catalog.quote_version,
            "quote_valid_until": item.offer.valid_until,
            "ad_bid": item.offer.ad_bid,
            "ad_quality": item.offer.ad_quality,
            "channel": channel,
            "channel_score": round(_clamp(score), 6),
            "normalized_score": 0.0,
            "reasons": reasons,
            "sponsored": channel == "ads",
            "disclosure": "赞助" if channel == "ads" else None,
        }

    @staticmethod
    def _top(candidates: list[dict[str, Any]], limit: int) -> list[dict[str, Any]]:
        candidates.sort(key=lambda item: (-item["channel_score"], item["sku_id"]))
        selected = candidates[:limit]
        if not selected:
            return []
        scores = [item["channel_score"] for item in selected]
        minimum, maximum = min(scores), max(scores)
        for item in selected:
            item["normalized_score"] = round(
                1.0
                if maximum == minimum
                else (item["channel_score"] - minimum) / (maximum - minimum),
                6,
            )
        return selected

    def _response(self, channel: str, items: list[dict[str, Any]]) -> dict[str, Any]:
        return {
            "channel": channel,
            "catalog_version": self.catalog.catalog_version,
            "quote_version": self.catalog.quote_version,
            "items": items,
        }

    def search(self, payload: dict[str, Any]) -> dict[str, Any]:
        request = self._request(payload)
        primary_category = (
            PRIMARY_CATEGORY
            if PRIMARY_CATEGORY in request.requested_categories
            else request.requested_categories[0]
        )
        candidates: list[dict[str, Any]] = []
        for item in self.items:
            if not self._eligible(item, request) or item.spu.category != primary_category:
                continue
            term_fit = self._term_fit(item, request)
            personalization, personalization_reasons = self._personalization_fit(item, request)
            brand_fit = 0.2 if item.spu.brand in request.preferred_brands else 0.0
            value = (
                0.15 * (1 - item.offer.price / request.max_price)
                if request.max_price
                else 0.05
            )
            candidates.append(
                self._candidate(
                    item,
                    "search",
                    0.52 + 0.25 * term_fit + brand_fit + value + personalization,
                    [
                        "structured_filter_passed",
                        f"query_term_fit={term_fit:.2f}",
                        *personalization_reasons,
                    ],
                )
            )
        return self._response("search", self._top(candidates, request.limit))

    def recommend(self, payload: dict[str, Any]) -> dict[str, Any]:
        request = self._request(payload)
        accessory_categories = {
            category
            for category in request.requested_categories
            if category != PRIMARY_CATEGORY
        }
        allowed_categories = accessory_categories or {PRIMARY_CATEGORY}
        peer_ecosystems = {
            self.items_by_product_id[product_id].sku.ecosystem
            for product_id in request.primary_product_ids
            if product_id in self.items_by_product_id
        }
        candidates: list[dict[str, Any]] = []
        for item in self.items:
            if (
                not self._eligible(item, request)
                or item.spu.category not in allowed_categories
                or not self._primary_accessory_eligible(item, request)
            ):
                continue
            term_fit = self._term_fit(item, request)
            personalization, personalization_reasons = self._personalization_fit(item, request)
            universal = 0.12 if item.sku.ecosystem == "universal" else 0.0
            peer_fit = (
                0.08
                if peer_ecosystems
                and (
                    item.sku.ecosystem == "universal"
                    or item.sku.ecosystem in peer_ecosystems
                )
                else 0.0
            )
            value = (
                0.18 * (1 - item.offer.price / request.max_price)
                if request.max_price
                else 0.05
            )
            reasons = [
                "session_intent_match",
                "universal_ecosystem" if universal else "ecosystem_specific",
                *personalization_reasons,
            ]
            if peer_fit:
                reasons.append("search_peer_context_match")
            candidates.append(
                self._candidate(
                    item,
                    "recommendation",
                    0.42 + 0.25 * term_fit + universal + peer_fit + value + personalization,
                    reasons,
                )
            )
        return self._response(
            "recommendation", self._top(candidates, request.limit)
        )

    def ads(self, payload: dict[str, Any]) -> dict[str, Any]:
        request = self._request(payload)
        if not request.sponsored_allowed:
            return self._response("ads", [])
        candidates: list[dict[str, Any]] = []
        for item in self.items:
            if not item.offer.sponsored or not self._eligible(item, request):
                continue
            category_fit = 1.0 if item.spu.category in request.requested_categories else 0.0
            term_fit = self._term_fit(item, request)
            relevance = 0.65 * category_fit + 0.35 * term_fit
            if relevance < 0.45 or item.offer.ad_quality < 0.5:
                continue
            bid = min(1.0, item.offer.ad_bid / 3)
            exposure_penalty = (
                0.2 if item.spu.spu_id in request.ad_exposure_product_ids else 0.0
            )
            candidates.append(
                self._candidate(
                    item,
                    "ads",
                    0.7 * relevance
                    + 0.2 * item.offer.ad_quality
                    + 0.1 * bid
                    - exposure_penalty,
                    [
                        f"ad_relevance={relevance:.2f}",
                        "sponsored_disclosure_required",
                        *(["ad_frequency_penalty"] if exposure_penalty else []),
                    ],
                )
            )
        return self._response("ads", self._top(candidates, request.limit))


def create_retail_discovery_service() -> RetailDiscoveryService:
    return RetailDiscoveryService()
