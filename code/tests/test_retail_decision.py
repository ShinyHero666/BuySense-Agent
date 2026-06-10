from __future__ import annotations

from datetime import datetime, timezone
import unittest

from shoprec.retail_decision import RetailDecisionService
from shoprec.retail_models import load_retail_catalog
from shoprec.validation import ValidationError


FIXED_NOW = datetime(2026, 8, 1, 10, 0, tzinfo=timezone.utc)


class RetailDecisionServiceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.service = RetailDecisionService(
            load_retail_catalog(), now=lambda: FIXED_NOW
        )

    def test_review_aspects_are_versioned_and_keep_missing_ids_visible(self) -> None:
        result = self.service.review_aspects(
            {"product_ids": ["spu-honor-200", "spu-missing"]}
        )
        self.assertEqual(result["review_snapshot_version"], "review-aspects-v1")
        self.assertEqual(result["products"][0]["product_id"], "spu-honor-200")
        self.assertGreater(result["products"][0]["sample_size"], 0)
        self.assertEqual(result["missing_product_ids"], ["spu-missing"])

    def test_compatibility_graph_returns_paths_and_rejects_camera_charger(self) -> None:
        result = self.service.check_compatibility(
            {
                "pairs": [
                    {
                        "product_sku_id": "sku-honor-200-256-green",
                        "accessory_sku_id": "sku-apple-charger-20w",
                    },
                    {
                        "product_sku_id": "sku-honor-200-256-green",
                        "accessory_sku_id": "sku-camera-charger-sponsored",
                    },
                ]
            }
        )
        self.assertEqual(result["graph_version"], "compatibility-graph-v1")
        self.assertEqual(result["results"][0]["status"], "compatible")
        self.assertTrue(result["results"][0]["paths"])
        self.assertEqual(result["results"][1]["status"], "incompatible")

    def test_pricing_issues_short_lived_offer_quotes(self) -> None:
        result = self.service.quote(
            {
                "offer_ids": [
                    "offer-honor-200-256-green-self",
                    "offer-does-not-exist",
                ]
            }
        )
        self.assertTrue(result["quote_batch_id"].startswith("quote-batch-"))
        self.assertTrue(result["quote_version"].startswith("realtime-"))
        self.assertEqual(result["quotes"][0]["status"], "active")
        self.assertEqual(result["quotes"][0]["amount"], 2799)
        self.assertEqual(result["quotes"][1]["status"], "unavailable")

    def test_decision_endpoints_reject_unknown_fields(self) -> None:
        with self.assertRaises(ValidationError):
            self.service.review_aspects(
                {"product_ids": ["spu-honor-200"], "include_raw_reviews": True}
            )

    def test_rrf_fusion_protects_organic_quality_and_discloses_ads(self) -> None:
        discovery_payload = {
            "query": "拍照手机搭配降噪耳机和充电器",
            "requested_categories": ["phone", "headphones", "charger"],
            "use_cases": ["拍照", "降噪"],
            "preferred_brands": [],
            "primary_product_ids": [],
            "max_price": 7000,
            "limit": 8,
            "sponsored_allowed": True,
        }
        from shoprec.retail_discovery import RetailDiscoveryService

        discovery = RetailDiscoveryService(self.service.catalog)
        channels = [
            discovery.search(discovery_payload),
            discovery.recommend(discovery_payload),
            discovery.ads(discovery_payload),
        ]
        fused = self.service.fuse({"channels": channels, "limit": 8})
        self.assertEqual(fused["fusion_version"], "weighted-rrf-v2")
        self.assertLessEqual(
            sum(item["sponsored"] for item in fused["items"][:3]),
            1,
        )
        self.assertTrue(
            all(item["disclosure"] == "赞助" for item in fused["items"] if item["sponsored"])
        )

    def test_constraint_optimizer_returns_global_top_n_bundle(self) -> None:
        from shoprec.retail_discovery import RetailDiscoveryService

        payload = {
            "query": "预算7000拍照手机降噪耳机充电器",
            "requested_categories": ["phone", "headphones", "charger"],
            "use_cases": ["拍照", "降噪"],
            "preferred_brands": [],
            "primary_product_ids": [],
            "max_price": 7000,
            "limit": 8,
            "sponsored_allowed": True,
        }
        discovery = RetailDiscoveryService(self.service.catalog)
        search = discovery.search(payload)
        recommend = discovery.recommend({
            **payload,
            "primary_product_ids": [search["items"][0]["product_id"]],
        })
        fused = self.service.fuse({
            "channels": [search, recommend, discovery.ads(payload)],
            "limit": 8,
        })
        optimized = self.service.optimize_bundles({
            "items": fused["items"],
            "requested_categories": payload["requested_categories"],
            "intent": "bundle",
            "budget_max": 7000,
            "top_n": 3,
        })
        self.assertTrue(optimized["complete"])
        self.assertGreaterEqual(len(optimized["bundles"]), 1)
        self.assertEqual(len(optimized["bundles"][0]["sku_ids"]), 3)
        self.assertLessEqual(optimized["bundles"][0]["total_price"], 7000)
        self.assertTrue(
            all(item["status"] == "compatible" for item in optimized["bundles"][0]["compatibility"])
        )
