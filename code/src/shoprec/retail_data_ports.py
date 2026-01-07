from __future__ import annotations

import hashlib
import ipaddress
import json
import math
import os
import re
import socket
import threading
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, Protocol
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

from .generated_contracts_v2 import (
    DataSourceKind,
    DataSourceMetadataWireRecord,
    PricingQuoteWireRequest,
    PricingQuoteWireResponse,
    ReviewEvidenceWireRequest,
    ReviewEvidenceWireResponse,
)
from .retail_domain import NORMAL_3C_DOMAIN_PACK_MODEL, RetailDomainPack
from .retail_models import (
    RetailCatalogSnapshot,
    load_retail_catalog,
    parse_retail_catalog,
)
from .validation import ValidationError, reject_unknown_fields, require_mapping


SOURCE_VALUES = frozenset({"local_snapshot", "remote_provider"})
PROVIDER_ID = re.compile(r"^[a-z][a-z0-9._-]{0,63}$")
SAFE_VERSION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
DEFAULT_TIMEOUT_SECONDS = 2.0
MAX_TIMEOUT_SECONDS = 10.0
DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024
MAX_CONFIGURED_RESPONSE_BYTES = 4 * 1024 * 1024
MAX_REQUEST_BYTES = 256 * 1024


class RetailDataPortError(RuntimeError):
    """Sanitized provider failure safe to expose as a stable 503 error code."""

    def __init__(self, code: str, message: str = "retail data provider unavailable") -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class DataSourceMetadata:
    source: DataSourceKind
    source_version: str
    provider_id: str

    def __post_init__(self) -> None:
        if self.source not in SOURCE_VALUES:
            raise ValueError("data source is unsupported")
        _safe_version(self.source_version, "source_version")
        _provider_id(self.provider_id)

    def to_wire(self) -> DataSourceMetadataWireRecord:
        return {
            "source": self.source,
            "source_version": self.source_version,
            "provider_id": self.provider_id,
        }


class RetailSourceState:
    """Thread-safe health and low-cardinality telemetry for one configured port."""

    def __init__(self, configured_mode: str, *, provider_id: str | None = None) -> None:
        self.configured_mode = configured_mode
        self._lock = threading.Lock()
        self._effective_source = (
            "local_snapshot" if configured_mode == "static" else "remote_provider"
        )
        self._status = "up"
        self._fallback_active = False
        self._version: str | None = None
        self._configured_provider_id = provider_id
        self._effective_provider_id = provider_id
        self._last_error_code: str | None = None
        self._requests = 0
        self._errors = 0
        self._fallbacks = 0

    def request_started(self) -> None:
        with self._lock:
            self._requests += 1

    def succeeded(self, metadata: DataSourceMetadata) -> None:
        with self._lock:
            self._effective_source = metadata.source
            self._status = "up"
            self._fallback_active = False
            self._version = metadata.source_version
            self._effective_provider_id = metadata.provider_id
            self._last_error_code = None

    def failed(self, error: RetailDataPortError) -> None:
        with self._lock:
            self._errors += 1
            self._effective_source = "unavailable"
            self._status = "down"
            self._fallback_active = False
            self._version = None
            self._effective_provider_id = None
            self._last_error_code = error.code

    def fell_back(
        self, metadata: DataSourceMetadata, error: RetailDataPortError
    ) -> None:
        with self._lock:
            self._fallbacks += 1
            self._effective_source = metadata.source
            self._status = "degraded"
            self._fallback_active = True
            self._version = metadata.source_version
            self._effective_provider_id = metadata.provider_id
            self._last_error_code = error.code

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            result: dict[str, Any] = {
                "configuredMode": self.configured_mode,
                "effectiveSource": self._effective_source,
                "status": self._status,
                "fallbackActive": self._fallback_active,
                "version": self._version,
                "providerId": self._configured_provider_id,
                "effectiveProviderId": self._effective_provider_id,
                "telemetry": {
                    "requests": self._requests,
                    "errors": self._errors,
                    "fallbacks": self._fallbacks,
                },
            }
            if self._last_error_code is not None:
                result["lastErrorCode"] = self._last_error_code
            return result


class CatalogDataPort(Protocol):
    state: RetailSourceState

    def load(self) -> tuple[RetailCatalogSnapshot, DataSourceMetadata]: ...


