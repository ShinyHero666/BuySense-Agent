#!/usr/bin/env python3
"""Synthetic retrieval regression, safety and scale gate for the v2 SAR data plane."""

from __future__ import annotations

import argparse
import json
import math
import statistics
import time
from pathlib import Path

from shoprec.retail_discovery import RetailDiscoveryService
from shoprec.retail_models import load_retail_catalog


def dcg(grades: list[int]) -> float:
    return sum((2**grade - 1) / math.log2(index + 2) for index, grade in enumerate(grades))


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, math.ceil(len(ordered) * fraction) - 1)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", type=Path, default=Path("data/v2"))
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()
    catalog = load_retail_catalog(args.data_dir / "scale_catalog_v2.json")
    service = RetailDiscoveryService(catalog)
    cases = json.loads((args.data_dir / "golden_queries_v2.json").read_text(encoding="utf-8"))
    recalls: list[float] = []
    ndcgs: list[float] = []
    latencies: list[float] = []
    filter_violations = 0
    ad_policy_violations = 0
    for case in cases:
        qrels = {
            str(product_id): int(grade)
            for product_id, grade in case["relevance_grades"].items()
        }
        payload = {
            "query": case["query"],
            "requested_categories": [case["expected_category"]],
            "use_cases": case["use_cases"],
            "preferred_brands": case["preferred_brands"],
            "primary_product_ids": [],
            "max_price": case["max_price"],
            "limit": 10,
            "sponsored_allowed": case["sponsored_allowed"],
            "identity_id": f"benchmark-{case['case_id']}",
            "session_id": f"session-{case['case_id']}",
            "personalization_enabled": False,
            "recent_product_ids": [],
            "excluded_product_ids": [],
            "ad_exposure_product_ids": [],
        }
        started = time.perf_counter()
        result = service.search(payload)
        latencies.append((time.perf_counter() - started) * 1000)
        predictions = result["items"]
        relevant = set(case["relevant_spu_ids"])
        hits = [item["product_id"] for item in predictions if item["product_id"] in relevant]
        recalls.append(len(set(hits)) / max(1, min(10, len(relevant))))
        grades = [qrels.get(item["product_id"], 0) for item in predictions]
        ideal = sorted(qrels.values(), reverse=True)[:10]
        ndcgs.append(0.0 if dcg(ideal) == 0 else dcg(grades) / dcg(ideal))
        filter_violations += sum(
            item["category"] != case["expected_category"]
            or item["price"] > case["max_price"]
            or item["stock"] <= 0
            for item in predictions
        )
        ads = service.ads(payload)["items"]
        ad_policy_violations += sum(
            (not case["sponsored_allowed"])
            or item["disclosure"] != "赞助"
            or item["ad_quality"] < 0.5
            for item in ads
        )
    report = {
        "suite": "normal-3c-retrieval-v2",
        "evaluation_kind": "synthetic_deterministic_regression",
        "label_provenance": "generated_from_catalog_attributes",
        "generated_at": catalog.generated_at,
        "catalog_version": catalog.catalog_version,
        "case_count": len(cases),
        "spu_count": len(catalog.spus),
        "metrics": {
            "recall_at_10": round(statistics.fmean(recalls), 6),
            "ndcg_at_10": round(statistics.fmean(ndcgs), 6),
            "p95_latency_ms": round(percentile(latencies, 0.95), 3),
            "filter_violations": filter_violations,
            "ad_policy_violations": ad_policy_violations,
        },
    }
    report["gates"] = {
        "recall_at_10": report["metrics"]["recall_at_10"] >= 0.70,
        "ndcg_at_10": report["metrics"]["ndcg_at_10"] >= 0.75,
        "p95_latency": report["metrics"]["p95_latency_ms"] < 200,
        "hard_filters": filter_violations == 0,
        "ad_policy": ad_policy_violations == 0,
    }
    report["passed"] = all(report["gates"].values())
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
