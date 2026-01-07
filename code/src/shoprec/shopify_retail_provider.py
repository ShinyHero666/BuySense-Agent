from __future__ import annotations

import hashlib
import json
import math
import re
import socket
import ssl
import threading
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from email.message import Message
from typing import Any, Callable, Mapping
from urllib.error import HTTPError, URLError
from urllib.request import (
    HTTPRedirectHandler,
    HTTPSHandler,
    ProxyHandler,
    Request,
    build_opener,
)

from .generated_contracts_v2 import (
    PricingQuoteWireRequest,
    PricingQuoteWireResponse,
    ReviewEvidenceWireRequest,
    ReviewEvidenceWireResponse,
)
from .retail_data_ports import (
    DataSourceMetadata,
    RetailDataPortError,
    RetailDataPorts,
    RetailSourceState,
    StaticCatalogDataPort,
    StaticPricingDataPort,
    StaticReviewDataPort,
)
from .retail_domain import RetailDomainPack
from .retail_models import RetailCatalogSnapshot, parse_retail_catalog
from .validation import ValidationError, reject_unknown_fields, require_mapping


SHOPIFY_API_VERSION = "2026-07"
DEFAULT_TIMEOUT_SECONDS = 2.0
MAX_TIMEOUT_SECONDS = 10.0
DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
MAX_REQUEST_BYTES = 256 * 1024
MAX_PRODUCTS = 2_000
PAGE_SIZE = 5
VARIANT_PAGE_SIZE = 25
STORE_DOMAIN = re.compile(
    r"^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\.myshopify\.com$"
)
PRODUCT_GID = re.compile(r"^gid://shopify/Product/([1-9][0-9]*)$")
VARIANT_GID = re.compile(r"^gid://shopify/ProductVariant/([1-9][0-9]*)$")


# These are the only documents the adapter will send. In particular, no query or
# field name can be supplied by an HTTP caller.
SHOPIFY_CATALOG_QUERY = """query MoyuanCatalog($first: Int!, $after: String, $query: String!) {
  currentAppInstallation {
    accessScopes { handle }
  }
  products(first: $first, after: $after, query: $query, sortKey: ID) {
    pageInfo { hasNextPage endCursor }
    nodes {
      id
      title
      vendor
      productType
      tags
      updatedAt
      requiresSellingPlan
      publishedInContext(context: {country: CN})
      sarProduct: metafield(namespace: "moyuan", key: "sar_product") {
        type
        value
        jsonValue
      }
      variants(first: 25) {
        pageInfo { hasNextPage }
        nodes {
          id
          title
          sku
          updatedAt
          requiresComponents
          availableForSale
          sellableOnlineQuantity
          contextualPricing(context: {country: CN}) {
            price { amount currencyCode }
          }
          sarVariant: metafield(namespace: "moyuan", key: "sar_variant") {
            type
            value
            jsonValue
          }
        }
      }
    }
  }
}"""

SHOPIFY_REVIEWS_QUERY = """query MoyuanReviews($ids: [ID!]!) {
  nodes(ids: $ids) {
    __typename
    id
    ... on Product {
      updatedAt
      reviewsRating: metafield(namespace: "reviews", key: "rating") {
        type
        value
        jsonValue
      }
      reviewsRatingCount: metafield(namespace: "reviews", key: "rating_count") {
        type
        value
      }
    }
  }
}"""

SHOPIFY_PRICING_QUERY = """query MoyuanPricing($ids: [ID!]!) {
  nodes(ids: $ids) {
    __typename
    id
    ... on ProductVariant {
      updatedAt
      requiresComponents
      availableForSale
      sellableOnlineQuantity
      product { publishedInContext(context: {country: CN}) }
      contextualPricing(context: {country: CN}) {
        price { amount currencyCode }
      }
    }
  }
}"""

_FIXED_QUERIES = frozenset(
    {SHOPIFY_CATALOG_QUERY, SHOPIFY_REVIEWS_QUERY, SHOPIFY_PRICING_QUERY}
)

_NON_FALLBACKABLE_CATALOG_ERRORS = frozenset(
    {
        "provider_access_forbidden",
        "provider_api_version_mismatch",
        "provider_authentication_failed",
        "provider_insufficient_scope",
        "provider_query_cost_exceeded",
        "provider_request_rejected",
        "provider_shop_inactive",
        "provider_write_scope_forbidden",
    }
)


class _NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


def _default_opener():
    # Proxy environment variables must never redirect an Admin token.
    return build_opener(
        ProxyHandler({}),
        _NoRedirects(),
        HTTPSHandler(context=ssl.create_default_context()),
    )


def shopify_provider_id(
    store_domain: str, api_version: str = SHOPIFY_API_VERSION
) -> str:
    domain = _store_domain(store_domain)
    if api_version != SHOPIFY_API_VERSION:
        raise ValueError("unsupported Shopify Admin API version")
    digest = hashlib.sha256(
        f"https://{domain}/admin/api/{api_version}/graphql.json".encode("utf-8")
    ).hexdigest()[:16]
    return f"shopify-{digest}"


def _store_domain(value: Any) -> str:
    if not isinstance(value, str) or value != value.strip():
        raise ValueError("MOYUAN_SHOPIFY_STORE_DOMAIN must be a bare myshopify.com domain")
    domain = value.lower()
    if STORE_DOMAIN.fullmatch(domain) is None:
        raise ValueError("MOYUAN_SHOPIFY_STORE_DOMAIN must be a bare myshopify.com domain")
    return domain


