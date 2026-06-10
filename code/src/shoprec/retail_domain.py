from __future__ import annotations

import json
from pathlib import Path
from typing import Any


DOMAIN_PACK_PATH = Path(__file__).resolve().parent / "data" / "normal_3c_domain_v1.json"


def _load_domain_pack() -> dict[str, Any]:
    raw = json.loads(DOMAIN_PACK_PATH.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ValueError("normal 3C domain pack must be an object")
    for field in ("schema_version", "pack_id", "default_category", "primary_category"):
        if not isinstance(raw.get(field), str) or not raw[field].strip():
            raise ValueError(f"normal 3C domain pack {field} must be a non-empty string")
    for field in ("categories", "default_bundle_categories", "use_cases", "brands", "protocol_terms"):
        if not isinstance(raw.get(field), list) or not raw[field]:
            raise ValueError(f"normal 3C domain pack {field} must be a non-empty array")
    category_ids = [
        item.get("id")
        for item in raw["categories"]
        if isinstance(item, dict) and isinstance(item.get("id"), str) and item["id"].strip()
    ]
    if len(category_ids) != len(raw["categories"]) or len(set(category_ids)) != len(category_ids):
        raise ValueError("normal 3C domain pack category ids must be present and unique")
    declared = set(category_ids)
    referenced = [
        raw["default_category"],
        raw["primary_category"],
        *raw["default_bundle_categories"],
    ]
    if not all(isinstance(value, str) and value in declared for value in referenced):
        raise ValueError("normal 3C domain pack category references must be declared")
    return raw


NORMAL_3C_DOMAIN_PACK = _load_domain_pack()
PRODUCT_CATEGORIES = frozenset(
    str(item["id"])
    for item in NORMAL_3C_DOMAIN_PACK["categories"]
    if isinstance(item, dict) and item.get("id")
)
DEFAULT_CATEGORY = str(NORMAL_3C_DOMAIN_PACK["default_category"])
PRIMARY_CATEGORY = str(NORMAL_3C_DOMAIN_PACK["primary_category"])
DEFAULT_BUNDLE_CATEGORIES = tuple(
    str(item) for item in NORMAL_3C_DOMAIN_PACK["default_bundle_categories"]
)
COMMERCE_TERMS = tuple(dict.fromkeys(
    str(term).lower()
    for category in NORMAL_3C_DOMAIN_PACK["categories"]
    for term in category.get("terms", [])
)) + tuple(str(term).lower() for term in NORMAL_3C_DOMAIN_PACK["use_cases"]) + tuple(
    str(term).lower()
    for brand in NORMAL_3C_DOMAIN_PACK["brands"]
    for term in brand.get("terms", [])
) + tuple(str(term).lower() for term in NORMAL_3C_DOMAIN_PACK["protocol_terms"])
