from __future__ import annotations

import json
from io import BytesIO
import threading
import unittest
import urllib.error
import urllib.request

from shoprec.server import Handler, build_server
from shoprec.validation import ValidationError
from tests.support import fixed_service


class HTTPIntegrationTest(unittest.TestCase):
    def setUp(self) -> None:
        service, _ = fixed_service()
        self.server = build_server("127.0.0.1", 0, service)
        self.thread = threading.Thread(
            target=self.server.serve_forever,
            daemon=True,
        )
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def _post(self, path: str, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        return urllib.request.urlopen(request, timeout=2)

    def test_health_and_search(self) -> None:
        health = json.load(
            urllib.request.urlopen(self.base_url + "/health/ready", timeout=2)
        )
        result = json.load(
            self._post(
                "/api/search",
                {"query": "iphone", "user_id": "u001", "page_size": 2},
            )
        )
        self.assertEqual(health["status"], "UP")
        self.assertEqual(health["retail_catalog_version"], "normal-3c-snapshot-v1")
        self.assertEqual(len(result["items"]), 2)

    def test_normal_commerce_discovery_endpoints(self) -> None:
        payload = {
            "query": "拍照手机搭配耳机和充电器",
            "requested_categories": ["phone", "headphones", "charger"],
            "use_cases": ["拍照", "降噪"],
            "preferred_brands": [],
            "primary_product_ids": [],
            "max_price": 7000,
            "limit": 8,
            "sponsored_allowed": True,
        }
        search = json.load(self._post("/api/v2/discovery/search", payload))
        peer_ids = [search["items"][0]["product_id"]]
        recommend = json.load(
            self._post(
                "/api/v2/discovery/recommend",
                {**payload, "primary_product_ids": peer_ids},
            )
        )
        ads = json.load(self._post("/api/v2/discovery/ads", payload))
        self.assertEqual(search["catalog_version"], "normal-3c-snapshot-v1")
        self.assertTrue(recommend["items"])
        self.assertTrue(all(item["disclosure"] == "赞助" for item in ads["items"]))

    def test_normal_commerce_decision_evidence_endpoints(self) -> None:
        reviews = json.load(
            self._post(
                "/api/v2/evidence/reviews",
                {"product_ids": ["spu-honor-200"]},
            )
        )
        compatibility = json.load(
            self._post(
                "/api/v2/evidence/compatibility",
                {
                    "pairs": [
                        {
                            "product_sku_id": "sku-honor-200-256-green",
                            "accessory_sku_id": "sku-apple-charger-20w",
                        }
                    ]
                },
            )
        )
        quote = json.load(
            self._post(
                "/api/v2/pricing/quote",
                {"offer_ids": ["offer-honor-200-256-green-self"]},
            )
        )
        self.assertEqual(reviews["review_snapshot_version"], "review-aspects-v1")
        self.assertEqual(compatibility["results"][0]["status"], "compatible")
        self.assertEqual(quote["quotes"][0]["status"], "active")

    def test_validation_error_returns_structured_400(self) -> None:
        with self.assertRaises(urllib.error.HTTPError) as captured:
            self._post("/api/search", {"query": "手机", "page": 0})
        error = captured.exception
        self.assertEqual(error.code, 400)
        body = json.loads(error.read().decode("utf-8"))
        error.close()
        self.assertEqual(body["error"], "validation_error")
        self.assertEqual(body["field"], "page")

    def test_unknown_request_field_returns_structured_400(self) -> None:
        with self.assertRaises(urllib.error.HTTPError) as captured:
            self._post("/api/search", {"query": "手机", "page_szie": 2})
        error = captured.exception
        self.assertEqual(error.code, 400)
        body = json.loads(error.read().decode("utf-8"))
        error.close()
        self.assertEqual(body["field"], "request")
        self.assertIn("page_szie", body["message"])

    def test_negative_content_length_is_rejected_before_read(self) -> None:
        handler = object.__new__(Handler)
        handler.headers = {"Content-Length": "-1"}
        handler.rfile = BytesIO(b"{}")
        with self.assertRaisesRegex(ValidationError, "must not be negative"):
            handler._json_body()

    def test_valuation_endpoint(self) -> None:
        result = json.load(
            self._post(
                "/api/valuation",
                {
                    "brand": "Apple",
                    "model": "iPhone 13",
                    "age_months": 36,
                    "condition_grade": "B",
                    "battery_health": 90,
                },
            )
        )
        self.assertEqual(result["quote_type"], "pre_inspection_estimate")
        self.assertTrue(result["final_price_requires_inspection"])

    def test_internal_error_returns_sanitized_500(self) -> None:
        def explode(_payload):
            raise KeyError("internal_feature_secret")

        self.server.service.search = explode
        with self.assertLogs("shoprec.server", level="ERROR"):
            with self.assertRaises(urllib.error.HTTPError) as captured:
                self._post("/api/search", {"query": "手机"})
        error = captured.exception
        self.assertEqual(error.code, 500)
        body = json.loads(error.read().decode("utf-8"))
        error.close()
        self.assertEqual(body, {"error": "internal_server_error"})
