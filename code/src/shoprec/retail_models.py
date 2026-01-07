from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from .retail_domain import NORMAL_3C_DOMAIN_PACK_MODEL, RetailDomainPack

ECOSYSTEMS = frozenset({"ios", "android", "universal"})
SAFE_VERSION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


@dataclass(frozen=True)
class RetailOffer:
    offer_id: str
    seller_id: str
    price: float
    currency: str
    stock: int
    sponsored: bool
    valid_until: str
    ad_bid: float = 0.0
    ad_quality: float = 0.0


@dataclass(frozen=True)
class RetailSku:
    sku_id: str
    title: str
    ecosystem: str
    connectors: tuple[str, ...]
    protocols: tuple[str, ...]
    offers: tuple[RetailOffer, ...]
    max_power_watts: int | None = None


@dataclass(frozen=True)
class RetailSpu:
    spu_id: str
    title: str
    category: str
    brand: str
    tags: tuple[str, ...]
    skus: tuple[RetailSku, ...]


@dataclass(frozen=True)
class RetailCatalogItem:
    spu: RetailSpu
    sku: RetailSku
    offer: RetailOffer


@dataclass(frozen=True)
class RetailCatalogSnapshot:
    catalog_version: str
    quote_version: str
    generated_at: str
    spus: tuple[RetailSpu, ...]

    def sellable_items(self) -> tuple[RetailCatalogItem, ...]:
        return tuple(
            RetailCatalogItem(spu=spu, sku=sku, offer=offer)
            for spu in self.spus
            for sku in spu.skus
            for offer in sku.offers
        )


def default_catalog_path(pack: RetailDomainPack | None = None) -> Path:
    return (pack or NORMAL_3C_DOMAIN_PACK_MODEL).asset_path("catalog")


def _non_empty_string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be a non-empty string")
    result = value.strip()
    if len(result) > 500:
        raise ValueError(f"{field} must contain at most 500 characters")
    return result


def _string_tuple(value: Any, field: str) -> tuple[str, ...]:
    if not isinstance(value, list) or not all(
        isinstance(item, str) and item.strip() for item in value
    ):
        raise ValueError(f"{field} must be an array of non-empty strings")
    if len(value) > 100:
        raise ValueError(f"{field} must contain at most 100 values")
    return tuple(item.strip() for item in value)