class ReviewDataPort(Protocol):
    state: RetailSourceState
    version: str
    products: dict[str, dict[str, Any]]

    def get(self, payload: ReviewEvidenceWireRequest) -> ReviewEvidenceWireResponse: ...


class PricingDataPort(Protocol):
    state: RetailSourceState

    def quote(self, payload: PricingQuoteWireRequest) -> PricingQuoteWireResponse: ...


class _NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


def _is_loopback(hostname: str) -> bool:
    if hostname.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(hostname).is_loopback
    except ValueError:
        return False


def retail_provider_id(base_url: str) -> str:
    """Return a stable, non-reversible fingerprint for endpoint reuse checks."""
    parsed = urlsplit(base_url)
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
        raise ValueError("retail data base URL must be an absolute HTTP(S) URL")
    try:
        effective_port = parsed.port or (443 if parsed.scheme.lower() == "https" else 80)
    except ValueError as error:
        raise ValueError("retail data base URL contains an invalid port") from error
    hostname = parsed.hostname.lower()
    if ":" in hostname:
        hostname = f"[{hostname}]"
    path = parsed.path.rstrip("/") or "/"
    canonical = f"{parsed.scheme.lower()}://{hostname}:{effective_port}{path}"
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]
    return f"retail-{digest}"


def _json_without_duplicate_keys(raw: bytes) -> Any:
    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                raise ValueError(f"duplicate JSON field: {key}")
            result[key] = value
        return result

    return json.loads(raw.decode("utf-8"), object_pairs_hook=pairs)


