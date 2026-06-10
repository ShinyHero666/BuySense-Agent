from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .service import CommerceDiscoveryService


SCENARIO_FIELDS = {"name", "description", "steps"}
STEP_FIELDS = {"name", "action", "payload", "expect"}
EXPECTATION_FIELDS = {
    "accepted",
    "all_items_match",
    "estimated_mid_between",
    "experiment",
    "fields_equal",
    "filter_reasons_contains",
    "item_ids_contains",
    "item_ids_excludes",
    "item_ids_prefix",
    "min_items",
    "query_blocked",
    "query_broad",
    "query_rewritten",
    "recall_source_present",
    "requires_inspection",
    "rerank_reason_present",
    "result_count",
    "risk_flag_present",
    "sorted_by",
    "top_item",
    "total",
    "trace_stages",
}


def _reject_unknown_fields(
    value: dict[str, Any],
    allowed: set[str],
    *,
    location: str,
) -> None:
    unknown = set(value) - allowed
    if unknown:
        raise ValueError(
            f"{location} has unknown fields: "
            + ", ".join(sorted(str(field) for field in unknown))
        )


def _assert_expectations(
    step_name: str,
    result: dict[str, Any],
    expected: dict[str, Any],
) -> None:
    item_ids = [item["product_id"] for item in result.get("items", [])]
    for product_id in expected.get("item_ids_contains", []):
        if product_id not in item_ids:
            raise AssertionError(
                f"{step_name}: expected product {product_id} in {item_ids}"
            )
    for product_id in expected.get("item_ids_excludes", []):
        if product_id in item_ids:
            raise AssertionError(
                f"{step_name}: expected product {product_id} to be absent"
            )
    if "min_items" in expected and len(item_ids) < int(expected["min_items"]):
        raise AssertionError(
            f"{step_name}: expected at least {expected['min_items']} items"
        )
    if "result_count" in expected and len(item_ids) != int(expected["result_count"]):
        raise AssertionError(
            f"{step_name}: expected {expected['result_count']} items, got {len(item_ids)}"
        )
    if "total" in expected and result.get("total") != int(expected["total"]):
        raise AssertionError(
            f"{step_name}: expected total={expected['total']}, got {result.get('total')}"
        )
    if "top_item" in expected:
        actual = item_ids[0] if item_ids else None
        if actual != expected["top_item"]:
            raise AssertionError(
                f"{step_name}: expected top item {expected['top_item']}, got {actual}"
            )
    if "item_ids_prefix" in expected:
        prefix = expected["item_ids_prefix"]
        if item_ids[: len(prefix)] != prefix:
            raise AssertionError(
                f"{step_name}: expected prefix {prefix}, got {item_ids}"
            )
    if "trace_stages" in expected:
        actual = [record["name"] for record in result.get("trace", [])]
        if actual != expected["trace_stages"]:
            raise AssertionError(
                f"{step_name}: trace mismatch\nexpected={expected['trace_stages']}\nactual={actual}"
            )
    if "experiment" in expected:
        if expected["experiment"] not in result.get("experiments", {}).values():
            raise AssertionError(
                f"{step_name}: expected experiment {expected['experiment']}, "
                f"got {result.get('experiments')}"
            )
    query = result.get("query_info", {})
    query_expectations = {
        "query_rewritten": "rewritten_query",
        "query_broad": "is_broad",
        "query_blocked": "blocked",
    }
    for expectation, field in query_expectations.items():
        if expectation in expected and query.get(field) != expected[expectation]:
            raise AssertionError(
                f"{step_name}: expected {field}={expected[expectation]!r}, "
                f"got {query.get(field)!r}"
            )
    for reason, minimum in expected.get("filter_reasons_contains", {}).items():
        actual = result.get("filter_reasons", {}).get(reason, 0)
        if actual < int(minimum):
            raise AssertionError(
                f"{step_name}: expected filter reason {reason}>={minimum}, got {actual}"
            )
    if "recall_source_present" in expected:
        source = expected["recall_source_present"]
        if not any(source in item.get("recall_sources", []) for item in result.get("items", [])):
            raise AssertionError(
                f"{step_name}: expected recall source {source!r} in result items"
            )
    if "rerank_reason_present" in expected:
        reason = expected["rerank_reason_present"]
        if not any(
            reason in item.get("rerank", {}).get("reasons", [])
            for item in result.get("items", [])
        ):
            raise AssertionError(
                f"{step_name}: expected rerank reason {reason!r} in result items"
            )
    for field, value in expected.get("all_items_match", {}).items():
        mismatches = [
            item.get("product_id")
            for item in result.get("items", [])
            if item.get(field) != value
        ]
        if mismatches:
            raise AssertionError(
                f"{step_name}: expected all items {field}={value!r}; "
                f"mismatches={mismatches}"
            )
    if "sorted_by" in expected:
        field = expected["sorted_by"]["field"]
        order = expected["sorted_by"].get("order", "asc")
        values = [item.get(field) for item in result.get("items", [])]
        sorted_values = sorted(values, reverse=order == "desc")
        if values != sorted_values:
            raise AssertionError(
                f"{step_name}: expected items sorted by {field} {order}; got {values}"
            )
    if "estimated_mid_between" in expected:
        lower, upper = expected["estimated_mid_between"]
        actual = result.get("estimated_mid")
        if actual is None or not int(lower) <= actual <= int(upper):
            raise AssertionError(
                f"{step_name}: expected estimated_mid between {lower} and {upper}, "
                f"got {actual}"
            )
    if (
        "requires_inspection" in expected
        and result.get("final_price_requires_inspection")
        != expected["requires_inspection"]
    ):
        raise AssertionError(
            f"{step_name}: expected final_price_requires_inspection="
            f"{expected['requires_inspection']}"
        )
    if "risk_flag_present" in expected:
        flag = expected["risk_flag_present"]
        if flag not in result.get("risk_flags", []):
            raise AssertionError(
                f"{step_name}: expected valuation risk flag {flag!r}"
            )
    if "accepted" in expected and result.get("accepted") != expected["accepted"]:
        raise AssertionError(
            f"{step_name}: expected accepted={expected['accepted']}"
        )
    fields_equal = expected.get("fields_equal", {})
    if not isinstance(fields_equal, dict):
        raise ValueError(f"{step_name}: fields_equal must be an object")
    for field, expected_value in fields_equal.items():
        actual = result.get(field)
        if actual != expected_value:
            raise AssertionError(
                f"{step_name}: expected {field}={expected_value!r}, got {actual!r}"
            )


