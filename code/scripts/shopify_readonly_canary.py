#!/usr/bin/env python3
"""Bounded, read-only Shopify Admin GraphQL connectivity canary."""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import socket
import ssl
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping
from urllib.error import HTTPError, URLError
from urllib.request import (
    HTTPRedirectHandler,
    HTTPSHandler,
    ProxyHandler,
    Request,
    build_opener,
)


CODE_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ENV_FILE = CODE_ROOT / ".env"
DEFAULT_API_VERSION = "2026-07"
DEFAULT_TIMEOUT_SECONDS = 5.0
DEFAULT_MAX_RESPONSE_BYTES = 256 * 1024
MAX_RESPONSE_BYTES = 1024 * 1024
SHOPIFY_ENV_KEYS = frozenset(
    {
        "MOYUAN_SHOPIFY_STORE_DOMAIN",
        "MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN",
        "MOYUAN_SHOPIFY_API_VERSION",
        "MOYUAN_SHOPIFY_CANARY_TIMEOUT_SECONDS",
        "MOYUAN_SHOPIFY_CANARY_MAX_RESPONSE_BYTES",
    }
)
STORE_DOMAIN = re.compile(
    r"^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\.myshopify\.com$"
)
SCOPE_HANDLE = re.compile(r"^[a-z][a-z0-9_]{0,127}$")
PRODUCT_GID = re.compile(r"^gid://shopify/Product/[1-9][0-9]*$")
READ_ONLY_QUERY = """query MoyuanShopifyReadOnlyCanary {
  currentAppInstallation {
    accessScopes {
      handle
    }
  }
  products(first: 1) {
    nodes {
      id
      updatedAt
    }
  }
}"""