def _boolean(values: Mapping[str, str], name: str, default: bool) -> bool:
    raw = values.get(name)
    if raw is None or not raw.strip():
        return default
    normalized = raw.strip().lower()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    raise ValueError(f"{name} must be a boolean")


def _timeout(value: str) -> float:
    try:
        parsed = float(value)
    except ValueError as error:
        raise ValueError("MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS must be numeric") from error
    if not math.isfinite(parsed) or not 0.05 <= parsed <= MAX_TIMEOUT_SECONDS:
        raise ValueError(
            f"MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS must be between 0.05 and {MAX_TIMEOUT_SECONDS:g}"
        )
    return parsed


def _response_limit(value: str) -> int:
    if not value.isdigit():
        raise ValueError("MOYUAN_RETAIL_DATA_MAX_RESPONSE_BYTES must be an integer")
    parsed = int(value)
    if not 1024 <= parsed <= MAX_RESPONSE_BYTES:
        raise ValueError(
            f"MOYUAN_RETAIL_DATA_MAX_RESPONSE_BYTES must be between 1024 and {MAX_RESPONSE_BYTES}"
        )
    return parsed


def _access_token(value: Any) -> str:
    if (
        not isinstance(value, str)
        or not value
        or value.startswith("replace-")
        or value != value.strip()
        or len(value) > 4096
        or any(ord(character) < 33 or ord(character) > 126 for character in value)
    ):
        raise ValueError("MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN is invalid")
    return value


@dataclass(frozen=True)
class ShopifyRetailConfig:
    store_domain: str
    admin_access_token: str
    api_version: str
    timeout_seconds: float
    max_response_bytes: int
    fallback_enabled: bool

    @property
    def endpoint(self) -> str:
        return (
            f"https://{self.store_domain}/admin/api/"
            f"{self.api_version}/graphql.json"
        )

    @property
    def provider_id(self) -> str:
        return shopify_provider_id(self.store_domain, self.api_version)

    @property
    def shop_hash(self) -> str:
        return hashlib.sha256(self.store_domain.encode("utf-8")).hexdigest()[:12]


def _config(values: Mapping[str, str]) -> ShopifyRetailConfig:
    version = values.get("MOYUAN_SHOPIFY_API_VERSION", "").strip() or SHOPIFY_API_VERSION
    if version != SHOPIFY_API_VERSION:
        raise ValueError(
            f"MOYUAN_SHOPIFY_API_VERSION must equal {SHOPIFY_API_VERSION}"
        )
    return ShopifyRetailConfig(
        store_domain=_store_domain(values.get("MOYUAN_SHOPIFY_STORE_DOMAIN", "")),
        admin_access_token=_access_token(
            values.get("MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN", "")
        ),
        api_version=version,
        timeout_seconds=_timeout(
            values.get(
                "MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS", str(DEFAULT_TIMEOUT_SECONDS)
            )
        ),
        max_response_bytes=_response_limit(
            values.get(
                "MOYUAN_RETAIL_DATA_MAX_RESPONSE_BYTES",
                str(DEFAULT_MAX_RESPONSE_BYTES),
            )
        ),
        fallback_enabled=_boolean(
            values, "MOYUAN_RETAIL_DATA_FALLBACK_ENABLED", False
        ),
    )


