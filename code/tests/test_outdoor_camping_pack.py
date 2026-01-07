from __future__ import annotations

import json
import threading
import unittest
import urllib.error
import urllib.request

from shoprec.retail_decision import RetailDecisionService
from shoprec.retail_discovery import RetailDiscoveryService
from shoprec.retail_domain import DOMAIN_PACK_REGISTRY
from shoprec.retail_models import load_retail_catalog
from shoprec.server import build_server


CAMPING_PACK_ID = "outdoor-camping-v1"


class OutdoorCampingDomainPackTest(unittest.TestCase):
    def setUp(self) -> None:
        self.pack = DOMAIN_PACK_REGISTRY.get(CAMPING_PACK_ID)
        self.catalog = load_retail_catalog(pack=self.pack)
        self.discovery = RetailDiscoveryService(self.catalog, pack=self.pack)
        self.decision = RetailDecisionService(self.catalog, pack=self.pack)
        self.payload = {
            "query": "预算900元高海拔防风炉具搭配气罐和锅具",
            "requested_categories": ["camp_stove", "fuel_canister", "cookware"],
            "use_cases": ["防风", "高海拔"],
            "preferred_brands": [],
            "primary_product_ids": [],
            "max_price": 900,
            "limit": 8,
            "sponsored_allowed": True,
        }

    def test_catalog_has_two_candidates_per_declared_category(self) -> None:
        counts = {
            category: sum(spu.category == category for spu in self.catalog.spus)
            for category in self.pack.product_categories
        }
        self.assertEqual(
            counts,
            {"camp_stove": 2, "fuel_canister": 2, "cookware": 2},
        )

    def test_search_recommendation_and_ads_use_camping_assets(self) -> None:
        search = self.discovery.search(self.payload)
        self.assertEqual(search["catalog_version"], "outdoor-camping-snapshot-v1")
        self.assertEqual(
            {item["category"] for item in search["items"]},
            {"camp_stove"},
        )

        recommendation = self.discovery.recommend({
            **self.payload,
            "primary_product_ids": [search["items"][0]["product_id"]],
        })
        self.assertEqual(
            {item["category"] for item in recommendation["items"]},
            {"fuel_canister", "cookware"},
        )
        self.assertTrue(
            any(
                "search_peer_context_match" in item["reasons"]
                for item in recommendation["items"]
            )
        )

        ads = self.discovery.ads(self.payload)
        self.assertTrue(ads["items"])
        self.assertTrue(all(item["sponsored"] for item in ads["items"]))
        self.assertTrue(all(item["disclosure"] == "赞助" for item in ads["items"]))
        self.assertEqual(
            self.discovery.ads({**self.payload, "sponsored_allowed": False})["items"],
            [],
        )

    def test_compatibility_has_grounded_positive_and_negative_paths(self) -> None:
        result = self.decision.check_compatibility({
            "pairs": [
                {
                    "product_sku_id": "sku-trailforge-alpine-stove",
                    "accessory_sku_id": "sku-trailforge-four-season-gas",
                },
                {
                    "product_sku_id": "sku-trailforge-alpine-stove",
                    "accessory_sku_id": "sku-easyflame-butane-cassette",
                },
            ]
        })
        self.assertEqual(result["graph_version"], "outdoor-camping-compatibility-v1")
        self.assertEqual(result["results"][0]["status"], "compatible")
        self.assertTrue(result["results"][0]["paths"])
        self.assertEqual(result["results"][1]["status"], "incompatible")

    def test_full_fusion_bundle_review_and_quote_chain(self) -> None:
        search = self.discovery.search(self.payload)
        recommendation = self.discovery.recommend({
            **self.payload,
            "primary_product_ids": [search["items"][0]["product_id"]],
        })
        ads = self.discovery.ads(self.payload)
        fused = self.decision.fuse({
            "channels": [search, recommendation, ads],
            "limit": 8,
        })
        optimized = self.decision.optimize_bundles({
            "items": fused["items"],
            "requested_categories": self.payload["requested_categories"],
            "intent": "bundle",
            "budget_max": 900,
            "top_n": 3,
        })
        self.assertTrue(optimized["complete"])
        selected = optimized["bundles"][0]
        self.assertEqual(len(selected["sku_ids"]), 3)
        self.assertLessEqual(selected["total_price"], 900)
        self.assertTrue(
            all(item["status"] == "compatible" for item in selected["compatibility"])
        )

        product_by_sku = {
            item.sku.sku_id: item
            for item in self.catalog.sellable_items()
        }
        product_ids = [product_by_sku[sku_id].spu.spu_id for sku_id in selected["sku_ids"]]
        offer_ids = [product_by_sku[sku_id].offer.offer_id for sku_id in selected["sku_ids"]]
        reviews = self.decision.review_aspects({"product_ids": product_ids})
        quotes = self.decision.quote({"offer_ids": offer_ids})
        self.assertEqual(reviews["missing_product_ids"], [])
        self.assertEqual(len(reviews["products"]), 3)
        self.assertTrue(all(item["aspects"] for item in reviews["products"]))
        self.assertTrue(all(item["status"] == "active" for item in quotes["quotes"]))


class OutdoorCampingHTTPRoutingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server = build_server("127.0.0.1", 0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def _post(self, path: str, payload: dict):
        request = urllib.request.Request(
            self.base_url + path,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        return urllib.request.urlopen(request, timeout=2)

    def test_pack_listing_and_request_scoped_routing_are_isolated(self) -> None:
        listing = json.load(
            urllib.request.urlopen(self.base_url + "/api/v2/domain-packs", timeout=2)
        )
        self.assertEqual(listing["defaultPackId"], "normal-3c-v1")
        self.assertTrue(
            {"normal-3c-v1", CAMPING_PACK_ID}.issubset(
                {item["id"] for item in listing["packs"]}
            )
        )

        camping = json.load(self._post(
            "/api/v2/discovery/search",
            {
                "domain_pack_id": CAMPING_PACK_ID,
                "query": "轻量防风炉具",
                "requested_categories": ["camp_stove"],
                "use_cases": ["轻量", "防风"],
                "preferred_brands": [],
                "primary_product_ids": [],
                "max_price": 900,
                "limit": 8,
                "sponsored_allowed": True,
            },
        ))
        normal = json.load(self._post(
            "/api/v2/discovery/search",
            {
                "query": "拍照手机",
                "requested_categories": ["phone"],
                "use_cases": ["拍照"],
                "preferred_brands": [],
                "primary_product_ids": [],
                "max_price": 7000,
                "limit": 8,
                "sponsored_allowed": True,
            },
        ))
        self.assertEqual(camping["catalog_version"], "outdoor-camping-snapshot-v1")
        self.assertEqual(normal["catalog_version"], "normal-3c-snapshot-v1")

    def test_unknown_pack_fails_closed_with_structured_400(self) -> None:
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self._post(
                "/api/v2/evidence/reviews",
                {"domain_pack_id": "not-installed", "product_ids": []},
            )
        try:
            self.assertEqual(caught.exception.code, 400)
            body = json.load(caught.exception)
            self.assertEqual(body["field"], "domain_pack_id")
            self.assertIn("unknown domain pack", body["message"])
        finally:
            caught.exception.close()

    def test_category_identifiers_reject_surrounding_whitespace(self) -> None:
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self._post(
                "/api/v2/discovery/search",
                {
                    "domain_pack_id": CAMPING_PACK_ID,
                    "query": "炉具",
                    "requested_categories": [" camp_stove "],
                },
            )
        try:
            self.assertEqual(caught.exception.code, 400)
            self.assertEqual(json.load(caught.exception)["field"], "requested_categories")
        finally:
            caught.exception.close()

    def test_readiness_covers_non_default_domain_pack_assets(self) -> None:
        self.server.domain_runtimes[CAMPING_PACK_ID].decision.reviews.products.clear()
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(self.base_url + "/health/ready", timeout=2)
        try:
            self.assertEqual(caught.exception.code, 503)
            body = json.load(caught.exception)
            self.assertEqual(body["components"]["reviews"]["status"], "up")
            self.assertEqual(
                body["domain_packs"][CAMPING_PACK_ID]["reviews"]["status"],
                "down",
            )
        finally:
            caught.exception.close()


if __name__ == "__main__":
    unittest.main()