def run_scenario(
    path: str | Path,
    service: CommerceDiscoveryService,
) -> dict[str, Any]:
    scenario_path = Path(path)
    raw = json.loads(scenario_path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ValueError(f"scenario {scenario_path} must be a JSON object")
    _reject_unknown_fields(raw, SCENARIO_FIELDS, location=f"scenario {scenario_path}")
    if not isinstance(raw.get("steps"), list) or not raw["steps"]:
        raise ValueError(f"scenario {scenario_path} must contain non-empty steps")

    results: list[dict[str, Any]] = []
    actions = {
        "search": service.search,
        "recommend": service.recommend,
        "valuation": service.value_device,
        "event": service.record_event,
    }
    for index, step in enumerate(raw["steps"], start=1):
        if not isinstance(step, dict):
            raise ValueError(f"scenario step {index} must be an object")
        _reject_unknown_fields(step, STEP_FIELDS, location=f"scenario step {index}")
        action_name = step.get("action")
        if action_name not in actions:
            raise ValueError(f"scenario step {index} has unknown action {action_name!r}")
        name = step.get("name", f"step-{index}")
        if not isinstance(name, str) or not name.strip():
            raise ValueError(f"scenario step {index} name must be a non-empty string")
        payload = step.get("payload", {})
        expected = step.get("expect", {})
        if not isinstance(payload, dict):
            raise ValueError(f"scenario step {index} payload must be an object")
        if not isinstance(expected, dict):
            raise ValueError(f"scenario step {index} expect must be an object")
        _reject_unknown_fields(
            expected,
            EXPECTATION_FIELDS,
            location=f"scenario step {index} expect",
        )
        result = actions[action_name](payload)
        _assert_expectations(name, result, expected)
        results.append(
            {
                "name": name,
                "action": action_name,
                "status": "PASS",
                "result": result,
            }
        )
    return {
        "scenario": raw.get("name", scenario_path.stem),
        "status": "PASS",
        "steps": results,
    }
