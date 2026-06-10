#!/usr/bin/env python3
"""Generate the reproducible normal-3C v2 catalog and synthetic regression suite."""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path


SEED = 20260802
CATEGORIES = {
    "phone": {
        "cn": "手机",
        "base": 3200,
        "tags": ["拍照", "续航", "游戏", "人像", "轻便", "快充"],
        "connectors": ["usb-c", "bluetooth"],
        "protocols": ["usb-pd", "pps", "bluetooth-aac"],
    },
    "headphones": {
        "cn": "降噪耳机",
        "base": 700,
        "tags": ["降噪", "音质", "通勤", "游戏", "便携"],
        "connectors": ["usb-c", "bluetooth"],
        "protocols": ["bluetooth-aac", "bluetooth-ldac"],
    },
    "charger": {
        "cn": "充电器",
        "base": 180,
        "tags": ["快充", "便携", "多口", "旅行"],
        "connectors": ["usb-c"],
        "protocols": ["usb-pd", "pps"],
    },
    "cable": {
        "cn": "数据线",
        "base": 60,
        "tags": ["快充", "耐用", "便携", "编织"],
        "connectors": ["usb-c"],
        "protocols": ["usb-pd"],
    },
    "case": {
        "cn": "手机壳",
        "base": 90,
        "tags": ["防摔", "轻薄", "透明", "耐用"],
        "connectors": ["usb-c"],
        "protocols": [],
    },
}
BRANDS = [
    "Apple", "Xiaomi", "HONOR", "Huawei", "Samsung", "OPPO",
    "vivo", "Soundcore", "UGREEN", "Baseus", "Anker", "Moyuan",
]


def build_catalog(spu_count: int) -> dict:
    rng = random.Random(SEED)
    spus = []
    categories = list(CATEGORIES)
    for index in range(spu_count):
        category = categories[index % len(categories)]
        config = CATEGORIES[category]
        brand = BRANDS[(index * 7 + index // len(categories)) % len(BRANDS)]
        tags = rng.sample(config["tags"], k=min(3, len(config["tags"])))
        price = round(config["base"] * (0.55 + (index % 19) * 0.055), 2)
        spu_id = f"scale-{category}-{index:05d}"
        sku_id = f"sku-{category}-{index:05d}"
        sponsored = index % 7 == 0
        offer = {
            "offer_id": f"offer-{category}-{index:05d}",
            "seller_id": f"seller-{index % 31:02d}",
            "price": price,
            "currency": "CNY",
            "stock": 0 if index % 53 == 0 else 5 + index % 90,
            "sponsored": sponsored,
            "valid_until": "2099-01-01T00:00:00Z",
        }
        if sponsored:
            offer.update({
                "ad_bid": round(0.5 + (index % 20) * 0.12, 2),
                "ad_quality": round(0.45 + (index % 11) * 0.05, 2),
            })
        spus.append({
            "spu_id": spu_id,
            "title": f"{brand} {config['cn']} {index:04d}",
            "category": category,
            "brand": brand,
            "tags": [config["cn"], *tags],
            "skus": [{
                "sku_id": sku_id,
                "title": f"{brand} {config['cn']} {index:04d} 标准版",
                "ecosystem": "ios" if brand == "Apple" else "universal" if category != "phone" else "android",
                "connectors": config["connectors"],
                "protocols": config["protocols"],
                **({"max_power_watts": 20 + index % 101} if category in {"phone", "charger", "cable"} else {}),
                "offers": [offer],
            }],
        })
    return {
        "catalog_version": f"normal-3c-scale-{spu_count}-seed-{SEED}",
        "quote_version": "scale-quote-v2",
        "generated_at": "2026-08-02T00:00:00Z",
        "spus": spus,
    }


def build_synthetic_suite(catalog: dict, case_count: int) -> list[dict]:
    flattened = [
        {
            "spu_id": spu["spu_id"],
            "category": spu["category"],
            "brand": spu["brand"],
            "tags": spu["tags"],
            "price": spu["skus"][0]["offers"][0]["price"],
            "stock": spu["skus"][0]["offers"][0]["stock"],
        }
        for spu in catalog["spus"]
    ]
    cases = []
    categories = list(CATEGORIES)
    intents = ["exploratory", "catalog", "compare"]
    for index in range(case_count):
        category = categories[index % len(categories)]
        config = CATEGORIES[category]
        intent = intents[index % len(intents)]
        use_case = config["tags"][index % len(config["tags"])]
        requested_brand = BRANDS[(index * 5 + 3) % len(BRANDS)] if index % 3 != 0 else None
        budget = round(config["base"] * (1.0 + (index % 5) * 0.18), 2)
        opt_out = index % 4 == 0
        eligible = [
            item for item in flattened
            if item["category"] == category
            and item["stock"] > 0
            and item["price"] <= budget
        ]
        brand = requested_brand
        if brand is not None and not any(item["brand"] == brand for item in eligible):
            brand = None
        subject = f"{brand + ' ' if brand else ''}{use_case}{config['cn']}"
        if intent == "exploratory":
            message = f"预算不超过{int(budget)}元，帮我推荐{subject}"
        elif intent == "compare":
            message = f"预算不超过{int(budget)}元，比较几款{subject}"
        else:
            message = f"预算不超过{int(budget)}元，想看{subject}"
        if opt_out:
            message += "，不要广告"
        relevance_grades = {
            item["spu_id"]: (
                3
                if brand is not None and item["brand"] == brand and use_case in item["tags"]
                else 2
                if use_case in item["tags"]
                else 1
            )
            for item in eligible
        }
        relevant = sorted(eligible, key=lambda item: (
            -relevance_grades[item["spu_id"]],
            item["price"],
            item["spu_id"],
        ))
        cases.append({
            "case_id": f"gold-v2-{index + 1:03d}",
            "curation": "synthetic deterministic scenario and rule-derived relevance judgments",
            "label_provenance": "generated_from_catalog_attributes",
            "message": message,
            "query": message,
            "expected_intent": intent,
            "expected_category": category,
            "preferred_brands": [brand] if brand else [],
            "use_cases": [use_case],
            "max_price": budget,
            "sponsored_allowed": not opt_out,
            "relevant_spu_ids": [item["spu_id"] for item in relevant[:10]],
            "relevance_grades": relevance_grades,
        })
    return cases


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=Path("data/v2"))
    parser.add_argument("--spu-count", type=int, default=1200)
    parser.add_argument("--case-count", type=int, default=120)
    args = parser.parse_args()
    if not 1000 <= args.spu_count <= 3000:
        raise SystemExit("--spu-count must be between 1000 and 3000")
    if not 100 <= args.case_count <= 200:
        raise SystemExit("--case-count must be between 100 and 200")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    catalog = build_catalog(args.spu_count)
    synthetic_cases = build_synthetic_suite(catalog, args.case_count)
    (args.output_dir / "scale_catalog_v2.json").write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (args.output_dir / "golden_queries_v2.json").write_text(
        json.dumps(synthetic_cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    manifest = {
        "schema_version": "2.0",
        "evaluation_kind": "synthetic_deterministic_regression",
        "label_provenance": "generated_from_catalog_attributes",
        "seed": SEED,
        "spu_count": len(catalog["spus"]),
        "sku_count": sum(len(spu["skus"]) for spu in catalog["spus"]),
        "offer_count": sum(len(sku["offers"]) for spu in catalog["spus"] for sku in spu["skus"]),
        "query_case_count": len(synthetic_cases),
        "categories": list(CATEGORIES),
        "generator": "scripts/generate_v2_benchmarks.py",
    }
    (args.output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == "__main__":
    main()
