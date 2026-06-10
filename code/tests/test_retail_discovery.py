from __future__ import annotations

import unittest

from shoprec.retail_discovery import RetailDiscoveryService
from shoprec.retail_domain import (
    DEFAULT_BUNDLE_CATEGORIES,
    DEFAULT_CATEGORY,
    PRIMARY_CATEGORY,
    PRODUCT_CATEGORIES,
)
from shoprec.retail_models import load_retail_catalog
from shoprec.validation import ValidationError


class RetailCatalogTest(unittest.TestCase):
    def test_shared_domain_pack_references_declared_categories(self) -> None:
        self.assertIn(DEFAULT_CATEGORY, PRODUCT_CATEGORIES)
        self.assertIn(PRIMARY_CATEGORY, PRODUCT_CATEGORIES)
        self.assertTrue(set(DEFAULT_BUNDLE_CATEGORIES).issubset(PRODUCT_CATEGORIES))

    def test_snapshot_has_explicit_spu_sku_offer_layers(self) -> None:
        catalog = load_retail_catalog()
        self.assertEqual(catalog.catalog_version, "normal-3c-snapshot-v1")
        self.assertEqual(len(catalog.spus), 10)
        self.assertEqual(len(catalog.sellable_items()), 11)
        iphone = next(spu for spu in catalog.spus if spu.spu_id == "spu-iphone-15")
        self.assertEqual(len(iphone.skus), 2)
        self.assertTrue(all(sku.offers for sku in iphone.skus))


class RetailDiscoveryServiceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.service = RetailDiscoveryService()
        self.request = {
            "query": "拍照手机搭配降噪耳机和充电器",
            "requested_categories": ["phone", "headphones", "charger"],
            "use_cases": ["拍照", "续航", "降噪"],
            "preferred_brands": [],
            "primary_product_ids": [],
            "max_price": 7000,
            "limit": 8,
            "sponsored_allowed": True,
        }

    def test_search_pushes_down_category_stock_and_price_filters(self) -> None:
        result = self.service.search(self.request)
        self.assertEqual(result["channel"], "search")
        self.assertTrue(result["items"])
        self.assertTrue(all(item["category"] == "phone" for item in result["items"]))
        self.assertTrue(all(item["stock"] > 0 for item in result["items"]))
        self.assertTrue(all(item["price"] <= 7000 for item in result["items"]))
        self.assertTrue(all(item["quote_version"] for item in result["items"]))

    def test_recommendation_consumes_search_peer_context(self) -> None:
        request = {**self.request, "primary_product_ids": ["spu-honor-200"]}
        result = self.service.recommend(request)
        self.assertTrue(
            any(
                "search_peer_context_match" in item["reasons"]
                for item in result["items"]
            )
        )
        self.assertNotIn(
            "sku-camera-charger-sponsored",
            {item["sku_id"] for item in result["items"]},
        )

    def test_ads_are_disclosed_and_quality_gated(self) -> None:
        result = self.service.ads(self.request)
        self.assertTrue(result["items"])
        self.assertTrue(all(item["sponsored"] for item in result["items"]))
        self.assertTrue(all(item["disclosure"] == "赞助" for item in result["items"]))
        self.assertNotIn(
            "sku-camera-charger-sponsored",
            {item["sku_id"] for item in result["items"]},
        )

    def test_ad_opt_out_and_unknown_fields_fail_closed(self) -> None:
        result = self.service.ads({**self.request, "sponsored_allowed": False})
        self.assertEqual(result["items"], [])
        with self.assertRaises(ValidationError):
            self.service.search({**self.request, "page_szie": 3})

    def test_raw_query_terms_and_personalization_controls_affect_scoring(self) -> None:
        honor = self.service.search({
            **self.request,
            "query": "荣耀 人像手机",
            "use_cases": [],
            "preferred_brands": [],
            "identity_id": "identity-a",
            "session_id": "session-a",
            "personalization_enabled": False,
            "recent_product_ids": [],
            "excluded_product_ids": [],
            "ad_exposure_product_ids": [],
        })
        xiaomi = self.service.search({
            **self.request,
            "query": "小米 游戏手机",
            "use_cases": [],
            "preferred_brands": [],
            "identity_id": "identity-a",
            "session_id": "session-a",
            "personalization_enabled": False,
            "recent_product_ids": [],
            "excluded_product_ids": [],
            "ad_exposure_product_ids": [],
        })
        self.assertEqual(honor["items"][0]["product_id"], "spu-honor-200")
        self.assertEqual(xiaomi["items"][0]["product_id"], "spu-xiaomi-14")
        self.assertTrue(
            all("personalization_opted_out" in item["reasons"] for item in honor["items"])
        )

    def test_ad_exposure_applies_frequency_penalty(self) -> None:
        baseline = self.service.ads({**self.request, "ad_exposure_product_ids": []})
        exposed_id = baseline["items"][0]["product_id"]
        penalized = self.service.ads({
            **self.request,
            "ad_exposure_product_ids": [exposed_id],
        })
        exposed = next(item for item in penalized["items"] if item["product_id"] == exposed_id)
        self.assertIn("ad_frequency_penalty", exposed["reasons"])
