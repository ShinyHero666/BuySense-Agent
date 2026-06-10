from __future__ import annotations

import unittest

from shoprec.validation import ValidationError
from tests.support import fixed_service


class SecondHandBusinessCaseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.service, _ = fixed_service()

    def test_pre_inspection_quote_is_explainable_and_deterministic(self) -> None:
        result = self.service.value_device(
            {
                "brand": "Apple",
                "model": "iPhone 13",
                "storage_gb": 128,
                "age_months": 36,
                "condition_grade": "B",
                "battery_health": 90,
                "inspection_method": "door",
            }
        )
        self.assertEqual(result["request_id"], "valuation-000001")
        self.assertLess(result["estimated_low"], result["estimated_mid"])
        self.assertLess(result["estimated_mid"], result["estimated_high"])
        self.assertTrue(result["final_price_requires_inspection"])
        self.assertEqual(result["reference_source"], "model_reference")
        self.assertIn("condition", result["factors"])

    def test_declared_damage_reduces_quote_and_adds_risk_flags(self) -> None:
        clean = self.service.value_device(
            {
                "brand": "Apple",
                "model": "iPhone 13",
                "condition_grade": "B",
                "battery_health": 90,
            }
        )
        damaged = self.service.value_device(
            {
                "brand": "Apple",
                "model": "iPhone 13",
                "condition_grade": "C",
                "battery_health": 78,
                "functional_issues": ["water_damage"],
            }
        )
        self.assertLess(damaged["estimated_mid"], clean["estimated_mid"])
        self.assertIn("BATTERY_BELOW_80", damaged["risk_flags"])
        self.assertIn("MANUAL_REVIEW_REQUIRED", damaged["risk_flags"])

    def test_platform_inspection_filters_are_visible_in_results(self) -> None:
        result = self.service.search(
            {
                "query": "iphone",
                "filters": {
                    "categories": ["手机"],
                    "inspection_grades": ["A", "B"],
                    "min_battery_health": 85,
                    "warranty_required": True,
                    "max_price": 4000,
                },
            }
        )
        self.assertTrue(result["items"])
        self.assertNotIn(
            "p017", {item["product_id"] for item in result["items"]}
        )
        self.assertTrue(
            all(
                item["service"]["inspection_status"] == "passed"
                and item["service"]["battery_health"] >= 85
                and item["service"]["warranty_days"] > 0
                and "trust" in item["features"]
                for item in result["items"]
            )
        )

    def test_unknown_valuation_issue_is_rejected(self) -> None:
        with self.assertRaises(ValidationError):
            self.service.value_device(
                {
                    "brand": "Apple",
                    "model": "iPhone 13",
                    "functional_issues": ["invented_issue"],
                }
            )

    def test_unknown_model_exposes_category_reference_fallback(self) -> None:
        result = self.service.value_device(
            {
                "brand": "Example",
                "model": "Unknown Phone",
                "category": "手机",
            }
        )
        self.assertEqual(result["reference_source"], "category_reference")

    def test_duplicate_valuation_issues_are_rejected(self) -> None:
        with self.assertRaisesRegex(ValidationError, "duplicate"):
            self.service.value_device(
                {
                    "brand": "Apple",
                    "model": "iPhone 13",
                    "functional_issues": [
                        "water_damage",
                        "water_damage",
                    ],
                }
            )
