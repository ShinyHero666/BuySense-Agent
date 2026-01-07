from __future__ import annotations

import json
import io
import os
import threading
import time
import unittest
from datetime import datetime, timezone
from email.message import Message
from urllib.error import HTTPError
from urllib.request import ProxyHandler
from unittest.mock import patch

from shoprec.retail_data_ports import RetailDataPortError
from shoprec.retail_domain import DOMAIN_PACK_REGISTRY
from shoprec.server import build_server
from shoprec.shopify_retail_provider import (
    SHOPIFY_API_VERSION,
    SHOPIFY_CATALOG_QUERY,
    SHOPIFY_PRICING_QUERY,
    SHOPIFY_REVIEWS_QUERY,
    _NoRedirects,
    create_shopify_retail_context,
    shopify_provider_id,
)


TOKEN = "shpat_NEVER_EXPOSE_THIS_SENTINEL"
NOW = datetime(2026, 8, 9, 12, 0, tzinfo=timezone.utc)
UPDATED_AT = "2026-08-09T10:30:00Z"


def _environment(*, fallback: bool = False) -> dict[str, str]:
    return {
        "MOYUAN_RETAIL_DATA_MODE": "http",
        "MOYUAN_RETAIL_DATA_PROVIDER": "shopify",
        "MOYUAN_RETAIL_DATA_FALLBACK_ENABLED": "true" if fallback else "false",
        "MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS": "1",
        "MOYUAN_RETAIL_DATA_MAX_RESPONSE_BYTES": "262144",
        "MOYUAN_SHOPIFY_STORE_DOMAIN": "moyuan-test.myshopify.com",
        "MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN": TOKEN,
        "MOYUAN_SHOPIFY_API_VERSION": SHOPIFY_API_VERSION,
    }


class _FakeResponse:
    def __init__(self, payload: dict, *, api_version: str = SHOPIFY_API_VERSION) -> None:
        self.raw = json.dumps(payload).encode("utf-8")
        self.headers = Message()
        self.headers["Content-Type"] = "application/json; charset=utf-8"
        self.headers["X-Shopify-API-Version"] = api_version

    def __enter__(self):
        return self

    def __exit__(self, *_args) -> None:
        return None

    def read(self, amount: int) -> bytes:
        return self.raw[:amount]


class _RouterOpener:
    def __init__(self) -> None:
        self.requests: list[tuple[object, dict, float]] = []
        self.mode = "success"
        self.delay = 0.0
        self.api_version = SHOPIFY_API_VERSION
        self.active = 0
        self.max_active = 0
        self._lock = threading.Lock()

    def open(self, request, *, timeout: float):  # noqa: ANN001
        payload = json.loads(request.data)
        self.requests.append((request, payload, timeout))
        with self._lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
        try:
            if self.delay:
                time.sleep(self.delay)
            result = self._route(payload)
        finally:
            with self._lock:
                self.active -= 1
        if isinstance(result, _FakeResponse):
            return result
        return _FakeResponse(result, api_version=self.api_version)

    def _route(self, payload: dict):
        query = payload["query"]
        variables = payload["variables"]
        if query == SHOPIFY_CATALOG_QUERY:
            pack_id = (
                "outdoor-camping-v1"
                if "outdoor-camping-v1" in variables["query"]
                else "normal-3c-v1"
            )
            if self.mode == "query_cost_error":
                return {
                    "errors": [
                        {
                            "message": TOKEN,
                            "extensions": {"code": "MAX_COST_EXCEEDED"},
                        }
                    ]
                }
            if self.mode == "fail_outdoor_catalog" and pack_id == "outdoor-camping-v1":
                return {
                    "errors": [
                        {"message": "merchant secret response", "token": TOKEN}
                    ]
                }
            if self.mode == "cursor_loop":
                return _catalog_response(pack_id, has_next=True, cursor="same-cursor")
            response = _catalog_response(pack_id)
            if self.mode == "cn_unpublished_catalog":
                response["data"]["products"]["nodes"][0][
                    "publishedInContext"
                ] = False
            return response
        if query == SHOPIFY_REVIEWS_QUERY:
            if self.mode == "graphql_error":
                return {"data": {"nodes": []}, "errors": [{"message": TOKEN}]}
            product_gid = variables["ids"][0] if variables["ids"] else None
            if self.mode == "partial_review":
                return {
                    "data": {
                        "nodes": [
                            {
                                "__typename": "Product",
                                "id": product_gid,
                                "updatedAt": UPDATED_AT,
                                "reviewsRating": None,
                                "reviewsRatingCount": {
                                    "type": "number_integer",
                                    "value": "12",
                                },
                            }
                        ]
                    }
                }
            if self.mode == "missing_review":
                return {
                    "data": {
                        "nodes": [
                            {
                                "__typename": "Product",
                                "id": product_gid,
                                "updatedAt": UPDATED_AT,
                                "reviewsRating": None,
                                "reviewsRatingCount": None,
                            }
                        ]
                    }
                }
            return {"data": {"nodes": [_review_node(product_gid)] if product_gid else []}}
        if query == SHOPIFY_PRICING_QUERY:
            if self.mode == "graphql_error":
                return {"data": {"nodes": []}, "errors": [{"message": TOKEN}]}
            variant_gid = variables["ids"][0] if variables["ids"] else None
            if self.mode == "wrong_pricing_type":
                return {
                    "data": {
                        "nodes": [
                            {
                                "__typename": "Product",
                                "id": "gid://shopify/Product/999",
                            }
                        ]
                    }
                }
            response = {
                "data": {
                    "nodes": [_pricing_node(variant_gid)] if variant_gid else []
                }
            }
            if self.mode == "cn_unpublished_pricing" and response["data"]["nodes"]:
                response["data"]["nodes"][0]["product"][
                    "publishedInContext"
                ] = False
            return response
        raise AssertionError("unexpected GraphQL document")