class CanaryConfigError(ValueError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class CanaryFailure(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class ShopifyCanaryConfig:
    store_domain: str
    access_token: str
    api_version: str
    timeout_seconds: float
    max_response_bytes: int

    @property
    def endpoint(self) -> str:
        return (
            f"https://{self.store_domain}/admin/api/"
            f"{self.api_version}/graphql.json"
        )


@dataclass(frozen=True)
class ShopifyCanaryResult:
    api_version: str
    product_sample_count: int
    scope_count: int


class _NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


def _parse_env_file(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        raise CanaryConfigError("env_file_unreadable") from error
    parsed: dict[str, str] = {}
    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(
            r"(?:export[ \t]+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)",
            raw_line.strip(),
        )
        if match is None:
            if any(key in raw_line for key in SHOPIFY_ENV_KEYS):
                raise CanaryConfigError("malformed_shopify_setting")
            continue
        key, raw_value = match.groups()
        if key not in SHOPIFY_ENV_KEYS:
            continue
        if key in parsed:
            raise CanaryConfigError("duplicate_shopify_setting")
        value = raw_value.strip()
        if value.startswith(("\"", "'")):
            if len(value) < 2 or value[-1] != value[0]:
                raise CanaryConfigError("unterminated_shopify_setting")
            value = value[1:-1]
        elif any(character.isspace() for character in value):
            raise CanaryConfigError("unquoted_shopify_setting_whitespace")
        parsed[key] = value
    return parsed


def _configuration_values(
    environ: Mapping[str, str], env_file: Path
) -> dict[str, str]:
    file_values = _parse_env_file(env_file)
    return {
        key: environ[key] if key in environ else file_values.get(key, "")
        for key in SHOPIFY_ENV_KEYS
    }


def _missing_credentials(values: Mapping[str, str]) -> tuple[str, ...]:
    missing: list[str] = []
    domain = values.get("MOYUAN_SHOPIFY_STORE_DOMAIN", "").strip()
    token = values.get("MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN", "").strip()
    if not domain:
        missing.append("MOYUAN_SHOPIFY_STORE_DOMAIN")
    if not token or token.startswith("replace-"):
        missing.append("MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN")
    return tuple(missing)


def _float_setting(raw: str, *, default: float) -> float:
    try:
        value = float(raw) if raw else default
    except ValueError as error:
        raise CanaryConfigError("invalid_canary_timeout") from error
    if not math.isfinite(value) or not 0.5 <= value <= 30:
        raise CanaryConfigError("invalid_canary_timeout")
    return value


def _integer_setting(raw: str, *, default: int) -> int:
    if not raw:
        return default
    if not raw.isdigit():
        raise CanaryConfigError("invalid_canary_response_limit")
    value = int(raw)
    if not 1024 <= value <= MAX_RESPONSE_BYTES:
        raise CanaryConfigError("invalid_canary_response_limit")
    return value


def build_config(values: Mapping[str, str]) -> ShopifyCanaryConfig:
    domain = values.get("MOYUAN_SHOPIFY_STORE_DOMAIN", "").strip().lower()
    if STORE_DOMAIN.fullmatch(domain) is None:
        raise CanaryConfigError("invalid_store_domain")
    token = values.get("MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN", "")
    if (
        not token
        or token != token.strip()
        or len(token) > 4096
        or any(ord(character) < 33 or ord(character) > 126 for character in token)
    ):
        raise CanaryConfigError("invalid_admin_access_token")
    version = (
        values.get("MOYUAN_SHOPIFY_API_VERSION", "").strip()
        or DEFAULT_API_VERSION
    )
    if version != DEFAULT_API_VERSION:
        raise CanaryConfigError("unsupported_api_version")
    return ShopifyCanaryConfig(
        store_domain=domain,
        access_token=token,
        api_version=version,
        timeout_seconds=_float_setting(
            values.get("MOYUAN_SHOPIFY_CANARY_TIMEOUT_SECONDS", "").strip(),
            default=DEFAULT_TIMEOUT_SECONDS,
        ),
        max_response_bytes=_integer_setting(
            values.get("MOYUAN_SHOPIFY_CANARY_MAX_RESPONSE_BYTES", "").strip(),
            default=DEFAULT_MAX_RESPONSE_BYTES,
        ),
    )


def assert_read_only_query(query: str = READ_ONLY_QUERY) -> None:
    normalized = re.sub(r"#[^\n]*", "", query).strip()
    if not normalized.startswith("query ") or re.search(
        r"\bmutation\b", normalized, flags=re.IGNORECASE
    ):
        raise CanaryConfigError("canary_query_is_not_read_only")


def _json_without_duplicate_keys(raw: bytes) -> Any:
    def pairs(items: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate JSON field")
            result[key] = value
        return result

    return json.loads(raw.decode("utf-8"), object_pairs_hook=pairs)


def _default_opener():
    # Ignore proxy environment variables so the Admin token is sent only over the
    # verified TLS connection to the configured *.myshopify.com endpoint.
    return build_opener(
        ProxyHandler({}),
        _NoRedirects(),
        HTTPSHandler(context=ssl.create_default_context()),
    )


def _http_failure_code(status: int) -> str:
    if status == 401:
        return "authentication_failed"
    if status == 403:
        return "access_forbidden"
    if status == 429:
        return "rate_limited"
    return "shopify_http_error"


def _scope_handles(data: Any) -> set[str] | None:
    if not isinstance(data, dict):
        return None
    installation = data.get("currentAppInstallation")
    if not isinstance(installation, dict):
        return None
    raw_scopes = installation.get("accessScopes")
    if not isinstance(raw_scopes, list) or len(raw_scopes) > 512:
        raise CanaryFailure("invalid_scope_response")
    scopes: set[str] = set()
    for raw_scope in raw_scopes:
        if not isinstance(raw_scope, dict):
            raise CanaryFailure("invalid_scope_response")
        handle = raw_scope.get("handle")
        if not isinstance(handle, str) or SCOPE_HANDLE.fullmatch(handle) is None:
            raise CanaryFailure("invalid_scope_response")
        scopes.add(handle)
    return scopes


def _validate_payload(payload: Any) -> tuple[int, int]:
    if not isinstance(payload, dict):
        raise CanaryFailure("invalid_graphql_response")
    data = payload.get("data")
    scopes = _scope_handles(data)
    if scopes is not None:
        if any(scope.startswith("write_") for scope in scopes):
            raise CanaryFailure("write_scope_present")
        if "read_products" not in scopes:
            raise CanaryFailure("missing_read_products_scope")
    errors = payload.get("errors")
    if errors is not None:
        if not isinstance(errors, list) or not errors:
            raise CanaryFailure("invalid_graphql_response")
        raise CanaryFailure("graphql_query_failed")
    if scopes is None:
        raise CanaryFailure("missing_scope_response")
    if not isinstance(data, dict):
        raise CanaryFailure("invalid_graphql_response")
    products = data.get("products")
    if not isinstance(products, dict):
        raise CanaryFailure("invalid_product_response")
    nodes = products.get("nodes")
    if not isinstance(nodes, list) or len(nodes) > 1:
        raise CanaryFailure("invalid_product_response")
    for node in nodes:
        if not isinstance(node, dict):
            raise CanaryFailure("invalid_product_response")
        product_id = node.get("id")
        updated_at = node.get("updatedAt")
        if (
            not isinstance(product_id, str)
            or PRODUCT_GID.fullmatch(product_id) is None
            or not isinstance(updated_at, str)
            or not updated_at
            or len(updated_at) > 128
        ):
            raise CanaryFailure("invalid_product_response")
    return len(nodes), len(scopes)


def run_canary(config: ShopifyCanaryConfig, *, opener=None) -> ShopifyCanaryResult:
    assert_read_only_query()
    body = json.dumps(
        {"query": READ_ONLY_QUERY},
        ensure_ascii=True,
        separators=(",", ":"),
    ).encode("utf-8")
    request = Request(
        config.endpoint,
        data=body,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "User-Agent": "Moyuan-Shopify-ReadOnly-Canary/1.0",
            "X-Shopify-Access-Token": config.access_token,
        },
        method="POST",
    )
    client = opener or _default_opener()
    try:
        with client.open(request, timeout=config.timeout_seconds) as response:
            content_type = response.headers.get_content_type()
            if content_type != "application/json":
                raise CanaryFailure("invalid_content_type")
            response_version = response.headers.get("X-Shopify-API-Version")
            if response_version != config.api_version:
                raise CanaryFailure("api_version_mismatch")
            raw = response.read(config.max_response_bytes + 1)
    except HTTPError as error:
        error.close()
        raise CanaryFailure(_http_failure_code(error.code)) from None
    except (socket.timeout, TimeoutError):
        raise CanaryFailure("request_timeout") from None
    except ssl.SSLError:
        raise CanaryFailure("tls_error") from None
    except URLError as error:
        code = (
            "request_timeout"
            if isinstance(error.reason, (socket.timeout, TimeoutError))
            else "network_error"
        )
        raise CanaryFailure(code) from None
    except OSError:
        raise CanaryFailure("network_error") from None
    if len(raw) > config.max_response_bytes:
        raise CanaryFailure("response_too_large")
    try:
        payload = _json_without_duplicate_keys(raw)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
        raise CanaryFailure("invalid_json_response") from None
    product_count, scope_count = _validate_payload(payload)
    return ShopifyCanaryResult(
        api_version=config.api_version,
        product_sample_count=product_count,
        scope_count=scope_count,
    )


def main(
    argv: list[str] | None = None,
    *,
    environ: Mapping[str, str] | None = None,
    env_file: Path | None = None,
    opener=None,
) -> int:
    parser = argparse.ArgumentParser(
        description="Run one bounded, read-only Shopify Admin GraphQL canary query."
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="validate local configuration without making a network request",
    )
    args = parser.parse_args(argv)
    try:
        assert_read_only_query()
        values = _configuration_values(
            os.environ if environ is None else environ,
            DEFAULT_ENV_FILE if env_file is None else env_file,
        )
        missing = _missing_credentials(values)
        if missing:
            print(f"SKIP Shopify read-only canary: missing {', '.join(missing)}")
            return 0
        config = build_config(values)
        if args.check:
            print(
                "READY Shopify read-only canary: local configuration is valid; "
                "no request was sent"
            )
            return 0
        result = run_canary(config, opener=opener)
        print(
            "PASS Shopify read-only canary: "
            f"api={result.api_version}, products_sampled={result.product_sample_count}, "
            f"scopes_checked={result.scope_count}"
        )
        return 0
    except CanaryConfigError as error:
        print(
            f"CONFIG_ERROR Shopify read-only canary: {error.code}",
            file=sys.stderr,
        )
        return 2
    except CanaryFailure as error:
        print(f"FAIL Shopify read-only canary: {error.code}", file=sys.stderr)
        return 1
    except Exception:
        # Never reflect an unexpected exception: urllib errors, response bodies and
        # environment values can contain credentials or merchant-controlled text.
        print("FAIL Shopify read-only canary: internal_error", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
