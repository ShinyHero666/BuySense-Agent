from __future__ import annotations

import json
import os
import threading
import time
import unittest
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from shoprec.retail_data_ports import (
    HttpPricingDataPort,
    HttpReviewDataPort,
    JsonHttpRetailClient,
    RetailDataPortError,
    StaticPricingDataPort,
    StaticReviewDataPort,
    create_retail_data_ports,
    retail_provider_id,
)
from shoprec.retail_domain import DOMAIN_PACK_REGISTRY
from shoprec.retail_models import load_retail_catalog
from shoprec.server import build_server


FIXED_NOW = datetime(2026, 8, 1, 10, 0, tzinfo=timezone.utc)


class _ProviderState:
    def __init__(self) -> None:
        self.base_url = ""
        self.provider_id = ""
        self.requests: list[dict] = []
        self.fail_paths: set[str] = set()
        self.slow_paths: set[str] = set()
        self.oversize_paths: set[str] = set()
        self.required_authorization = "Bearer test-secret"
        self.version_override: str | None = None


def _remote_metadata(state: _ProviderState, version: str) -> dict:
    source_version = state.version_override or version
    return {
        "source": "remote_provider",
        "source_version": source_version,
        "provider_id": state.provider_id,
    }


class _ProviderHandler(BaseHTTPRequestHandler):
    state: _ProviderState

    def _body(self) -> dict:
        length = int(self.headers.get("content-length", "0"))
        return json.loads(self.rfile.read(length).decode("utf-8")) if length else {}

    def _send(self, status: HTTPStatus, payload: dict) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(raw)))
        self.end_headers()
        try:
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _before(self, body: dict) -> bool:
        state = self.state
        state.requests.append(
            {
                "method": self.command,
                "path": self.path,
                "authorization": self.headers.get("authorization"),
                "body": body,
            }
        )
        if self.headers.get("authorization") != state.required_authorization:
            self._send(HTTPStatus.UNAUTHORIZED, {"error": "missing authorization"})
            return False
        if self.path in state.slow_paths:
            time.sleep(0.15)
        if self.path in state.fail_paths:
            self._send(
                HTTPStatus.SERVICE_UNAVAILABLE,
                {"error": "provider-secret-body", "token": "upstream-secret-token"},
            )
            return False
        if self.path in state.oversize_paths:
            self._send(HTTPStatus.OK, {"padding": "x" * 4096})
            return False
        return True

    def do_GET(self) -> None:
        if not self._before({}):
            return
        prefix = "/v1/catalog/"
        if not self.path.startswith(prefix):
            self._send(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        pack = DOMAIN_PACK_REGISTRY.get(self.path[len(prefix) :])
        payload = json.loads(pack.asset_path("catalog").read_text(encoding="utf-8"))
        payload["data_source"] = _remote_metadata(
            self.state, payload["catalog_version"]
        )
        self._send(HTTPStatus.OK, payload)

    def do_POST(self) -> None:
        body = self._body()
        if not self._before(body):
            return
        pack = DOMAIN_PACK_REGISTRY.get(body.get("domain_pack_id"))
        if self.path == "/v1/reviews/query":
            local = StaticReviewDataPort(pack=pack)
            result = local.get({"product_ids": body.get("product_ids", [])})
            if self.state.version_override is not None:
                result["review_snapshot_version"] = self.state.version_override
            metadata = _remote_metadata(
                self.state, result["review_snapshot_version"]
            )
            result["data_source"] = metadata
            for product in result["products"]:
                product.update(metadata)
            self._send(HTTPStatus.OK, result)
            return
        if self.path == "/v1/prices/quote":
            catalog = load_retail_catalog(pack=pack)
            local = StaticPricingDataPort(
                catalog, pack=pack, now=lambda: FIXED_NOW
            )
            result = local.quote({"offer_ids": body.get("offer_ids", [])})
            if self.state.version_override is not None:
                result["quote_version"] = self.state.version_override
            result["data_source"] = _remote_metadata(
                self.state, result["quote_version"]
            )
            self._send(HTTPStatus.OK, result)
            return
        self._send(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def log_message(self, _format: str, *_args) -> None:
        return


class RetailDataPortHTTPTest(unittest.TestCase):
    def setUp(self) -> None:
        self.provider_state = _ProviderState()
        handler = type("ProviderHandler", (_ProviderHandler,), {})
        handler.state = self.provider_state
        self.provider = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.provider.daemon_threads = True
        self.provider_thread = threading.Thread(
            target=self.provider.serve_forever, daemon=True
        )
        self.provider_thread.start()
        self.provider_state.base_url = (
            f"http://127.0.0.1:{self.provider.server_address[1]}"
        )
        self.provider_state.provider_id = retail_provider_id(
            self.provider_state.base_url
        )
        self.discovery_servers: list[tuple[ThreadingHTTPServer, threading.Thread]] = []

    def tearDown(self) -> None:
        for server, thread in self.discovery_servers:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
        self.provider.shutdown()
        self.provider.server_close()
        self.provider_thread.join(timeout=2)

    def _env(self, *, fallback: bool = False) -> dict[str, str]:
        return {
            "MOYUAN_RETAIL_DATA_MODE": "http",
            "MOYUAN_RETAIL_DATA_BASE_URL": self.provider_state.base_url,
            "MOYUAN_RETAIL_DATA_API_KEY": "test-secret",
            "MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS": "1",
            "MOYUAN_RETAIL_DATA_FALLBACK_ENABLED": (
                "true" if fallback else "false"
            ),
        }

    def _start_discovery(self, *, fallback: bool = False):
        with patch.dict(os.environ, self._env(fallback=fallback), clear=True):
            server = build_server("127.0.0.1", 0)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.discovery_servers.append((server, thread))
        return server, f"http://127.0.0.1:{server.server_address[1]}"

    @staticmethod
    def _post(base_url: str, path: str, payload: dict):
        request = urllib.request.Request(
            base_url + path,
            data=json.dumps(payload).encode("utf-8"),
            headers={"content-type": "application/json"},
            method="POST",
        )
        return urllib.request.urlopen(request, timeout=2)

    def test_two_pack_catalog_boot_and_remote_review_quote_end_to_end(self) -> None:
        _, base_url = self._start_discovery()
        catalog_paths = {
            item["path"]
            for item in self.provider_state.requests
            if item["method"] == "GET"
        }
        self.assertEqual(
            catalog_paths,
            {
                "/v1/catalog/normal-3c-v1",
                "/v1/catalog/outdoor-camping-v1",
            },
        )

        reviews = json.load(
            self._post(
                base_url,
                "/api/v2/evidence/reviews",
                {"product_ids": ["spu-honor-200"]},
            )
        )
        quote = json.load(
            self._post(
                base_url,
                "/api/v2/pricing/quote",
                {"offer_ids": ["offer-honor-200-256-green-self"]},
            )
        )
        self.assertEqual(reviews["data_source"]["source"], "remote_provider")
        self.assertEqual(
            reviews["products"][0]["provider_id"],
            self.provider_state.provider_id,
        )
        self.assertEqual(quote["data_source"]["source_version"], quote["quote_version"])
        self.assertEqual(quote["quotes"][0]["status"], "active")
        self.assertTrue(
            all(
                request["authorization"] == "Bearer test-secret"
                for request in self.provider_state.requests
            )
        )
        health = json.load(urllib.request.urlopen(base_url + "/health/ready", timeout=2))
        self.assertEqual(health["status"], "UP")
        self.assertEqual(
            health["retailSources"]["catalog"]["providerId"],
            self.provider_state.provider_id,
        )
        self.assertGreaterEqual(
            health["retailSources"]["reviews"]["telemetry"]["requests"], 1
        )

    def test_strict_runtime_failure_is_503_and_marks_readiness_down(self) -> None:
        _, base_url = self._start_discovery()
        self.provider_state.fail_paths.add("/v1/prices/quote")
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self._post(
                base_url,
                "/api/v2/pricing/quote",
                {"offer_ids": ["offer-honor-200-256-green-self"]},
            )
        try:
            self.assertEqual(caught.exception.code, 503)
            body = json.load(caught.exception)
            self.assertEqual(
                body,
                {
                    "error": "retail_data_unavailable",
                    "code": "provider_http_error",
                },
            )
        finally:
            caught.exception.close()
        with self.assertRaises(urllib.error.HTTPError) as health_error:
            urllib.request.urlopen(base_url + "/health/ready", timeout=2)
        try:
            health = json.load(health_error.exception)
        finally:
            health_error.exception.close()
        self.assertEqual(health["retailSources"]["pricing"]["status"], "down")
        serialized = json.dumps(health, ensure_ascii=False)
        self.assertNotIn("test-secret", serialized)
        self.assertNotIn("upstream-secret-token", serialized)
        self.assertNotIn(self.provider_state.base_url, serialized)

    def test_explicit_fallback_is_local_degraded_and_ready(self) -> None:
        self.provider_state.fail_paths.update(
            {
                "/v1/catalog/normal-3c-v1",
                "/v1/catalog/outdoor-camping-v1",
                "/v1/reviews/query",
                "/v1/prices/quote",
            }
        )
        _, base_url = self._start_discovery(fallback=True)
        reviews = json.load(
            self._post(
                base_url,
                "/api/v2/evidence/reviews",
                {"product_ids": ["spu-honor-200"]},
            )
        )
        quote = json.load(
            self._post(
                base_url,
                "/api/v2/pricing/quote",
                {"offer_ids": ["offer-honor-200-256-green-self"]},
            )
        )
        self.assertEqual(reviews["data_source"]["source"], "local_snapshot")
        self.assertEqual(quote["data_source"]["source"], "local_snapshot")
        health = json.load(urllib.request.urlopen(base_url + "/health/ready", timeout=2))
        self.assertTrue(health["ready"])
        self.assertEqual(health["status"], "DEGRADED")
        for name in ("catalog", "reviews", "pricing"):
            source = health["retailSources"][name]
            self.assertEqual(source["status"], "degraded")
            self.assertTrue(source["fallbackActive"])
            self.assertEqual(source["providerId"], self.provider_state.provider_id)
            self.assertGreater(source["telemetry"]["fallbacks"], 0)

    def test_strict_catalog_failure_fails_startup(self) -> None:
        self.provider_state.fail_paths.add("/v1/catalog/normal-3c-v1")
        with patch.dict(os.environ, self._env(), clear=True):
            with self.assertRaisesRegex(
                RetailDataPortError, "retail data provider returned HTTP 503"
            ) as caught:
                build_server("127.0.0.1", 0)
        self.assertEqual(caught.exception.code, "provider_http_error")
        self.assertNotIn("upstream-secret-token", str(caught.exception))

    def test_response_limit_and_timeout_are_bounded_and_counted(self) -> None:
        pack = DOMAIN_PACK_REGISTRY.get()
        client = JsonHttpRetailClient(
            self.provider_state.base_url,
            api_key="test-secret",
            timeout_seconds=0.05,
            max_response_bytes=1024,
        )
        reviews = HttpReviewDataPort(
            pack, client, self.provider_state.provider_id
        )
        self.provider_state.oversize_paths.add("/v1/reviews/query")
        with self.assertRaises(RetailDataPortError) as oversized:
            reviews.get({"product_ids": ["spu-honor-200"]})
        self.assertEqual(oversized.exception.code, "provider_response_too_large")

        catalog = load_retail_catalog(pack=pack)
        pricing = HttpPricingDataPort(
            pack, client, self.provider_state.provider_id
        )
        self.provider_state.slow_paths.add("/v1/prices/quote")
        with self.assertRaises(RetailDataPortError) as timed_out:
            pricing.quote({"offer_ids": [catalog.sellable_items()[0].offer.offer_id]})
        self.assertEqual(timed_out.exception.code, "provider_timeout")
        self.assertEqual(reviews.state.snapshot()["telemetry"]["errors"], 1)
        self.assertEqual(pricing.state.snapshot()["telemetry"]["errors"], 1)

    def test_unsafe_remote_version_is_rejected_without_trace_leakage(self) -> None:
        _, base_url = self._start_discovery()
        unsafe = "https://provider.example/catalog?token=secret"
        self.provider_state.version_override = unsafe
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self._post(
                base_url,
                "/api/v2/evidence/reviews",
                {"product_ids": ["spu-honor-200"]},
            )
        try:
            self.assertEqual(caught.exception.code, 503)
            self.assertEqual(
                json.load(caught.exception)["code"], "provider_invalid_response"
            )
        finally:
            caught.exception.close()
        with self.assertRaises(urllib.error.HTTPError) as health_error:
            urllib.request.urlopen(base_url + "/health/ready", timeout=2)
        try:
            serialized = json.dumps(json.load(health_error.exception))
        finally:
            health_error.exception.close()
        self.assertNotIn("provider.example", serialized)
        self.assertNotIn("token", serialized)

    def test_url_and_secret_configuration_fail_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "explicit insecure HTTP opt-in"):
            JsonHttpRetailClient("http://provider.internal:8080")
        with self.assertRaisesRegex(ValueError, "must not contain credentials"):
            JsonHttpRetailClient("https://user:password@provider.example")
        with self.assertRaisesRegex(ValueError, "invalid characters"):
            JsonHttpRetailClient("https://provider.example", api_key="secret\nheader")
        self.assertEqual(
            retail_provider_id("HTTPS://Provider.Example:443/api/"),
            retail_provider_id("https://provider.example/api"),
        )

    def test_factory_static_mode_never_needs_remote_configuration(self) -> None:
        ports = create_retail_data_ports(environ={})
        self.assertTrue(ports.catalog.sellable_items())
        self.assertEqual(
            ports.health()["catalog"]["effectiveSource"], "local_snapshot"
        )


if __name__ == "__main__":
    unittest.main()