def _json_without_duplicates(raw: bytes) -> Any:
    def pairs(items: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate JSON field")
            result[key] = value
        return result

    def constant(_value: str) -> Any:
        raise ValueError("non-finite JSON number")

    return json.loads(
        raw.decode("utf-8"),
        object_pairs_hook=pairs,
        parse_constant=constant,
    )


class ShopifyGraphQLClient:
    def __init__(self, config: ShopifyRetailConfig, *, opener=None) -> None:
        self.config = config
        self._opener = opener or _default_opener()
        self._request_lock = threading.Lock()

    def execute(self, document: str, variables: Mapping[str, Any]) -> dict[str, Any]:
        if document not in _FIXED_QUERIES:
            raise ValueError("Shopify GraphQL document must be a fixed adapter query")
        encoded = json.dumps(
            {"query": document, "variables": dict(variables)},
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        if len(encoded) > MAX_REQUEST_BYTES:
            raise RetailDataPortError("provider_request_too_large")
        request = Request(
            self.config.endpoint,
            data=encoded,
            headers={
                "Accept": "application/json",
                "Content-Type": "application/json",
                "X-Shopify-Access-Token": self.config.admin_access_token,
            },
            method="POST",
        )
        try:
            with self._request_lock:
                with self._opener.open(
                    request, timeout=self.config.timeout_seconds
                ) as response:
                    if response.headers.get_content_type() != "application/json":
                        raise RetailDataPortError("provider_invalid_content_type")
                    if (
                        response.headers.get("X-Shopify-API-Version")
                        != self.config.api_version
                    ):
                        raise RetailDataPortError("provider_api_version_mismatch")
                    raw = response.read(self.config.max_response_bytes + 1)
                    if len(raw) > self.config.max_response_bytes:
                        raise RetailDataPortError("provider_response_too_large")
        except HTTPError as error:
            status = error.code
            error.close()
            code = (
                "provider_authentication_failed"
                if status == 401
                else "provider_access_forbidden"
                if status == 403
                else "provider_rate_limited"
                if status == 429
                else "provider_request_rejected"
                if 400 <= status < 500
                else "provider_http_error"
            )
            raise RetailDataPortError(code) from None
        except (socket.timeout, TimeoutError):
            raise RetailDataPortError("provider_timeout") from None
        except URLError as error:
            code = (
                "provider_timeout"
                if isinstance(error.reason, (socket.timeout, TimeoutError))
                else "provider_network_error"
            )
            raise RetailDataPortError(code) from None
        except OSError:
            raise RetailDataPortError("provider_network_error") from None
        try:
            payload = _json_without_duplicates(raw)
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
            raise RetailDataPortError("provider_invalid_json") from None
        if not isinstance(payload, dict):
            raise RetailDataPortError("provider_invalid_response")
        errors = payload.get("errors")
        if errors is not None:
            if not isinstance(errors, list) or errors:
                raise RetailDataPortError(_graphql_error_code(errors))
        data = payload.get("data")
        if not isinstance(data, dict):
            raise RetailDataPortError("provider_invalid_response")
        return data


def _graphql_error_code(value: Any) -> str:
    if not isinstance(value, list) or not value:
        return "provider_invalid_response"
    codes: set[str] = set()
    for item in value:
        if not isinstance(item, dict):
            continue
        extensions = item.get("extensions")
        if isinstance(extensions, dict) and isinstance(extensions.get("code"), str):
            codes.add(extensions["code"])
    if "ACCESS_DENIED" in codes:
        return "provider_access_forbidden"
    if "MAX_COST_EXCEEDED" in codes:
        return "provider_query_cost_exceeded"
    if "SHOP_INACTIVE" in codes:
        return "provider_shop_inactive"
    if "THROTTLED" in codes:
        return "provider_rate_limited"
    return "provider_graphql_error"


def _mapping(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{field} must be an object")
    return value


def _string(value: Any, field: str, maximum: int = 500) -> str:
    if (
        not isinstance(value, str)
        or not value
        or value != value.strip()
        or len(value) > maximum
    ):
        raise ValueError(f"{field} must be a bounded non-empty string")
    return value


def _timestamp(value: Any, field: str) -> str:
    result = _string(value, field, 128)
    try:
        parsed = datetime.fromisoformat(result.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{field} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise ValueError(f"{field} must include a timezone")
    return result


def _decimal_amount(value: Any, field: str) -> float:
    if not isinstance(value, str) or len(value) > 64:
        raise ValueError(f"{field} must be a decimal string")
    try:
        parsed = Decimal(value)
    except InvalidOperation as error:
        raise ValueError(f"{field} must be a decimal string") from error
    if not parsed.is_finite() or parsed < 0:
        raise ValueError(f"{field} must be a finite non-negative amount")
    result = float(parsed)
    if not math.isfinite(result):
        raise ValueError(f"{field} exceeds the supported numeric range")
    return result


def _gid(value: Any, pattern: re.Pattern[str], field: str) -> tuple[str, str]:
    result = _string(value, field, 128)
    match = pattern.fullmatch(result)
    if match is None:
        raise ValueError(f"{field} is not a supported Shopify GID")
    return result, match.group(1)


def _metafield_json(value: Any, field: str) -> dict[str, Any] | None:
    if value is None:
        return None
    metafield = _mapping(value, field)
    if metafield.get("type") != "json":
        raise ValueError(f"{field}.type must equal json")
    raw = metafield.get("jsonValue")
    if raw is None:
        serialized = metafield.get("value")
        if not isinstance(serialized, str) or len(serialized) > 64 * 1024:
            raise ValueError(f"{field}.value must contain bounded JSON")
        try:
            raw = _json_without_duplicates(serialized.encode("utf-8"))
        except (UnicodeError, json.JSONDecodeError, ValueError) as error:
            raise ValueError(f"{field}.value must contain JSON") from error
    return _mapping(raw, f"{field}.jsonValue")


def _string_list(value: Any, field: str, *, maximum: int = 100) -> list[str]:
    if (
        not isinstance(value, list)
        or len(value) > maximum
        or not all(
            isinstance(item, str)
            and item
            and item == item.strip()
            and len(item) <= 120
            for item in value
        )
    ):
        raise ValueError(f"{field} must be an array of bounded strings")
    return list(value)


def _request_ids(payload: Any, field: str) -> list[str]:
    value = require_mapping(payload)
    reject_unknown_fields(value, {field})
    raw = value.get(field)
    if (
        not isinstance(raw, list)
        or len(raw) > 100
        or not all(
            isinstance(item, str)
            and item
            and item == item.strip()
            and len(item) <= 128
            for item in raw
        )
        or len(raw) != len(set(raw))
    ):
        raise ValidationError(field, "must contain at most 100 unique bounded ids")
    return list(raw)


def _source_version(prefix: str, value: Any) -> str:
    serialized = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return f"shopify-{prefix}-{SHOPIFY_API_VERSION}-{hashlib.sha256(serialized).hexdigest()[:16]}"


def _data_source(provider_id: str, version: str) -> DataSourceMetadata:
    return DataSourceMetadata("remote_provider", version, provider_id)


def _price_and_stock(node: dict[str, Any], field: str) -> tuple[float, int]:
    pricing = _mapping(node.get("contextualPricing"), f"{field}.contextualPricing")
    price = _mapping(pricing.get("price"), f"{field}.contextualPricing.price")
    if price.get("currencyCode") != "CNY":
        raise ValueError(f"{field} contextual price must use CNY")
    amount = _decimal_amount(price.get("amount"), f"{field}.price.amount")
    sellable = node.get("sellableOnlineQuantity")
    if isinstance(sellable, bool) or not isinstance(sellable, int) or sellable < 0:
        raise ValueError(f"{field}.sellableOnlineQuantity must be non-negative")
    return amount, sellable


def _product_metadata(
    node: dict[str, Any], pack: RetailDomainPack
) -> dict[str, Any]:
    marker = f"moyuan-domain-pack-{pack.pack_id}"
    tags = _string_list(node.get("tags"), "product.tags", maximum=250)
    pack_markers = [item for item in tags if item.startswith("moyuan-domain-pack-")]
    if pack_markers != [marker]:
        raise ValueError("Shopify product must have exactly one matching domain pack tag")
    metadata = _metafield_json(node.get("sarProduct"), "product.sarProduct")
    if metadata is None:
        raise ValueError("product.sarProduct is required")
    reject_unknown_fields(
        metadata,
        {"domain_pack_id", "category", "brand", "tags"},
        field="product.sarProduct",
    )
    domain_pack_id = _string(
        metadata.get("domain_pack_id"), "product.sarProduct.domain_pack_id", 64
    )
    _string(metadata.get("category"), "product.sarProduct.category", 64)
    if "brand" in metadata:
        _string(metadata["brand"], "product.sarProduct.brand", 120)
    if "tags" in metadata:
        _string_list(metadata["tags"], "product.sarProduct.tags")
    if domain_pack_id != pack.pack_id:
        raise ValueError("Shopify tag and sar_product domain pack disagree")
    return metadata


def _variant_metadata(value: Any) -> dict[str, Any]:
    metadata = _metafield_json(value, "variant.sarVariant")
    if metadata is None:
        raise ValueError("variant.sarVariant is required")
    reject_unknown_fields(
        metadata,
        {
            "ecosystem",
            "connectors",
            "protocols",
            "max_power_watts",
            "seller_id",
            "sponsored",
            "ad_bid",
            "ad_quality",
        },
        field="variant.sarVariant",
    )
    ecosystem = metadata.get("ecosystem")
    if ecosystem not in {"ios", "android", "universal"}:
        raise ValueError("variant.sarVariant.ecosystem is unsupported")
    result: dict[str, Any] = {
        "ecosystem": ecosystem,
        "connectors": _string_list(
            metadata.get("connectors"), "variant.sarVariant.connectors"
        ),
        "protocols": _string_list(
            metadata.get("protocols"), "variant.sarVariant.protocols"
        ),
    }
    maximum_power = metadata.get("max_power_watts")
    if maximum_power is not None and (
        isinstance(maximum_power, bool)
        or not isinstance(maximum_power, int)
        or maximum_power <= 0
    ):
        raise ValueError("variant.sarVariant.max_power_watts must be positive")
    if maximum_power is not None:
        result["max_power_watts"] = maximum_power
    seller = metadata.get("seller_id")
    if seller is not None:
        result["seller_id"] = _string(seller, "variant.sarVariant.seller_id", 128)
    sponsored = metadata.get("sponsored", False)
    if not isinstance(sponsored, bool):
        raise ValueError("variant.sarVariant.sponsored must be a boolean")
    result["sponsored"] = sponsored
    for name in ("ad_bid", "ad_quality"):
        number = metadata.get(name, 0.0)
        if (
            isinstance(number, bool)
            or not isinstance(number, (int, float))
            or not math.isfinite(number)
            or number < 0
            or (name == "ad_quality" and number > 1)
        ):
            raise ValueError(f"variant.sarVariant.{name} is invalid")
        result[name] = float(number)
    return result


def _validate_read_products_scope(value: Any) -> None:
    installation = _mapping(value, "data.currentAppInstallation")
    scopes = installation.get("accessScopes")
    if not isinstance(scopes, list) or len(scopes) > 512:
        raise RetailDataPortError("provider_invalid_response")
    handles: set[str] = set()
    for index, raw in enumerate(scopes):
        scope = _mapping(raw, f"accessScopes[{index}]")
        handle = _string(scope.get("handle"), f"accessScopes[{index}].handle", 128)
        handles.add(handle)
    if "read_products" not in handles:
        raise RetailDataPortError("provider_insufficient_scope")
    if any(handle.startswith("write_") for handle in handles):
        raise RetailDataPortError("provider_write_scope_forbidden")


class ShopifyRetailContext:
    """One store client/cache shared by every domain pack in a server process."""

    def __init__(
        self,
        config: ShopifyRetailConfig,
        *,
        opener=None,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self.config = config
        self.client = ShopifyGraphQLClient(config, opener=opener)
        self.now = now or (lambda: datetime.now(timezone.utc))
        self._lock = threading.Lock()
        self._catalogs: dict[str, tuple[RetailCatalogSnapshot, DataSourceMetadata]] = {}
        self._catalog_states: dict[str, RetailSourceState] = {}
        self._product_ids: dict[str, dict[str, str]] = {}
        self._variant_ids: dict[str, dict[str, str]] = {}
        self._fallback_error: RetailDataPortError | None = None
        self._sealed = False

    @property
    def provider_id(self) -> str:
        return self.config.provider_id

    @property
    def fallback_enabled(self) -> bool:
        return self.config.fallback_enabled

    def activate_catalog_fallback(self, error: RetailDataPortError) -> None:
        if self._sealed:
            raise RuntimeError("Shopify catalog cohort is already active")
        if error.code in _NON_FALLBACKABLE_CATALOG_ERRORS:
            raise error
        if not self.fallback_enabled:
            raise error
        self._fallback_error = error
        self._catalogs.clear()
        self._product_ids.clear()
        self._variant_ids.clear()

    def seal_catalog_cohort(self) -> None:
        self._sealed = True

    def probe_runtime_ports(self, ports: RetailDataPorts) -> None:
        """Verify review and pricing queries after a remote catalog is published."""
        if not self._sealed:
            raise RuntimeError("Shopify catalog cohort must be sealed before probing")
        if ports.catalog_metadata.source != "remote_provider":
            return
        items = ports.catalog.sellable_items()
        if not items:
            raise RetailDataPortError("provider_invalid_response")
        item = items[0]
        ports.reviews.get({"product_ids": [item.spu.spu_id]})
        ports.pricing.quote({"offer_ids": [item.offer.offer_id]})

    def create_ports(self, pack: RetailDomainPack) -> RetailDataPorts:
        if self._fallback_error is not None:
            return self._fallback_ports(pack, self._fallback_error)
        catalog_port = ShopifyCatalogDataPort(pack, self)
        self._catalog_states[pack.pack_id] = catalog_port.state
        catalog, metadata = catalog_port.load()
        return RetailDataPorts(
            catalog,
            metadata,
            catalog_port,
            ShopifyReviewDataPort(pack, self),
            ShopifyPricingDataPort(pack, self),
        )

    def _fallback_ports(
        self, pack: RetailDomainPack, error: RetailDataPortError
    ) -> RetailDataPorts:
        static_catalog_port = StaticCatalogDataPort(pack)
        catalog, metadata = static_catalog_port.load()
        catalog_port = _ShopifyFallbackCatalogPort(
            catalog,
            metadata,
            self.provider_id,
            error,
            state=self._catalog_states.get(pack.pack_id),
        )
        reviews = _ShopifyFallbackReviewPort(
            StaticReviewDataPort(pack=pack), self.provider_id, error
        )
        pricing = _ShopifyFallbackPricingPort(
            StaticPricingDataPort(catalog, pack=pack, now=self.now),
            self.provider_id,
            error,
        )
        return RetailDataPorts(catalog, metadata, catalog_port, reviews, pricing)

    def catalog_for(
        self, pack: RetailDomainPack
    ) -> tuple[RetailCatalogSnapshot, DataSourceMetadata]:
        with self._lock:
            cached = self._catalogs.get(pack.pack_id)
            if cached is not None:
                return cached
            try:
                product_nodes = self._load_product_nodes(pack)
                result = self._build_catalog(pack, product_nodes)
            except RetailDataPortError:
                raise
            except (ValueError, ValidationError):
                raise RetailDataPortError("provider_invalid_response") from None
            self._catalogs[pack.pack_id] = result
            return result

    def _load_product_nodes(
        self, pack: RetailDomainPack
    ) -> tuple[dict[str, Any], ...]:
        nodes: list[dict[str, Any]] = []
        seen_product_gids: set[str] = set()
        seen_cursors: set[str] = set()
        after: str | None = None
        search_query = (
            "status:active published_status:published "
            f"tag:moyuan-domain-pack-{pack.pack_id}"
        )
        while True:
            data = self.client.execute(
                SHOPIFY_CATALOG_QUERY,
                {"first": PAGE_SIZE, "after": after, "query": search_query},
            )
            _validate_read_products_scope(data.get("currentAppInstallation"))
            products = _mapping(data.get("products"), "data.products")
            page_nodes = products.get("nodes")
            if not isinstance(page_nodes, list) or len(page_nodes) > PAGE_SIZE:
                raise RetailDataPortError("provider_invalid_response")
            for node in page_nodes:
                if not isinstance(node, dict):
                    raise RetailDataPortError("provider_invalid_response")
                gid, _ = _gid(node.get("id"), PRODUCT_GID, "product.id")
                if gid in seen_product_gids:
                    raise RetailDataPortError("provider_invalid_response")
                seen_product_gids.add(gid)
                nodes.append(node)
            if len(nodes) > MAX_PRODUCTS:
                raise RetailDataPortError("provider_catalog_too_large")
            page_info = _mapping(products.get("pageInfo"), "data.products.pageInfo")
            has_next = page_info.get("hasNextPage")
            if not isinstance(has_next, bool):
                raise RetailDataPortError("provider_invalid_response")
            if not has_next:
                break
            end_cursor = page_info.get("endCursor")
            if (
                not isinstance(end_cursor, str)
                or not end_cursor
                or end_cursor in seen_cursors
            ):
                raise RetailDataPortError("provider_invalid_response")
            seen_cursors.add(end_cursor)
            after = end_cursor
        return tuple(nodes)

    def _build_catalog(
        self, pack: RetailDomainPack, nodes: tuple[dict[str, Any], ...]
    ) -> tuple[RetailCatalogSnapshot, DataSourceMetadata]:
        generated_at = self.now().astimezone(timezone.utc)
        valid_until = (generated_at + timedelta(hours=24)).isoformat()
        raw_spus: list[dict[str, Any]] = []
        product_map: dict[str, str] = {}
        variant_map: dict[str, str] = {}
        seen_variant_gids: set[str] = set()
        for product_index, node in enumerate(nodes):
            product_gid, product_number = _gid(
                node.get("id"), PRODUCT_GID, f"products[{product_index}].id"
            )
            metadata = _product_metadata(node, pack)
            _timestamp(node.get("updatedAt"), "product.updatedAt")
            requires_selling_plan = node.get("requiresSellingPlan")
            published_in_context = node.get("publishedInContext")
            if not isinstance(requires_selling_plan, bool) or not isinstance(
                published_in_context, bool
            ):
                raise ValueError("Shopify product sale flags must be booleans")
            if requires_selling_plan or not published_in_context:
                continue
            variants = _mapping(
                node.get("variants"), f"products[{product_index}].variants"
            )
            variant_page = _mapping(
                variants.get("pageInfo"), f"products[{product_index}].variants.pageInfo"
            )
            if variant_page.get("hasNextPage") is not False:
                raise ValueError(
                    f"Shopify product has more than {VARIANT_PAGE_SIZE} variants"
                )
            variant_nodes = variants.get("nodes")
            if not isinstance(variant_nodes, list) or not variant_nodes:
                raise ValueError("selected Shopify product must contain variants")
            spu_id = f"spu-shopify-{self.config.shop_hash}-{product_number}"
            category = str(metadata["category"])
            brand = _string(
                metadata.get("brand") or node.get("vendor"),
                "product.brand",
                120,
            )
            product_tags = list(metadata.get("tags") or [])
            if category not in pack.product_categories:
                raise ValueError("Shopify product category is not declared by the pack")
            skus: list[dict[str, Any]] = []
            for variant_index, variant_raw in enumerate(variant_nodes):
                variant = _mapping(
                    variant_raw,
                    f"products[{product_index}].variants[{variant_index}]",
                )
                variant_gid, variant_number = _gid(
                    variant.get("id"),
                    VARIANT_GID,
                    f"products[{product_index}].variants[{variant_index}].id",
                )
                if variant_gid in seen_variant_gids:
                    raise ValueError("duplicate Shopify variant GID")
                seen_variant_gids.add(variant_gid)
                requires_components = variant.get("requiresComponents")
                available_for_sale = variant.get("availableForSale")
                if not isinstance(requires_components, bool) or not isinstance(
                    available_for_sale, bool
                ):
                    raise ValueError("Shopify variant sale flags must be booleans")
                if requires_components or not available_for_sale:
                    continue
                _timestamp(variant.get("updatedAt"), "variant.updatedAt")
                amount, stock = _price_and_stock(variant, "variant")
                details = _variant_metadata(variant.get("sarVariant"))
                sku_id = f"sku-shopify-{self.config.shop_hash}-{variant_number}"
                offer_id = f"offer-shopify-{self.config.shop_hash}-{variant_number}"
                variant_map[offer_id] = variant_gid
                sku: dict[str, Any] = {
                    "sku_id": sku_id,
                    "title": _string(variant.get("title"), "variant.title"),
                    "ecosystem": details["ecosystem"],
                    "connectors": details["connectors"],
                    "protocols": details["protocols"],
                    "offers": [
                        {
                            "offer_id": offer_id,
                            "seller_id": details.get(
                                "seller_id", f"shopify-{self.config.shop_hash}"
                            ),
                            "price": amount,
                            "currency": "CNY",
                            "stock": stock,
                            "sponsored": details["sponsored"],
                            "valid_until": valid_until,
                            "ad_bid": details["ad_bid"],
                            "ad_quality": details["ad_quality"],
                        }
                    ],
                }
                if "max_power_watts" in details:
                    sku["max_power_watts"] = details["max_power_watts"]
                skus.append(sku)
            if not skus:
                continue
            product_map[spu_id] = product_gid
            raw_spus.append(
                {
                    "spu_id": spu_id,
                    "title": _string(node.get("title"), "product.title"),
                    "category": category,
                    "brand": brand,
                    "tags": product_tags,
                    "skus": skus,
                }
            )
        if not raw_spus:
            raise ValueError(f"Shopify catalog has no products for {pack.pack_id}")
        version_input = {
            "pack": pack.pack_id,
            "products": raw_spus,
            "updates": [node.get("updatedAt") for node in nodes],
        }
        version = _source_version("catalog", version_input)
        raw_catalog = {
            "catalog_version": version,
            "quote_version": version,
            "generated_at": generated_at.isoformat(),
            "spus": raw_spus,
        }
        catalog = parse_retail_catalog(raw_catalog, pack=pack)
        self._product_ids[pack.pack_id] = product_map
        self._variant_ids[pack.pack_id] = variant_map
        return catalog, _data_source(self.provider_id, version)

    def product_gid(self, pack_id: str, product_id: str) -> str | None:
        return self._product_ids.get(pack_id, {}).get(product_id)

    def variant_gid(self, pack_id: str, offer_id: str) -> str | None:
        return self._variant_ids.get(pack_id, {}).get(offer_id)


class ShopifyCatalogDataPort:
    def __init__(self, pack: RetailDomainPack, context: ShopifyRetailContext) -> None:
        self.pack = pack
        self.context = context
        self.state = RetailSourceState("http", provider_id=context.provider_id)

    def load(self) -> tuple[RetailCatalogSnapshot, DataSourceMetadata]:
        self.state.request_started()
        try:
            catalog, metadata = self.context.catalog_for(self.pack)
        except RetailDataPortError as error:
            self.state.failed(error)
            raise
        self.state.succeeded(metadata)
        return catalog, metadata


class ShopifyReviewDataPort:
    def __init__(self, pack: RetailDomainPack, context: ShopifyRetailContext) -> None:
        self.pack = pack
        self.context = context
        self.state = RetailSourceState("http", provider_id=context.provider_id)

    @property
    def version(self) -> str:
        return str(self.state.snapshot().get("version") or "")

    @property
    def products(self) -> dict[str, dict[str, Any]]:
        return {}

    def get(self, payload: ReviewEvidenceWireRequest) -> ReviewEvidenceWireResponse:
        product_ids = _request_ids(payload, "product_ids")
        self.state.request_started()
        known = {
            product_id: self.context.product_gid(self.pack.pack_id, product_id)
            for product_id in product_ids
        }
        gids = [gid for gid in known.values() if gid is not None]
        try:
            data = self.context.client.execute(SHOPIFY_REVIEWS_QUERY, {"ids": gids})
            result, metadata = self._response(product_ids, known, data)
        except RetailDataPortError as error:
            self.state.failed(error)
            raise
        except (ValueError, ValidationError):
            error = RetailDataPortError("provider_invalid_response")
            self.state.failed(error)
            raise error from None
        self.state.succeeded(metadata)
        return result

    def _response(
        self,
        requested_ids: list[str],
        known: dict[str, str | None],
        data: dict[str, Any],
    ) -> tuple[ReviewEvidenceWireResponse, DataSourceMetadata]:
        raw_nodes = data.get("nodes")
        if not isinstance(raw_nodes, list) or len(raw_nodes) > len(requested_ids):
            raise ValueError("Shopify review nodes are invalid")
        by_gid: dict[str, dict[str, Any]] = {}
        expected = set(filter(None, known.values()))
        for index, raw in enumerate(raw_nodes):
            if raw is None:
                continue
            node = _mapping(raw, f"nodes[{index}]")
            if node.get("__typename") != "Product":
                continue
            gid, _ = _gid(node.get("id"), PRODUCT_GID, f"nodes[{index}].id")
            if gid in by_gid or gid not in expected:
                raise ValueError("Shopify review response contains an unexpected id")
            _timestamp(node.get("updatedAt"), f"nodes[{index}].updatedAt")
            by_gid[gid] = node
        version = _source_version("reviews", raw_nodes)
        metadata = _data_source(self.context.provider_id, version)
        products: list[dict[str, Any]] = []
        missing: list[str] = []
        for product_id in requested_ids:
            gid = known[product_id]
            node = by_gid.get(gid or "")
            if node is None:
                missing.append(product_id)
                continue
            rating_raw = node.get("reviewsRating")
            count_raw = node.get("reviewsRatingCount")
            if rating_raw is None and count_raw is None:
                missing.append(product_id)
                continue
            if rating_raw is None or count_raw is None:
                raise ValueError("Shopify standard review metafields are incomplete")
            rating = _rating(rating_raw)
            count = _rating_count(count_raw)
            if rating is None or count is None:
                raise ValueError("Shopify standard review metafields are incomplete")
            sentiment = max(-1.0, min(1.0, (rating - 3.0) / 2.0))
            products.append(
                {
                    "product_id": product_id,
                    "sample_size": count,
                    "aspects": [
                        {
                            "aspect": "overall_rating",
                            "sentiment": sentiment,
                            "mention_count": count,
                            "confidence": 1.0,
                            "summary": f"Average customer rating {rating:g}/5 ({count} reviews)",
                        }
                    ],
                    **metadata.to_wire(),
                }
            )
        return (
            {
                "review_snapshot_version": version,
                "data_source": metadata.to_wire(),
                "products": products,
                "missing_product_ids": missing,
            },
            metadata,
        )


def _rating(value: Any) -> float | None:
    if value is None:
        return None
    metafield = _mapping(value, "reviews.rating")
    if metafield.get("type") != "rating":
        raise ValueError("reviews.rating must have type rating")
    raw = metafield.get("jsonValue")
    if raw is None:
        serialized = metafield.get("value")
        if not isinstance(serialized, str):
            raise ValueError("reviews.rating must contain JSON")
        raw = _json_without_duplicates(serialized.encode("utf-8"))
    rating = _mapping(raw, "reviews.rating.jsonValue")
    try:
        number = float(rating.get("value"))
        minimum = float(rating.get("scale_min"))
        maximum = float(rating.get("scale_max"))
    except (TypeError, ValueError) as error:
        raise ValueError("reviews.rating is invalid") from error
    if not all(math.isfinite(item) for item in (number, minimum, maximum)):
        raise ValueError("reviews.rating is invalid")
    if minimum != 1 or maximum != 5 or not minimum <= number <= maximum:
        raise ValueError("reviews.rating must use the 1-5 scale")
    return number


def _rating_count(value: Any) -> int | None:
    if value is None:
        return None
    metafield = _mapping(value, "reviews.rating_count")
    if metafield.get("type") != "number_integer":
        raise ValueError("reviews.rating_count must have type number_integer")
    raw = metafield.get("value")
    if not isinstance(raw, str) or not raw.isdigit():
        raise ValueError("reviews.rating_count must be a non-negative integer")
    result = int(raw)
    if result > 2**31 - 1:
        raise ValueError("reviews.rating_count is too large")
    return result


class ShopifyPricingDataPort:
    def __init__(self, pack: RetailDomainPack, context: ShopifyRetailContext) -> None:
        self.pack = pack
        self.context = context
        self.state = RetailSourceState("http", provider_id=context.provider_id)

    def quote(self, payload: PricingQuoteWireRequest) -> PricingQuoteWireResponse:
        offer_ids = _request_ids(payload, "offer_ids")
        self.state.request_started()
        known = {
            offer_id: self.context.variant_gid(self.pack.pack_id, offer_id)
            for offer_id in offer_ids
        }
        gids = [gid for gid in known.values() if gid is not None]
        try:
            data = self.context.client.execute(SHOPIFY_PRICING_QUERY, {"ids": gids})
            result, metadata = self._response(offer_ids, known, data)
        except RetailDataPortError as error:
            self.state.failed(error)
            raise
        except (ValueError, ValidationError):
            error = RetailDataPortError("provider_invalid_response")
            self.state.failed(error)
            raise error from None
        self.state.succeeded(metadata)
        return result

    def _response(
        self,
        offer_ids: list[str],
        known: dict[str, str | None],
        data: dict[str, Any],
    ) -> tuple[PricingQuoteWireResponse, DataSourceMetadata]:
        raw_nodes = data.get("nodes")
        if not isinstance(raw_nodes, list) or len(raw_nodes) > len(offer_ids):
            raise ValueError("Shopify pricing nodes are invalid")
        expected = set(filter(None, known.values()))
        by_gid: dict[str, dict[str, Any]] = {}
        for index, raw in enumerate(raw_nodes):
            if raw is None:
                continue
            node = _mapping(raw, f"nodes[{index}]")
            if node.get("__typename") != "ProductVariant":
                continue
            gid, _ = _gid(node.get("id"), VARIANT_GID, f"nodes[{index}].id")
            if gid in by_gid or gid not in expected:
                raise ValueError("Shopify pricing response contains an unexpected id")
            _timestamp(node.get("updatedAt"), f"nodes[{index}].updatedAt")
            by_gid[gid] = node
        issued_at = self.context.now().astimezone(timezone.utc)
        valid_until = (issued_at + timedelta(minutes=5)).isoformat()
        version = _source_version("pricing", raw_nodes)
        metadata = _data_source(self.context.provider_id, version)
        quotes: list[dict[str, Any]] = []
        for offer_id in offer_ids:
            node = by_gid.get(known[offer_id] or "")
            if node is None:
                quotes.append(
                    {
                        "offer_id": offer_id,
                        "status": "unavailable",
                        "amount": None,
                        "currency": "CNY",
                        "stock": 0,
                        "valid_until": issued_at.isoformat(),
                        "reason": "offer_not_found",
                    }
                )
                continue
            requires_components = node.get("requiresComponents")
            available_for_sale = node.get("availableForSale")
            product = _mapping(node.get("product"), "variant.product")
            published_in_context = product.get("publishedInContext")
            if not isinstance(requires_components, bool) or not isinstance(
                available_for_sale, bool
            ) or not isinstance(published_in_context, bool):
                raise ValueError("Shopify variant sale flags must be booleans")
            if requires_components or not available_for_sale or not published_in_context:
                quotes.append(
                    {
                        "offer_id": offer_id,
                        "status": "unavailable",
                        "amount": None,
                        "currency": "CNY",
                        "stock": 0,
                        "valid_until": issued_at.isoformat(),
                        "reason": (
                            "product_not_published_in_cn"
                            if not published_in_context
                            else "offer_not_sellable"
                        ),
                    }
                )
                continue
            amount, stock = _price_and_stock(node, "variant")
            active = stock > 0
            quotes.append(
                {
                    "offer_id": offer_id,
                    "status": "active" if active else "unavailable",
                    "amount": amount if active else None,
                    "currency": "CNY",
                    "stock": stock,
                    "valid_until": valid_until if active else issued_at.isoformat(),
                    "reason": "shopify_contextual_price" if active else "out_of_stock",
                }
            )
        batch_digest = hashlib.sha256(
            ("|".join(offer_ids) + issued_at.isoformat()).encode("utf-8")
        ).hexdigest()[:16]
        return (
            {
                "quote_batch_id": f"shopify-quote-{batch_digest}",
                "quote_version": version,
                "issued_at": issued_at.isoformat(),
                "data_source": metadata.to_wire(),
                "quotes": quotes,
            },
            metadata,
        )


class _ShopifyFallbackCatalogPort:
    def __init__(
        self,
        catalog: RetailCatalogSnapshot,
        metadata: DataSourceMetadata,
        provider_id: str,
        error: RetailDataPortError,
        *,
        state: RetailSourceState | None = None,
    ) -> None:
        self.catalog = catalog
        self.metadata = metadata
        self.state = state or RetailSourceState("http", provider_id=provider_id)
        if not self.state.snapshot()["fallbackActive"]:
            self.state.fell_back(metadata, error)

    def load(self) -> tuple[RetailCatalogSnapshot, DataSourceMetadata]:
        return self.catalog, self.metadata


class _ShopifyFallbackReviewPort:
    def __init__(
        self, delegate: StaticReviewDataPort, provider_id: str, error: RetailDataPortError
    ) -> None:
        self.delegate = delegate
        self.error = error
        self.state = RetailSourceState("http", provider_id=provider_id)
        self.state.fell_back(delegate.metadata, error)

    @property
    def version(self) -> str:
        return self.delegate.version

    @property
    def products(self) -> dict[str, dict[str, Any]]:
        return self.delegate.products

    def get(self, payload: ReviewEvidenceWireRequest) -> ReviewEvidenceWireResponse:
        self.state.request_started()
        return self.delegate.get(payload)


class _ShopifyFallbackPricingPort:
    def __init__(
        self, delegate: StaticPricingDataPort, provider_id: str, error: RetailDataPortError
    ) -> None:
        self.delegate = delegate
        self.error = error
        self.state = RetailSourceState("http", provider_id=provider_id)
        self.state.fell_back(delegate.metadata, error)

    def quote(self, payload: PricingQuoteWireRequest) -> PricingQuoteWireResponse:
        self.state.request_started()
        return self.delegate.quote(payload)


def create_shopify_retail_context(
    *,
    environ: Mapping[str, str] | None = None,
    opener=None,
    now: Callable[[], datetime] | None = None,
) -> ShopifyRetailContext:
    import os

    values = os.environ if environ is None else environ
    return ShopifyRetailContext(_config(values), opener=opener, now=now)