class JsonHttpRetailClient:
    """Bounded stdlib JSON client; configured paths never come from end users."""

    def __init__(
        self,
        base_url: str,
        *,
        api_key: str = "",
        timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
        max_response_bytes: int = DEFAULT_MAX_RESPONSE_BYTES,
        allow_insecure_http: bool = False,
    ) -> None:
        parsed = urlsplit(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError("retail data base URL must be an absolute HTTP(S) URL")
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("retail data base URL must not contain credentials, query, or fragment")
        if parsed.scheme == "http" and not (
            allow_insecure_http or _is_loopback(parsed.hostname)
        ):
            raise ValueError(
                "non-loopback retail data HTTP requires explicit insecure HTTP opt-in"
            )
        if not math.isfinite(timeout_seconds) or not 0.05 <= timeout_seconds <= MAX_TIMEOUT_SECONDS:
            raise ValueError(
                f"retail data timeout must be between 0.05 and {MAX_TIMEOUT_SECONDS:g} seconds"
            )
        if not 1024 <= max_response_bytes <= MAX_CONFIGURED_RESPONSE_BYTES:
            raise ValueError(
                f"retail data response limit must be between 1024 and {MAX_CONFIGURED_RESPONSE_BYTES} bytes"
            )
        if "\r" in api_key or "\n" in api_key or len(api_key) > 4096:
            raise ValueError("retail data API key contains invalid characters")
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._timeout_seconds = timeout_seconds
        self._max_response_bytes = max_response_bytes
        self._opener = build_opener(_NoRedirects())

    def _url(self, path: str) -> str:
        if not path.startswith("/") or "?" in path or "#" in path or ".." in path:
            raise ValueError("retail data provider path is invalid")
        return f"{self._base_url}{path}"

    def request(
        self,
        method: str,
        path: str,
        *,
        body: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        encoded = None
        if body is not None:
            encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
            if len(encoded) > MAX_REQUEST_BYTES:
                raise RetailDataPortError("provider_request_too_large")
        headers = {"Accept": "application/json"}
        if encoded is not None:
            headers["Content-Type"] = "application/json"
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        request = Request(
            self._url(path), data=encoded, headers=headers, method=method
        )
        try:
            with self._opener.open(request, timeout=self._timeout_seconds) as response:
                content_type = response.headers.get_content_type()
                if content_type != "application/json":
                    raise RetailDataPortError("provider_invalid_content_type")
                raw = response.read(self._max_response_bytes + 1)
                if len(raw) > self._max_response_bytes:
                    raise RetailDataPortError("provider_response_too_large")
        except HTTPError as error:
            error.close()
            raise RetailDataPortError(
                "provider_http_error", f"retail data provider returned HTTP {error.code}"
            ) from None
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
            payload = _json_without_duplicate_keys(raw)
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
            raise RetailDataPortError("provider_invalid_json") from None
        if not isinstance(payload, dict):
            raise RetailDataPortError("provider_invalid_response")
        return payload


def _non_empty_string(value: Any, field: str, maximum: int = 500) -> str:
    if not isinstance(value, str) or not value.strip() or value != value.strip():
        raise ValueError(f"{field} must be a non-empty string without surrounding whitespace")
    if len(value) > maximum:
        raise ValueError(f"{field} must contain at most {maximum} characters")
    return value


def _mapping(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{field} must be an object")
    return value


def _timestamp(value: Any, field: str) -> str:
    result = _non_empty_string(value, field, 128)
    try:
        parsed = datetime.fromisoformat(result.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{field} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise ValueError(f"{field} must include a timezone")
    return result


def _provider_id(value: Any, field: str = "provider_id") -> str:
    result = _non_empty_string(value, field, 64)
    if PROVIDER_ID.fullmatch(result) is None:
        raise ValueError(f"{field} must be a safe provider identifier")
    return result


def _safe_version(value: Any, field: str) -> str:
    result = _non_empty_string(value, field, 128)
    if SAFE_VERSION.fullmatch(result) is None:
        raise ValueError(f"{field} must be a safe version identifier")
    return result


def _metadata(
    raw: Any,
    *,
    expected_source: str | None = None,
    expected_version: str | None = None,
    expected_provider_id: str | None = None,
) -> DataSourceMetadata:
    value = _mapping(raw, "data_source")
    reject_unknown_fields(
        value, {"source", "source_version", "provider_id"}, field="data_source"
    )
    source = _non_empty_string(value.get("source"), "data_source.source", 32)
    if source not in SOURCE_VALUES:
        raise ValueError("data_source.source is unsupported")
    version = _safe_version(
        value.get("source_version"), "data_source.source_version"
    )
    provider = _provider_id(value.get("provider_id"), "data_source.provider_id")
    if expected_source is not None and source != expected_source:
        raise ValueError("data_source.source does not match the configured source")
    if expected_version is not None and version != expected_version:
        raise ValueError("data_source.source_version does not match the payload version")
    if expected_provider_id is not None and provider != expected_provider_id:
        raise ValueError("data_source.provider_id does not match configuration")
    return DataSourceMetadata(source, version, provider)


def _string_ids(payload: dict[str, Any], field: str) -> list[str]:
    value = payload.get(field)
    if not isinstance(value, list) or len(value) > 100:
        raise ValidationError(field, "must be an array with at most 100 values")
    if not all(
        isinstance(item, str)
        and item
        and item == item.strip()
        and len(item) <= 128
        for item in value
    ):
        raise ValidationError(
            field, "must contain non-empty bounded strings without surrounding whitespace"
        )
    if len(value) != len(set(value)):
        raise ValidationError(field, "must not contain duplicate values")
    return list(value)


class StaticCatalogDataPort:
    def __init__(self, pack: RetailDomainPack) -> None:
        self.pack = pack
        self.state = RetailSourceState("static", provider_id=pack.pack_id)

    def load(self) -> tuple[RetailCatalogSnapshot, DataSourceMetadata]:
        self.state.request_started()
        catalog = load_retail_catalog(pack=self.pack)
        metadata = DataSourceMetadata(
            "local_snapshot", catalog.catalog_version, self.pack.pack_id
        )
        self.state.succeeded(metadata)
        return catalog, metadata


class HttpCatalogDataPort:
    def __init__(
        self,
        pack: RetailDomainPack,
        client: JsonHttpRetailClient,
        provider_id: str,
        *,
        fallback: CatalogDataPort | None = None,
    ) -> None:
        self.pack = pack
        self.client = client
        self.provider_id = _provider_id(provider_id)
        self.fallback = fallback
        self.state = RetailSourceState("http", provider_id=self.provider_id)

    def load(self) -> tuple[RetailCatalogSnapshot, DataSourceMetadata]:
        self.state.request_started()
        try:
            payload = self.client.request(
                "GET", f"/v1/catalog/{quote(self.pack.pack_id, safe='-')}"
            )
            catalog = parse_retail_catalog(payload, pack=self.pack)
            metadata = _metadata(
                payload.get("data_source"),
                expected_source="remote_provider",
                expected_version=catalog.catalog_version,
                expected_provider_id=self.provider_id,
            )
        except RetailDataPortError as error:
            return self._failure(error)
        except (ValueError, ValidationError):
            return self._failure(RetailDataPortError("provider_invalid_response"))
        self.state.succeeded(metadata)
        return catalog, metadata

    def _failure(
        self, error: RetailDataPortError
    ) -> tuple[RetailCatalogSnapshot, DataSourceMetadata]:
        self.state.failed(error)
        if self.fallback is None:
            raise error
        catalog, metadata = self.fallback.load()
        self.state.fell_back(metadata, error)
        return catalog, metadata


def _load_review_products(
    raw: Any,
    *,
    metadata: DataSourceMetadata,
    remote_response: bool,
) -> tuple[str, dict[str, dict[str, Any]]]:
    value = require_mapping(raw)
    allowed = {
        "review_snapshot_version",
        "generated_at",
        "data_source",
        "products",
        "missing_product_ids",
    }
    reject_unknown_fields(value, allowed)
    version = _safe_version(
        value.get("review_snapshot_version"), "review_snapshot_version"
    )
    if metadata.source_version != version:
        raise ValueError("review source version does not match snapshot version")
    products = value.get("products")
    if not isinstance(products, list) or len(products) > 2000:
        raise ValueError("review products must be an array with at most 2000 values")
    parsed: dict[str, dict[str, Any]] = {}
    for index, product_raw in enumerate(products):
        product = _mapping(product_raw, f"products[{index}]")
        product_allowed = {
            "product_id",
            "sample_size",
            "aspects",
            "source",
            "source_version",
            "provider_id",
        }
        reject_unknown_fields(product, product_allowed, field=f"products[{index}]")
        product_id = _non_empty_string(
            product.get("product_id"), f"products[{index}].product_id", 128
        )
        if product_id in parsed:
            raise ValueError(f"duplicate review product_id: {product_id}")
        sample_size = product.get("sample_size")
        if (
            isinstance(sample_size, bool)
            or not isinstance(sample_size, int)
            or sample_size < 0
        ):
            raise ValueError(f"{product_id}.sample_size must be a non-negative integer")
        aspects_raw = product.get("aspects")
        if not isinstance(aspects_raw, list) or len(aspects_raw) > 100:
            raise ValueError(f"{product_id}.aspects must contain at most 100 values")
        aspects: list[dict[str, Any]] = []
        for aspect_index, aspect_raw in enumerate(aspects_raw):
            field = f"{product_id}.aspects[{aspect_index}]"
            aspect = _mapping(aspect_raw, field)
            reject_unknown_fields(
                aspect,
                {"aspect", "sentiment", "mention_count", "confidence", "summary"},
                field=field,
            )
            sentiment = aspect.get("sentiment")
            confidence = aspect.get("confidence")
            mentions = aspect.get("mention_count")
            if (
                isinstance(sentiment, bool)
                or not isinstance(sentiment, (int, float))
                or not math.isfinite(sentiment)
                or not -1 <= sentiment <= 1
            ):
                raise ValueError(f"{field}.sentiment must be between -1 and 1")
            if (
                isinstance(confidence, bool)
                or not isinstance(confidence, (int, float))
                or not math.isfinite(confidence)
                or not 0 <= confidence <= 1
            ):
                raise ValueError(f"{field}.confidence must be between 0 and 1")
            if isinstance(mentions, bool) or not isinstance(mentions, int) or mentions < 0:
                raise ValueError(f"{field}.mention_count must be non-negative")
            aspects.append(
                {
                    "aspect": _non_empty_string(aspect.get("aspect"), f"{field}.aspect", 120),
                    "sentiment": float(sentiment),
                    "mention_count": mentions,
                    "confidence": float(confidence),
                    "summary": _non_empty_string(aspect.get("summary"), f"{field}.summary"),
                }
            )
        if remote_response:
            item_metadata = _metadata(
                {
                    "source": product.get("source"),
                    "source_version": product.get("source_version"),
                    "provider_id": product.get("provider_id"),
                },
                expected_source=metadata.source,
                expected_version=metadata.source_version,
                expected_provider_id=metadata.provider_id,
            )
        else:
            item_metadata = metadata
        parsed[product_id] = {
            "product_id": product_id,
            "sample_size": sample_size,
            "aspects": aspects,
            **item_metadata.to_wire(),
        }
    return version, parsed


class StaticReviewDataPort:
    def __init__(
        self,
        path: str | Path | None = None,
        *,
        pack: RetailDomainPack | None = None,
    ) -> None:
        self.pack = pack or NORMAL_3C_DOMAIN_PACK_MODEL
        raw = json.loads(
            (Path(path) if path else self.pack.asset_path("reviews")).read_text(
                encoding="utf-8"
            )
        )
        version_raw = raw.get("review_snapshot_version") if isinstance(raw, dict) else None
        version = _safe_version(version_raw, "review_snapshot_version")
        self.metadata = DataSourceMetadata("local_snapshot", version, self.pack.pack_id)
        self.version, self.products = _load_review_products(
            raw, metadata=self.metadata, remote_response=False
        )
        self.state = RetailSourceState("static", provider_id=self.pack.pack_id)
        self.state.succeeded(self.metadata)

    def get(self, payload: ReviewEvidenceWireRequest) -> ReviewEvidenceWireResponse:
        value = require_mapping(payload)
        reject_unknown_fields(value, {"product_ids"})
        product_ids = _string_ids(value, "product_ids")
        self.state.request_started()
        self.state.succeeded(self.metadata)
        return {
            "review_snapshot_version": self.version,
            "data_source": self.metadata.to_wire(),
            "products": [
                self.products[product_id]
                for product_id in product_ids
                if product_id in self.products
            ],
            "missing_product_ids": [
                product_id for product_id in product_ids if product_id not in self.products
            ],
        }


def _parse_review_response(
    payload: dict[str, Any],
    requested_ids: list[str],
    provider_id: str,
) -> tuple[ReviewEvidenceWireResponse, DataSourceMetadata]:
    version = _safe_version(
        payload.get("review_snapshot_version"), "review_snapshot_version"
    )
    metadata = _metadata(
        payload.get("data_source"),
        expected_source="remote_provider",
        expected_version=version,
        expected_provider_id=provider_id,
    )
    parsed_version, products_by_id = _load_review_products(
        payload, metadata=metadata, remote_response=True
    )
    missing_raw = payload.get("missing_product_ids")
    if not isinstance(missing_raw, list):
        raise ValueError("missing_product_ids must be an array")
    missing_ids = _string_ids({"missing_product_ids": missing_raw}, "missing_product_ids")
    found_ids = list(products_by_id)
    requested_set = set(requested_ids)
    if (
        not set(found_ids).issubset(requested_set)
        or not set(missing_ids).issubset(requested_set)
        or set(found_ids).intersection(missing_ids)
        or set(found_ids).union(missing_ids) != requested_set
    ):
        raise ValueError("review response does not partition the requested product ids")
    return (
        {
            "review_snapshot_version": parsed_version,
            "data_source": metadata.to_wire(),
            "products": [products_by_id[item] for item in found_ids],
            "missing_product_ids": missing_ids,
        },
        metadata,
    )


class HttpReviewDataPort:
    def __init__(
        self,
        pack: RetailDomainPack,
        client: JsonHttpRetailClient,
        provider_id: str,
        *,
        fallback: ReviewDataPort | None = None,
    ) -> None:
        self.pack = pack
        self.client = client
        self.provider_id = _provider_id(provider_id)
        self.fallback = fallback
        self.state = RetailSourceState("http", provider_id=self.provider_id)

    @property
    def version(self) -> str:
        return str(self.state.snapshot().get("version") or "")

    @property
    def products(self) -> dict[str, dict[str, Any]]:
        fallback_products = getattr(self.fallback, "products", None)
        return fallback_products if isinstance(fallback_products, dict) else {}

    def get(self, payload: ReviewEvidenceWireRequest) -> ReviewEvidenceWireResponse:
        value = require_mapping(payload)
        reject_unknown_fields(value, {"product_ids"})
        product_ids = _string_ids(value, "product_ids")
        self.state.request_started()
        try:
            response = self.client.request(
                "POST",
                "/v1/reviews/query",
                body={"domain_pack_id": self.pack.pack_id, "product_ids": product_ids},
            )
            result, metadata = _parse_review_response(
                response, product_ids, self.provider_id
            )
        except RetailDataPortError as error:
            return self._failure(value, error)
        except (ValueError, ValidationError):
            return self._failure(
                value, RetailDataPortError("provider_invalid_response")
            )
        self.state.succeeded(metadata)
        return result

    def _failure(
        self, payload: dict[str, Any], error: RetailDataPortError
    ) -> ReviewEvidenceWireResponse:
        self.state.failed(error)
        if self.fallback is None:
            raise error
        result = self.fallback.get(payload)
        metadata = _metadata(result["data_source"])
        self.state.fell_back(metadata, error)
        return result


class StaticPricingDataPort:
    def __init__(
        self,
        catalog: RetailCatalogSnapshot,
        *,
        pack: RetailDomainPack | None = None,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self.catalog = catalog
        self.pack = pack or NORMAL_3C_DOMAIN_PACK_MODEL
        self.now = now or (lambda: datetime.now(timezone.utc))
        self.items_by_offer = {
            item.offer.offer_id: item for item in catalog.sellable_items()
        }
        self.metadata = DataSourceMetadata(
            "local_snapshot", f"realtime-{catalog.quote_version}", self.pack.pack_id
        )
        self.state = RetailSourceState("static", provider_id=self.pack.pack_id)
        self.state.succeeded(self.metadata)

    def quote(self, payload: PricingQuoteWireRequest) -> PricingQuoteWireResponse:
        value = require_mapping(payload)
        reject_unknown_fields(value, {"offer_ids"})
        offer_ids = _string_ids(value, "offer_ids")
        self.state.request_started()
        issued_at = self.now().astimezone(timezone.utc)
        ttl_end = issued_at + timedelta(minutes=5)
        quotes: list[dict[str, Any]] = []
        for offer_id in offer_ids:
            item = self.items_by_offer.get(offer_id)
            if item is None:
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
            catalog_expiry = datetime.fromisoformat(
                item.offer.valid_until.replace("Z", "+00:00")
            )
            valid_until = min(ttl_end, catalog_expiry)
            active = item.offer.stock > 0 and valid_until > issued_at
            quotes.append(
                {
                    "offer_id": offer_id,
                    "status": "active" if active else "unavailable",
                    "amount": item.offer.price if active else None,
                    "currency": item.offer.currency,
                    "stock": item.offer.stock if active else 0,
                    "valid_until": valid_until.isoformat(),
                    "reason": (
                        "local_offer_snapshot" if active else "out_of_stock_or_expired"
                    ),
                }
            )
        digest = hashlib.sha256(
            ("|".join(offer_ids) + issued_at.isoformat()).encode("utf-8")
        ).hexdigest()[:16]
        self.state.succeeded(self.metadata)
        return {
            "quote_batch_id": f"quote-batch-{digest}",
            "quote_version": self.metadata.source_version,
            "issued_at": issued_at.isoformat(),
            "data_source": self.metadata.to_wire(),
            "quotes": quotes,
        }


def _parse_pricing_response(
    payload: dict[str, Any], requested_ids: list[str], provider_id: str
) -> tuple[PricingQuoteWireResponse, DataSourceMetadata]:
    reject_unknown_fields(
        payload,
        {"quote_batch_id", "quote_version", "issued_at", "data_source", "quotes"},
    )
    batch_id = _non_empty_string(payload.get("quote_batch_id"), "quote_batch_id", 128)
    version = _safe_version(payload.get("quote_version"), "quote_version")
    issued_at = _timestamp(payload.get("issued_at"), "issued_at")
    issued_at_value = datetime.fromisoformat(issued_at.replace("Z", "+00:00"))
    metadata = _metadata(
        payload.get("data_source"),
        expected_source="remote_provider",
        expected_version=version,
        expected_provider_id=provider_id,
    )
    raw_quotes = payload.get("quotes")
    if not isinstance(raw_quotes, list) or len(raw_quotes) > 100:
        raise ValueError("quotes must be an array with at most 100 values")
    quotes: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, raw_quote in enumerate(raw_quotes):
        item = _mapping(raw_quote, f"quotes[{index}]")
        reject_unknown_fields(
            item,
            {"offer_id", "status", "amount", "currency", "stock", "valid_until", "reason"},
            field=f"quotes[{index}]",
        )
        offer_id = _non_empty_string(item.get("offer_id"), f"quotes[{index}].offer_id", 128)
        if offer_id in seen:
            raise ValueError(f"duplicate quote offer_id: {offer_id}")
        seen.add(offer_id)
        status = item.get("status")
        if status not in {"active", "unavailable"}:
            raise ValueError(f"quotes[{index}].status is unsupported")
        amount = item.get("amount")
        if amount is not None and (
            isinstance(amount, bool)
            or not isinstance(amount, (int, float))
            or not math.isfinite(amount)
            or amount < 0
        ):
            raise ValueError(f"quotes[{index}].amount must be non-negative or null")
        stock = item.get("stock")
        if isinstance(stock, bool) or not isinstance(stock, int) or stock < 0:
            raise ValueError(f"quotes[{index}].stock must be non-negative")
        if item.get("currency") != "CNY":
            raise ValueError(f"quotes[{index}].currency must equal CNY")
        if status == "active" and (amount is None or stock <= 0):
            raise ValueError("active quote must contain amount and positive stock")
        if status == "unavailable" and amount is not None:
            raise ValueError("unavailable quote amount must be null")
        valid_until = _timestamp(
            item.get("valid_until"), f"quotes[{index}].valid_until"
        )
        valid_until_value = datetime.fromisoformat(
            valid_until.replace("Z", "+00:00")
        )
        if status == "active" and valid_until_value <= issued_at_value:
            raise ValueError("active quote must remain valid after issued_at")
        quotes.append(
            {
                "offer_id": offer_id,
                "status": status,
                "amount": None if amount is None else float(amount),
                "currency": "CNY",
                "stock": stock,
                "valid_until": valid_until,
                "reason": _non_empty_string(item.get("reason"), f"quotes[{index}].reason", 240),
            }
        )
    if seen != set(requested_ids):
        raise ValueError("pricing response must contain exactly one quote per requested offer")
    return (
        {
            "quote_batch_id": batch_id,
            "quote_version": version,
            "issued_at": issued_at,
            "data_source": metadata.to_wire(),
            "quotes": quotes,
        },
        metadata,
    )


class HttpPricingDataPort:
    def __init__(
        self,
        pack: RetailDomainPack,
        client: JsonHttpRetailClient,
        provider_id: str,
        *,
        fallback: PricingDataPort | None = None,
    ) -> None:
        self.pack = pack
        self.client = client
        self.provider_id = _provider_id(provider_id)
        self.fallback = fallback
        self.state = RetailSourceState("http", provider_id=self.provider_id)

    def quote(self, payload: PricingQuoteWireRequest) -> PricingQuoteWireResponse:
        value = require_mapping(payload)
        reject_unknown_fields(value, {"offer_ids"})
        offer_ids = _string_ids(value, "offer_ids")
        self.state.request_started()
        try:
            response = self.client.request(
                "POST",
                "/v1/prices/quote",
                body={"domain_pack_id": self.pack.pack_id, "offer_ids": offer_ids},
            )
            result, metadata = _parse_pricing_response(
                response, offer_ids, self.provider_id
            )
        except RetailDataPortError as error:
            return self._failure(value, error)
        except (ValueError, ValidationError):
            return self._failure(
                value, RetailDataPortError("provider_invalid_response")
            )
        self.state.succeeded(metadata)
        return result

    def _failure(
        self, payload: dict[str, Any], error: RetailDataPortError
    ) -> PricingQuoteWireResponse:
        self.state.failed(error)
        if self.fallback is None:
            raise error
        result = self.fallback.quote(payload)
        metadata = _metadata(result["data_source"])
        self.state.fell_back(metadata, error)
        return result


@dataclass(frozen=True)
class RetailDataPorts:
    catalog: RetailCatalogSnapshot
    catalog_metadata: DataSourceMetadata
    catalog_port: CatalogDataPort
    reviews: ReviewDataPort
    pricing: PricingDataPort

    def health(self) -> dict[str, dict[str, Any]]:
        return {
            "catalog": self.catalog_port.state.snapshot(),
            "reviews": self.reviews.state.snapshot(),
            "pricing": self.pricing.state.snapshot(),
        }


def _env_boolean(values: Mapping[str, str], name: str, default: bool) -> bool:
    raw = values.get(name)
    if raw is None or not raw.strip():
        return default
    normalized = raw.strip().lower()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    raise ValueError(f"{name} must be a boolean")


def create_retail_data_ports(
    *,
    pack: RetailDomainPack | None = None,
    environ: Mapping[str, str] | None = None,
    now: Callable[[], datetime] | None = None,
    context: Any | None = None,
) -> RetailDataPorts:
    runtime_pack = pack or NORMAL_3C_DOMAIN_PACK_MODEL
    values = os.environ if environ is None else environ
    mode = values.get("MOYUAN_RETAIL_DATA_MODE", "static").strip().lower()
    if mode not in {"static", "http"}:
        raise ValueError("MOYUAN_RETAIL_DATA_MODE must be static or http")
    if mode == "static":
        if context is not None:
            raise ValueError("a shared retail context cannot be used in static mode")
        static_catalog_port = StaticCatalogDataPort(runtime_pack)
        static_catalog, static_catalog_metadata = static_catalog_port.load()
        static_reviews = StaticReviewDataPort(pack=runtime_pack)
        static_pricing = StaticPricingDataPort(
            static_catalog, pack=runtime_pack, now=now
        )
        return RetailDataPorts(
            static_catalog,
            static_catalog_metadata,
            static_catalog_port,
            static_reviews,
            static_pricing,
        )

    provider = (
        values.get("MOYUAN_RETAIL_DATA_PROVIDER", "generic").strip().lower()
        or "generic"
    )
    if provider not in {"generic", "shopify"}:
        raise ValueError(
            "MOYUAN_RETAIL_DATA_PROVIDER must be generic or shopify in http mode"
        )
    if provider == "shopify":
        from .shopify_retail_provider import (
            ShopifyRetailContext,
            create_shopify_retail_context,
        )

        if context is not None and not isinstance(context, ShopifyRetailContext):
            raise ValueError("shared retail context does not match the Shopify provider")
        owns_context = context is None
        shopify_context = context or create_shopify_retail_context(
            environ=values, now=now
        )
        try:
            result = shopify_context.create_ports(runtime_pack)
        except RetailDataPortError as error:
            if not owns_context or not shopify_context.fallback_enabled:
                raise
            shopify_context.activate_catalog_fallback(error)
            result = shopify_context.create_ports(runtime_pack)
        if owns_context:
            shopify_context.seal_catalog_cohort()
            shopify_context.probe_runtime_ports(result)
        return result

    if context is not None:
        raise ValueError("shared retail context is only supported by the Shopify provider")

    base_url = values.get("MOYUAN_RETAIL_DATA_BASE_URL", "").strip()
    if not base_url:
        raise ValueError("MOYUAN_RETAIL_DATA_BASE_URL is required in http mode")
    provider_id = retail_provider_id(base_url)
    fallback_enabled = _env_boolean(
        values, "MOYUAN_RETAIL_DATA_FALLBACK_ENABLED", False
    )
    fallback_catalog_port: StaticCatalogDataPort | None = None
    fallback_reviews: StaticReviewDataPort | None = None
    fallback_pricing: StaticPricingDataPort | None = None
    if fallback_enabled:
        fallback_catalog_port = StaticCatalogDataPort(runtime_pack)
        fallback_catalog, _ = fallback_catalog_port.load()
        fallback_reviews = StaticReviewDataPort(pack=runtime_pack)
        fallback_pricing = StaticPricingDataPort(
            fallback_catalog, pack=runtime_pack, now=now
        )
    client = JsonHttpRetailClient(
        base_url,
        api_key=values.get("MOYUAN_RETAIL_DATA_API_KEY", ""),
        timeout_seconds=float(
            values.get(
                "MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS", str(DEFAULT_TIMEOUT_SECONDS)
            )
        ),
        max_response_bytes=int(
            values.get(
                "MOYUAN_RETAIL_DATA_MAX_RESPONSE_BYTES",
                str(DEFAULT_MAX_RESPONSE_BYTES),
            )
        ),
        allow_insecure_http=_env_boolean(
            values, "MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP", False
        ),
    )
    catalog_port = HttpCatalogDataPort(
        runtime_pack,
        client,
        provider_id,
        fallback=fallback_catalog_port,
    )
    catalog, catalog_metadata = catalog_port.load()
    remote_reviews = HttpReviewDataPort(
        runtime_pack,
        client,
        provider_id,
        fallback=fallback_reviews,
    )
    remote_pricing = HttpPricingDataPort(
        runtime_pack,
        client,
        provider_id,
        fallback=fallback_pricing,
    )
    return RetailDataPorts(
        catalog,
        catalog_metadata,
        catalog_port,
        remote_reviews,
        remote_pricing,
    )