def _catalog_response(
    pack_id: str, *, has_next: bool = False, cursor: str | None = None
) -> dict:
    if pack_id == "normal-3c-v1":
        product_number, variant_number, category = "101", "1001", "phone"
    else:
        product_number, variant_number, category = "201", "2001", "camp_stove"
    marker = f"moyuan-domain-pack-{pack_id}"
    return {
        "data": {
            "currentAppInstallation": {
                "accessScopes": [{"handle": "read_products"}]
            },
            "products": {
                "pageInfo": {"hasNextPage": has_next, "endCursor": cursor},
                "nodes": [
                    {
                        "id": f"gid://shopify/Product/{product_number}",
                        "title": f"Product {product_number}",
                        "vendor": "Moyuan",
                        "productType": category,
                        "tags": [marker, "featured"],
                        "updatedAt": UPDATED_AT,
                        "requiresSellingPlan": False,
                        "publishedInContext": True,
                        "sarProduct": {
                            "type": "json",
                            "value": "{}",
                            "jsonValue": {
                                "domain_pack_id": pack_id,
                                "category": category,
                                "brand": "Moyuan",
                                "tags": ["featured"],
                            },
                        },
                        "variants": {
                            "pageInfo": {"hasNextPage": False},
                            "nodes": [
                                {
                                    "id": f"gid://shopify/ProductVariant/{variant_number}",
                                    "title": "Default",
                                    "sku": f"SKU-{variant_number}",
                                    "updatedAt": UPDATED_AT,
                                    "requiresComponents": False,
                                    "availableForSale": True,
                                    "sellableOnlineQuantity": 7,
                                    "contextualPricing": {
                                        "price": {
                                            "amount": "499.50",
                                            "currencyCode": "CNY",
                                        }
                                    },
                                    "sarVariant": {
                                        "type": "json",
                                        "value": "{}",
                                        "jsonValue": {
                                            "ecosystem": "universal",
                                            "connectors": [],
                                            "protocols": [],
                                            "sponsored": False,
                                        },
                                    },
                                }
                            ],
                        },
                    }
                ],
            },
        }
    }


def _review_node(product_gid: str) -> dict:
    return {
        "__typename": "Product",
        "id": product_gid,
        "updatedAt": UPDATED_AT,
        "reviewsRating": {
            "type": "rating",
            "value": '{"value":"4.5","scale_min":"1.0","scale_max":"5.0"}',
            "jsonValue": {"value": "4.5", "scale_min": "1.0", "scale_max": "5.0"},
        },
        "reviewsRatingCount": {"type": "number_integer", "value": "12"},
    }