def _mapping(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{field} must be an object")
    return value


def _reject_unknown_fields(
    value: dict[str, Any], allowed: set[str], field: str
) -> None:
    unknown = sorted(set(value) - allowed)
    if unknown:
        raise ValueError(f"{field} contains unknown fields: {', '.join(unknown)}")


def _timestamp(value: Any, field: str) -> str:
    result = _non_empty_string(value, field)
    try:
        parsed = datetime.fromisoformat(result.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{field} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise ValueError(f"{field} must include a timezone")
    return result


def _version(value: Any, field: str) -> str:
    result = _non_empty_string(value, field)
    if SAFE_VERSION.fullmatch(result) is None:
        raise ValueError(f"{field} must be a safe version identifier")
    return result


def _offer(raw: dict[str, Any], field: str) -> RetailOffer:
    _reject_unknown_fields(
        raw,
        {
            "offer_id",
            "seller_id",
            "price",
            "currency",
            "stock",
            "sponsored",
            "valid_until",
            "ad_bid",
            "ad_quality",
        },
        field,
    )
    price = raw.get("price")
    stock = raw.get("stock")
    sponsored = raw.get("sponsored")
    if (
        isinstance(price, bool)
        or not isinstance(price, (int, float))
        or not math.isfinite(price)
        or price < 0
    ):
        raise ValueError(f"{field}.price must be a finite non-negative number")
    if isinstance(stock, bool) or not isinstance(stock, int) or stock < 0:
        raise ValueError(f"{field}.stock must be a non-negative integer")
    if not isinstance(sponsored, bool):
        raise ValueError(f"{field}.sponsored must be a boolean")
    currency = _non_empty_string(raw.get("currency"), f"{field}.currency")
    if currency != "CNY":
        raise ValueError(f"{field}.currency must be CNY")
    valid_until = _timestamp(raw.get("valid_until"), f"{field}.valid_until")
    ad_bid = raw.get("ad_bid", 0.0)
    ad_quality = raw.get("ad_quality", 0.0)
    if (
        isinstance(ad_bid, bool)
        or not isinstance(ad_bid, (int, float))
        or not math.isfinite(ad_bid)
        or ad_bid < 0
    ):
        raise ValueError(f"{field}.ad_bid must be a finite non-negative number")
    if (
        isinstance(ad_quality, bool)
        or not isinstance(ad_quality, (int, float))
        or not math.isfinite(ad_quality)
        or not 0 <= ad_quality <= 1
    ):
        raise ValueError(f"{field}.ad_quality must be between 0 and 1")
    return RetailOffer(
        offer_id=_non_empty_string(raw.get("offer_id"), f"{field}.offer_id"),
        seller_id=_non_empty_string(raw.get("seller_id"), f"{field}.seller_id"),
        price=float(price),
        currency=currency,
        stock=stock,
        sponsored=sponsored,
        valid_until=valid_until,
        ad_bid=float(ad_bid),
        ad_quality=float(ad_quality),
    )


def parse_retail_catalog(
    raw: Any,
    *,
    pack: RetailDomainPack | None = None,
) -> RetailCatalogSnapshot:
    runtime_pack = pack or NORMAL_3C_DOMAIN_PACK_MODEL
    if not isinstance(raw, dict) or not isinstance(raw.get("spus"), list):
        raise ValueError("retail catalog must contain a spus array")
    _reject_unknown_fields(
        raw,
        {"catalog_version", "quote_version", "generated_at", "spus", "data_source"},
        "retail catalog",
    )
    if len(raw["spus"]) > 2000:
        raise ValueError("retail catalog must contain at most 2000 spus")

    seen_spus: set[str] = set()
    seen_skus: set[str] = set()
    seen_offers: set[str] = set()
    spus: list[RetailSpu] = []
    for spu_index, raw_spu in enumerate(raw["spus"]):
        if not isinstance(raw_spu, dict) or not isinstance(raw_spu.get("skus"), list):
            raise ValueError(f"spus[{spu_index}] must contain a skus array")
        _reject_unknown_fields(
            raw_spu,
            {"spu_id", "title", "category", "brand", "tags", "skus"},
            f"spus[{spu_index}]",
        )
        if len(raw_spu["skus"]) > 100:
            raise ValueError(f"spus[{spu_index}].skus must contain at most 100 values")
        spu_id = _non_empty_string(raw_spu.get("spu_id"), f"spus[{spu_index}].spu_id")
        if spu_id in seen_spus:
            raise ValueError(f"duplicate spu_id: {spu_id}")
        seen_spus.add(spu_id)
        category = _non_empty_string(raw_spu.get("category"), f"{spu_id}.category")
        if category not in runtime_pack.product_categories:
            raise ValueError(f"unknown product category: {category}")

        skus: list[RetailSku] = []
        for sku_index, raw_sku in enumerate(raw_spu["skus"]):
            if not isinstance(raw_sku, dict) or not isinstance(raw_sku.get("offers"), list):
                raise ValueError(f"{spu_id}.skus[{sku_index}] must contain an offers array")
            _reject_unknown_fields(
                raw_sku,
                {
                    "sku_id",
                    "title",
                    "ecosystem",
                    "connectors",
                    "protocols",
                    "offers",
                    "max_power_watts",
                },
                f"{spu_id}.skus[{sku_index}]",
            )
            if len(raw_sku["offers"]) > 100:
                raise ValueError(
                    f"{spu_id}.skus[{sku_index}].offers must contain at most 100 values"
                )
            sku_id = _non_empty_string(raw_sku.get("sku_id"), f"{spu_id}.sku_id")
            if sku_id in seen_skus:
                raise ValueError(f"duplicate sku_id: {sku_id}")
            seen_skus.add(sku_id)
            ecosystem = _non_empty_string(raw_sku.get("ecosystem"), f"{sku_id}.ecosystem")
            if ecosystem not in ECOSYSTEMS:
                raise ValueError(f"unknown ecosystem: {ecosystem}")
            offers = tuple(
                _offer(
                    _mapping(raw_offer, f"{sku_id}.offers[{offer_index}]"),
                    f"{sku_id}.offers[{offer_index}]",
                )
                for offer_index, raw_offer in enumerate(raw_sku["offers"])
            )
            if not offers:
                raise ValueError(f"{sku_id} must contain at least one offer")
            for offer in offers:
                if offer.offer_id in seen_offers:
                    raise ValueError(f"duplicate offer_id: {offer.offer_id}")
                seen_offers.add(offer.offer_id)
            maximum_power = raw_sku.get("max_power_watts")
            if maximum_power is not None and (
                isinstance(maximum_power, bool)
                or not isinstance(maximum_power, int)
                or maximum_power <= 0
            ):
                raise ValueError(f"{sku_id}.max_power_watts must be a positive integer")
            skus.append(
                RetailSku(
                    sku_id=sku_id,
                    title=_non_empty_string(raw_sku.get("title"), f"{sku_id}.title"),
                    ecosystem=ecosystem,
                    connectors=_string_tuple(raw_sku.get("connectors"), f"{sku_id}.connectors"),
                    protocols=_string_tuple(raw_sku.get("protocols"), f"{sku_id}.protocols"),
                    offers=offers,
                    max_power_watts=maximum_power,
                )
            )
        if not skus:
            raise ValueError(f"{spu_id} must contain at least one sku")
        spus.append(
            RetailSpu(
                spu_id=spu_id,
                title=_non_empty_string(raw_spu.get("title"), f"{spu_id}.title"),
                category=category,
                brand=_non_empty_string(raw_spu.get("brand"), f"{spu_id}.brand"),
                tags=_string_tuple(raw_spu.get("tags"), f"{spu_id}.tags"),
                skus=tuple(skus),
            )
        )

    return RetailCatalogSnapshot(
        catalog_version=_version(raw.get("catalog_version"), "catalog_version"),
        quote_version=_version(raw.get("quote_version"), "quote_version"),
        generated_at=_timestamp(raw.get("generated_at"), "generated_at"),
        spus=tuple(spus),
    )


def load_retail_catalog(
    path: str | Path | None = None,
    *,
    pack: RetailDomainPack | None = None,
) -> RetailCatalogSnapshot:
    runtime_pack = pack or NORMAL_3C_DOMAIN_PACK_MODEL
    source = Path(path) if path else default_catalog_path(runtime_pack)
    return parse_retail_catalog(
        json.loads(source.read_text(encoding="utf-8")),
        pack=runtime_pack,
    )
