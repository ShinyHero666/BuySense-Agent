from __future__ import annotations

import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from email.message import Message
from pathlib import Path


SCRIPTS_DIR = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import shopify_readonly_canary as canary  # noqa: E402


SENTINEL_TOKEN = "shpat_NEVER_PRINT_THIS_SENTINEL"


class _FakeResponse:
    def __init__(self, payload: dict, *, api_version: str = "2026-07") -> None:
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


class _FakeOpener:
    def __init__(self, response: _FakeResponse) -> None:
        self.response = response
        self.requests = []

    def open(self, request, *, timeout: float):  # noqa: ANN001
        self.requests.append((request, timeout))
        return self.response


def _environment(**overrides: str) -> dict[str, str]:
    values = {
        "MOYUAN_SHOPIFY_STORE_DOMAIN": "moyuan-test.myshopify.com",
        "MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN": SENTINEL_TOKEN,
        "MOYUAN_SHOPIFY_API_VERSION": "2026-07",
        "MOYUAN_SHOPIFY_CANARY_TIMEOUT_SECONDS": "3",
        "MOYUAN_SHOPIFY_CANARY_MAX_RESPONSE_BYTES": "65536",
    }
    values.update(overrides)
    return values


def _success_payload(*, scopes: list[str] | None = None) -> dict:
    return {
        "data": {
            "currentAppInstallation": {
                "accessScopes": [
                    {"handle": scope} for scope in (scopes or ["read_products"])
                ]
            },
            "products": {
                "nodes": [
                    {
                        "id": "gid://shopify/Product/123",
                        "updatedAt": "2026-08-09T12:00:00Z",
                    }
                ]
            },
        }
    }