def _pricing_node(variant_gid: str) -> dict:
    return {
        "__typename": "ProductVariant",
        "id": variant_gid,
        "updatedAt": UPDATED_AT,
        "requiresComponents": False,
        "availableForSale": True,
        "sellableOnlineQuantity": 5,
        "product": {"publishedInContext": True},
        "contextualPricing": {
            "price": {"amount": "488.00", "currencyCode": "CNY"}
        },
    }


class ShopifyRetailProviderTest(unittest.TestCase):
    def _context(self, opener: _RouterOpener, *, fallback: bool = False):
        return create_shopify_retail_context(
            environ=_environment(fallback=fallback), opener=opener, now=lambda: NOW
        )

    def test_catalog_reviews_and_pricing_use_fixed_bounded_shopify_queries(self) -> None:
        opener = _RouterOpener()
        context = self._context(opener)
        pack = DOMAIN_PACK_REGISTRY.get("normal-3c-v1")
        ports = context.create_ports(pack)
        context.seal_catalog_cohort()

        item = ports.catalog.sellable_items()[0]
        self.assertEqual(
            item.spu.spu_id,
            f"spu-shopify-{context.config.shop_hash}-101",
        )
        self.assertEqual(item.offer.currency, "CNY")
        self.assertEqual(item.offer.stock, 7)
        self.assertIn(context.config.shop_hash, item.offer.offer_id)

        reviews = ports.reviews.get({"product_ids": [item.spu.spu_id]})
        quote = ports.pricing.quote({"offer_ids": [item.offer.offer_id]})
        self.assertEqual(reviews["products"][0]["sample_size"], 12)
        self.assertEqual(reviews["products"][0]["aspects"][0]["confidence"], 1.0)
        self.assertEqual(quote["quotes"][0]["amount"], 488.0)
        self.assertEqual(quote["quotes"][0]["stock"], 5)
        self.assertEqual(quote["data_source"]["source"], "remote_provider")

        request, payload, timeout = opener.requests[0]
        self.assertEqual(request.full_url, context.config.endpoint)
        self.assertEqual(timeout, 1)
        headers = {name.lower(): value for name, value in request.header_items()}
        self.assertEqual(headers["x-shopify-access-token"], TOKEN)
        self.assertEqual(payload["query"], SHOPIFY_CATALOG_QUERY)
        self.assertEqual(payload["variables"]["first"], 5)
        self.assertIn("variants(first: 25)", payload["query"])
        self.assertIn("publishedInContext(context: {country: CN})", payload["query"])
        self.assertEqual(
            payload["variables"]["query"],
            "status:active published_status:published "
            "tag:moyuan-domain-pack-normal-3c-v1",
        )
        self.assertNotRegex(payload["query"].lower(), r"\bmutation\b")

    def test_review_and_pricing_indexes_are_isolated_by_domain_pack(self) -> None:
        opener = _RouterOpener()
        context = self._context(opener)
        normal = context.create_ports(DOMAIN_PACK_REGISTRY.get("normal-3c-v1"))
        camping = context.create_ports(
            DOMAIN_PACK_REGISTRY.get("outdoor-camping-v1")
        )
        context.seal_catalog_cohort()

        camping_item = camping.catalog.sellable_items()[0]
        reviews = normal.reviews.get({"product_ids": [camping_item.spu.spu_id]})
        quote = normal.pricing.quote({"offer_ids": [camping_item.offer.offer_id]})

        self.assertEqual(reviews["products"], [])
        self.assertEqual(
            reviews["missing_product_ids"], [camping_item.spu.spu_id]
        )
        self.assertEqual(quote["quotes"][0]["status"], "unavailable")
        self.assertEqual(quote["quotes"][0]["reason"], "offer_not_found")
        self.assertEqual(opener.requests[-2][1]["variables"]["ids"], [])
        self.assertEqual(opener.requests[-1][1]["variables"]["ids"], [])

    def test_http_200_graphql_errors_fail_closed_without_runtime_fallback(self) -> None:
        opener = _RouterOpener()
        context = self._context(opener, fallback=True)
        ports = context.create_ports(DOMAIN_PACK_REGISTRY.get("normal-3c-v1"))
        context.seal_catalog_cohort()
        offer_id = ports.catalog.sellable_items()[0].offer.offer_id
        opener.mode = "graphql_error"
        with self.assertRaises(RetailDataPortError) as caught:
            ports.pricing.quote({"offer_ids": [offer_id]})
        self.assertEqual(caught.exception.code, "provider_graphql_error")
        self.assertEqual(ports.pricing.state.snapshot()["status"], "down")
        self.assertNotIn(TOKEN, str(caught.exception))

    def test_whole_catalog_cohort_falls_back_before_server_registration(self) -> None:
        opener = _RouterOpener()
        opener.mode = "fail_outdoor_catalog"
        with patch.dict(os.environ, _environment(fallback=True), clear=True), patch(
            "shoprec.shopify_retail_provider._default_opener", return_value=opener
        ):
            server = build_server("127.0.0.1", 0)
        try:
            self.assertEqual(len(opener.requests), 2)
            catalog_telemetry: dict[str, dict[str, int]] = {}
            for pack_id, runtime in server.domain_runtimes.items():
                health = runtime.data_ports.health()
                catalog_telemetry[pack_id] = health["catalog"]["telemetry"]
                for name, source in health.items():
                    self.assertEqual(source["configuredMode"], "http")
                    self.assertEqual(source["effectiveSource"], "local_snapshot")
                    self.assertEqual(source["status"], "degraded")
                    self.assertTrue(source["fallbackActive"])
                    self.assertEqual(source["lastErrorCode"], "provider_graphql_error")
                    if name != "catalog":
                        self.assertEqual(source["telemetry"]["errors"], 0)
                        self.assertEqual(source["telemetry"]["requests"], 0)
                    self.assertEqual(source["telemetry"]["fallbacks"], 1)
            self.assertEqual(
                catalog_telemetry["normal-3c-v1"],
                {"requests": 1, "errors": 0, "fallbacks": 1},
            )
            self.assertEqual(
                catalog_telemetry["outdoor-camping-v1"],
                {"requests": 1, "errors": 1, "fallbacks": 1},
            )
            self.assertEqual(
                sum(item["requests"] for item in catalog_telemetry.values()), 2
            )
            self.assertEqual(
                sum(item["errors"] for item in catalog_telemetry.values()), 1
            )

            runtime = server.domain_runtimes["normal-3c-v1"]
            item = runtime.data_ports.catalog.sellable_items()[0]
            for _ in range(2):
                runtime.data_ports.reviews.get({"product_ids": [item.spu.spu_id]})
                runtime.data_ports.pricing.quote({"offer_ids": [item.offer.offer_id]})
            after_reads = runtime.data_ports.health()
            for name in ("reviews", "pricing"):
                self.assertEqual(after_reads[name]["telemetry"]["requests"], 2)
                self.assertEqual(after_reads[name]["telemetry"]["errors"], 0)
                self.assertEqual(after_reads[name]["telemetry"]["fallbacks"], 1)
        finally:
            server.server_close()

    def test_server_startup_probes_review_and_pricing_for_every_pack(self) -> None:
        opener = _RouterOpener()
        with patch.dict(os.environ, _environment(), clear=True), patch(
            "shoprec.shopify_retail_provider._default_opener", return_value=opener
        ):
            server = build_server("127.0.0.1", 0)
        try:
            self.assertEqual(len(opener.requests), 6)
            for runtime in server.domain_runtimes.values():
                health = runtime.data_ports.health()
                for source in health.values():
                    self.assertEqual(source["status"], "up")
                    self.assertEqual(source["effectiveSource"], "remote_provider")
                    self.assertIsInstance(source["version"], str)
                    self.assertTrue(source["version"])
                    self.assertEqual(source["telemetry"]["requests"], 1)
        finally:
            server.server_close()

    def test_runtime_probe_failure_never_activates_static_fallback(self) -> None:
        opener = _RouterOpener()
        opener.mode = "graphql_error"
        with patch.dict(os.environ, _environment(fallback=True), clear=True), patch(
            "shoprec.shopify_retail_provider._default_opener", return_value=opener
        ):
            with self.assertRaises(RetailDataPortError) as caught:
                build_server("127.0.0.1", 0)
        self.assertEqual(caught.exception.code, "provider_graphql_error")
        self.assertEqual(
            [payload["query"] for _, payload, _ in opener.requests].count(
                SHOPIFY_CATALOG_QUERY
            ),
            2,
        )
        self.assertIn(SHOPIFY_REVIEWS_QUERY, [item[1]["query"] for item in opener.requests])

    def test_partial_standard_review_metafields_are_invalid_but_both_missing_is_missing(self) -> None:
        opener = _RouterOpener()
        context = self._context(opener)
        ports = context.create_ports(DOMAIN_PACK_REGISTRY.get("normal-3c-v1"))
        product_id = ports.catalog.spus[0].spu_id
        opener.mode = "partial_review"
        with self.assertRaises(RetailDataPortError) as caught:
            ports.reviews.get({"product_ids": [product_id]})
        self.assertEqual(caught.exception.code, "provider_invalid_response")

        opener.mode = "missing_review"
        result = ports.reviews.get({"product_ids": [product_id]})
        self.assertEqual(result["products"], [])
        self.assertEqual(result["missing_product_ids"], [product_id])

    def test_wrong_pricing_node_type_becomes_unavailable(self) -> None:
        opener = _RouterOpener()
        context = self._context(opener)
        ports = context.create_ports(DOMAIN_PACK_REGISTRY.get("normal-3c-v1"))
        offer_id = ports.catalog.sellable_items()[0].offer.offer_id
        opener.mode = "wrong_pricing_type"
        result = ports.pricing.quote({"offer_ids": [offer_id]})
        self.assertEqual(result["quotes"][0]["status"], "unavailable")
        self.assertEqual(result["quotes"][0]["reason"], "offer_not_found")

    def test_cn_publication_is_required_at_catalog_and_quote_time(self) -> None:
        opener = _RouterOpener()
        opener.mode = "cn_unpublished_catalog"
        context = self._context(opener)
        with self.assertRaises(RetailDataPortError) as catalog_error:
            context.create_ports(DOMAIN_PACK_REGISTRY.get("normal-3c-v1"))
        self.assertEqual(catalog_error.exception.code, "provider_invalid_response")

        opener = _RouterOpener()
        context = self._context(opener)
        ports = context.create_ports(DOMAIN_PACK_REGISTRY.get("normal-3c-v1"))
        offer_id = ports.catalog.sellable_items()[0].offer.offer_id
        opener.mode = "cn_unpublished_pricing"
        result = ports.pricing.quote({"offer_ids": [offer_id]})
        self.assertEqual(result["quotes"][0]["status"], "unavailable")
        self.assertEqual(
            result["quotes"][0]["reason"], "product_not_published_in_cn"
        )

    def test_cursor_cycle_and_unsupported_scope_fail_closed(self) -> None:
        opener = _RouterOpener()
        opener.mode = "cursor_loop"
        context = self._context(opener)
        with self.assertRaises(RetailDataPortError) as caught:
            context.create_ports(DOMAIN_PACK_REGISTRY.get("normal-3c-v1"))
        self.assertEqual(caught.exception.code, "provider_invalid_response")

        opener = _RouterOpener()
        original_route = opener._route

        def write_scope(payload: dict):
            result = original_route(payload)
            if payload["query"] == SHOPIFY_CATALOG_QUERY:
                result["data"]["currentAppInstallation"]["accessScopes"].append(
                    {"handle": "write_products"}
                )
            return result

        opener._route = write_scope  # type: ignore[method-assign]
        context = self._context(opener, fallback=True)
        with self.assertRaises(RetailDataPortError) as scope_error:
            context.create_ports(DOMAIN_PACK_REGISTRY.get("normal-3c-v1"))
        self.assertEqual(scope_error.exception.code, "provider_write_scope_forbidden")
        with self.assertRaises(RetailDataPortError) as fallback_error:
            context.activate_catalog_fallback(scope_error.exception)
        self.assertEqual(
            fallback_error.exception.code, "provider_write_scope_forbidden"
        )

        opener = _RouterOpener()
        opener.mode = "query_cost_error"
        context = self._context(opener, fallback=True)
        with self.assertRaises(RetailDataPortError) as cost_error:
            context.create_ports(DOMAIN_PACK_REGISTRY.get("normal-3c-v1"))
        self.assertEqual(cost_error.exception.code, "provider_query_cost_exceeded")
        with self.assertRaises(RetailDataPortError):
            context.activate_catalog_fallback(cost_error.exception)

    def test_shared_client_serializes_concurrent_graphql_requests(self) -> None:
        opener = _RouterOpener()
        opener.delay = 0.03
        context = self._context(opener)
        errors: list[Exception] = []

        def execute() -> None:
            try:
                context.client.execute(SHOPIFY_REVIEWS_QUERY, {"ids": []})
            except Exception as error:  # pragma: no cover - asserted below
                errors.append(error)

        threads = [threading.Thread(target=execute) for _ in range(3)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=1)
        self.assertEqual(errors, [])
        self.assertEqual(opener.max_active, 1)

    def test_provider_fingerprint_and_configuration_are_strict(self) -> None:
        first = shopify_provider_id("moyuan-test.myshopify.com")
        self.assertEqual(first, shopify_provider_id("MOYUAN-TEST.MYSHOPIFY.COM"))
        self.assertRegex(first, r"^shopify-[0-9a-f]{16}$")
        with self.assertRaises(ValueError):
            shopify_provider_id("https://moyuan-test.myshopify.com")
        with self.assertRaises(ValueError):
            shopify_provider_id("moyuan-test.myshopify.com", "2026-10")
        with self.assertRaises(ValueError):
            create_shopify_retail_context(
                environ={
                    **_environment(),
                    "MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN": "bad\nheader",
                },
                opener=_RouterOpener(),
            )
        with self.assertRaises(ValueError):
            create_shopify_retail_context(
                environ={
                    **_environment(),
                    "MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN": "replace-with-a-token",
                },
                opener=_RouterOpener(),
            )

    def test_version_header_redirect_and_proxy_policy_fail_closed(self) -> None:
        opener = _RouterOpener()
        opener.api_version = "2026-10"
        context = self._context(opener)
        with self.assertRaises(RetailDataPortError) as version_error:
            context.client.execute(SHOPIFY_REVIEWS_QUERY, {"ids": []})
        self.assertEqual(
            version_error.exception.code, "provider_api_version_mismatch"
        )
        self.assertNotIn(TOKEN, str(version_error.exception))

        class RedirectOpener:
            def __init__(self) -> None:
                self.calls = 0

            def open(self, request, *, timeout: float):  # noqa: ANN001
                self.calls += 1
                raise HTTPError(
                    request.full_url,
                    302,
                    "redirect",
                    Message(),
                    io.BytesIO(b"redirect body containing " + TOKEN.encode()),
                )

        redirect_opener = RedirectOpener()
        context = create_shopify_retail_context(
            environ=_environment(), opener=redirect_opener
        )
        with self.assertRaises(RetailDataPortError) as redirect_error:
            context.client.execute(SHOPIFY_REVIEWS_QUERY, {"ids": []})
        self.assertEqual(redirect_error.exception.code, "provider_http_error")
        self.assertEqual(redirect_opener.calls, 1)
        self.assertNotIn(TOKEN, str(redirect_error.exception))

        with patch.dict(
            os.environ, {"HTTPS_PROXY": "http://attacker.invalid:8080"}, clear=False
        ):
            default_context = create_shopify_retail_context(environ=_environment())
        handlers = default_context.client._opener.handlers
        proxy_handlers = [item for item in handlers if isinstance(item, ProxyHandler)]
        # Explicit ProxyHandler({}) suppresses urllib's environment-derived
        # default and is itself omitted because it has no proxy methods.
        self.assertEqual(proxy_handlers, [])
        self.assertTrue(any(isinstance(item, _NoRedirects) for item in handlers))


if __name__ == "__main__":
    unittest.main()
