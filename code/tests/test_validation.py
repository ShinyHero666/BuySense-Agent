from __future__ import annotations

import math
import unittest

from shoprec.validation import ValidationError
from tests.support import fixed_service


class RequestValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.service, _ = fixed_service()

    def test_search_rejects_invalid_page(self) -> None:
        with self.assertRaisesRegex(ValidationError, "page"):
            self.service.search({"query": "手机", "page": 0})

    def test_integer_fields_reject_fractional_numbers(self) -> None:
        with self.assertRaisesRegex(ValidationError, "page"):
            self.service.search({"query": "手机", "page": 1.5})
        with self.assertRaisesRegex(ValidationError, "battery_health"):
            self.service.value_device(
                {
                    "brand": "Apple",
                    "model": "iPhone 13",
                    "battery_health": 89.5,
                }
            )

    def test_top_level_typo_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValidationError, "unknown fields: page_szie"):
            self.service.search({"query": "手机", "page_szie": 5})

    def test_search_rejects_invalid_sort(self) -> None:
        with self.assertRaisesRegex(ValidationError, "sort"):
            self.service.search({"query": "手机", "sort": "unknown"})

    def test_search_rejects_inverted_price_range(self) -> None:
        with self.assertRaisesRegex(ValidationError, "min_price"):
            self.service.search(
                {
                    "query": "手机",
                    "filters": {"min_price": 5000, "max_price": 1000},
                }
            )

    def test_price_filters_require_finite_json_numbers(self) -> None:
        for value in ("1000", math.nan, math.inf, -math.inf):
            with self.subTest(value=value):
                with self.assertRaisesRegex(ValidationError, "filters.min_price"):
                    self.service.search(
                        {
                            "query": "手机",
                            "filters": {"min_price": value},
                        }
                    )

    def test_recommend_rejects_negative_size(self) -> None:
        with self.assertRaisesRegex(ValidationError, "size"):
            self.service.recommend({"user_id": "u001", "size": -2})

    def test_event_rejects_unknown_product(self) -> None:
        with self.assertRaisesRegex(ValidationError, "unknown product"):
            self.service.record_event(
                {
                    "user_id": "u001",
                    "product_id": "missing",
                    "event_type": "click",
                }
            )

    def test_each_endpoint_rejects_unknown_top_level_fields(self) -> None:
        cases = [
            (self.service.recommend, {"user_id": "u001", "szie": 5}),
            (
                self.service.value_device,
                {"brand": "Apple", "model": "iPhone 13", "storag_gb": 128},
            ),
            (
                self.service.record_event,
                {
                    "user_id": "u001",
                    "product_id": "p001",
                    "event_type": "click",
                    "extra": True,
                },
            ),
        ]
        for action, payload in cases:
            with self.subTest(action=action.__name__):
                with self.assertRaisesRegex(ValidationError, "unknown fields"):
                    action(payload)