class ShopifyReadOnlyCanaryTest(unittest.TestCase):
    def _main(self, argv, *, environ, opener=None):
        stdout = io.StringIO()
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            missing_env_file = Path(directory) / ".env"
            with redirect_stdout(stdout), redirect_stderr(stderr):
                status = canary.main(
                    argv,
                    environ=environ,
                    env_file=missing_env_file,
                    opener=opener,
                )
        return status, stdout.getvalue(), stderr.getvalue()

    def test_missing_credentials_is_an_explicit_network_free_skip(self) -> None:
        opener = _FakeOpener(_FakeResponse(_success_payload()))
        status, stdout, stderr = self._main(
            [], environ={}, opener=opener
        )
        self.assertEqual(status, 0)
        self.assertIn("SKIP Shopify read-only canary", stdout)
        self.assertIn("MOYUAN_SHOPIFY_STORE_DOMAIN", stdout)
        self.assertIn("MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN", stdout)
        self.assertEqual(stderr, "")
        self.assertEqual(opener.requests, [])

    def test_script_entrypoint_runs_the_network_free_check(self) -> None:
        environment = os.environ.copy()
        environment["MOYUAN_SHOPIFY_STORE_DOMAIN"] = ""
        environment["MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN"] = ""
        completed = subprocess.run(
            [sys.executable, str(SCRIPTS_DIR / "shopify_readonly_canary.py"), "--check"],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
            timeout=5,
        )
        self.assertEqual(completed.returncode, 0)
        self.assertIn("SKIP Shopify read-only canary", completed.stdout)
        self.assertEqual(completed.stderr, "")

    def test_check_validates_configuration_without_a_request_or_secret_output(self) -> None:
        opener = _FakeOpener(_FakeResponse(_success_payload()))
        status, stdout, stderr = self._main(
            ["--check"], environ=_environment(), opener=opener
        )
        self.assertEqual(status, 0)
        self.assertIn("READY Shopify read-only canary", stdout)
        self.assertNotIn(SENTINEL_TOKEN, stdout + stderr)
        self.assertEqual(opener.requests, [])

    def test_canary_sends_only_the_fixed_query_and_does_not_echo_data(self) -> None:
        opener = _FakeOpener(_FakeResponse(_success_payload()))
        status, stdout, stderr = self._main(
            [], environ=_environment(), opener=opener
        )
        self.assertEqual(status, 0)
        self.assertIn("PASS Shopify read-only canary", stdout)
        self.assertNotIn(SENTINEL_TOKEN, stdout + stderr)
        self.assertNotIn("gid://shopify/Product/123", stdout + stderr)
        self.assertEqual(len(opener.requests), 1)
        request, timeout = opener.requests[0]
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(
            request.full_url,
            "https://moyuan-test.myshopify.com/admin/api/2026-07/graphql.json",
        )
        self.assertEqual(timeout, 3)
        payload = json.loads(request.data)
        self.assertEqual(payload, {"query": canary.READ_ONLY_QUERY})
        self.assertNotRegex(payload["query"].lower(), r"\bmutation\b")
        headers = {key.lower(): value for key, value in request.header_items()}
        self.assertEqual(headers["x-shopify-access-token"], SENTINEL_TOKEN)

    def test_write_scope_fails_closed_without_printing_the_token(self) -> None:
        opener = _FakeOpener(
            _FakeResponse(
                _success_payload(scopes=["read_products", "write_products"])
            )
        )
        status, stdout, stderr = self._main(
            [], environ=_environment(), opener=opener
        )
        self.assertEqual(status, 1)
        self.assertEqual(stdout, "")
        self.assertIn("write_scope_present", stderr)
        self.assertNotIn(SENTINEL_TOKEN, stderr)

    def test_graphql_error_body_is_not_reflected(self) -> None:
        response_secret = "merchant-response-secret"
        opener = _FakeOpener(
            _FakeResponse(
                {
                    "data": {
                        "currentAppInstallation": {
                            "accessScopes": [{"handle": "read_products"}]
                        },
                        "products": None,
                    },
                    "errors": [
                        {
                            "message": response_secret,
                            "extensions": {"token": SENTINEL_TOKEN},
                        }
                    ],
                }
            )
        )
        status, stdout, stderr = self._main(
            [], environ=_environment(), opener=opener
        )
        self.assertEqual(status, 1)
        self.assertIn("graphql_query_failed", stderr)
        self.assertNotIn(response_secret, stdout + stderr)
        self.assertNotIn(SENTINEL_TOKEN, stdout + stderr)

    def test_version_fall_forward_is_rejected(self) -> None:
        opener = _FakeOpener(
            _FakeResponse(_success_payload(), api_version="2026-10")
        )
        status, stdout, stderr = self._main(
            [], environ=_environment(), opener=opener
        )
        self.assertEqual(status, 1)
        self.assertIn("api_version_mismatch", stderr)
        self.assertNotIn(SENTINEL_TOKEN, stdout + stderr)

    def test_unsupported_configured_version_never_reaches_the_network(self) -> None:
        opener = _FakeOpener(_FakeResponse(_success_payload()))
        status, stdout, stderr = self._main(
            [],
            environ=_environment(MOYUAN_SHOPIFY_API_VERSION="2026-10"),
            opener=opener,
        )
        self.assertEqual(status, 2)
        self.assertIn("unsupported_api_version", stderr)
        self.assertNotIn(SENTINEL_TOKEN, stdout + stderr)
        self.assertEqual(opener.requests, [])

    def test_invalid_domain_never_reaches_the_network_or_logs_the_token(self) -> None:
        opener = _FakeOpener(_FakeResponse(_success_payload()))
        status, stdout, stderr = self._main(
            [],
            environ=_environment(
                MOYUAN_SHOPIFY_STORE_DOMAIN="https://evil.example/path"
            ),
            opener=opener,
        )
        self.assertEqual(status, 2)
        self.assertIn("invalid_store_domain", stderr)
        self.assertNotIn(SENTINEL_TOKEN, stdout + stderr)
        self.assertEqual(opener.requests, [])


if __name__ == "__main__":
    unittest.main()
