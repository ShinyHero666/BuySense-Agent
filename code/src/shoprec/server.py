from __future__ import annotations

import argparse
import hmac
import json
import logging
import os
import signal
from dataclasses import asdict, dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

from .retail_decision import RetailDecisionService, create_retail_decision_service
from .retail_data_ports import (
    RetailDataPortError,
    RetailDataPorts,
    create_retail_data_ports,
    retail_provider_id,
)
from .retail_discovery import RetailDiscoveryService, create_retail_discovery_service
from .retail_domain import DOMAIN_PACK_REGISTRY, DomainPackRegistry, RetailDomainPack
from .service import create_demo_service
from .shoprec_agent import create_buyer_agent
from .validation import ValidationError


MAX_BODY_BYTES = 1024 * 1024
LOGGER = logging.getLogger(__name__)


def _raise_keyboard_interrupt(_signum, _frame) -> None:
    raise KeyboardInterrupt


@dataclass(frozen=True)
class DomainRuntime:
    pack: RetailDomainPack
    discovery: RetailDiscoveryService
    decision: RetailDecisionService
    data_ports: RetailDataPorts | None = None


class MoyuanHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(
        self,
        address,
        handler,
        service,
        buyer_agent,
        retail_service,
        retail_decision_service,
        domain_pack_registry,
        domain_runtimes,
        retail_bridge_enabled,
        retail_bridge_api_key,
    ) -> None:
        self.service = service
        self.buyer_agent = buyer_agent
        self.retail_service = retail_service
        self.retail_decision_service = retail_decision_service
        self.domain_pack_registry = domain_pack_registry
        self.domain_runtimes = domain_runtimes
        self.retail_bridge_enabled = retail_bridge_enabled
        self.retail_bridge_api_key = retail_bridge_api_key
        super().__init__(address, handler)

    def domain_runtime(self, pack_id: str | None = None) -> DomainRuntime:
        resolved_id = pack_id or self.domain_pack_registry.default_pack_id
        try:
            return self.domain_runtimes[resolved_id]
        except KeyError as error:
            raise ValidationError(
                "domain_pack_id", f"unknown domain pack: {resolved_id}"
            ) from error


class Handler(BaseHTTPRequestHandler):
    server_version = "MoyuanLab/1.0"

    def _bridge_provider_id(self) -> str:
        host = self.headers.get("Host", "").strip()
        if not host or "/" in host or chr(92) in host:
            raise ValidationError("Host", "must identify the retail bridge endpoint")
        return retail_provider_id(f"http://{host}")

    def _bridge_authorized(self) -> bool:
        expected = f"Bearer {self.server.retail_bridge_api_key}"
        actual = self.headers.get("Authorization", "")
        if hmac.compare_digest(actual, expected):
            return True
        self._send(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
        return False

    def _bridge_metadata(self, metadata) -> dict:
        source = dict(metadata.to_wire() if hasattr(metadata, "to_wire") else metadata)
        if source.get("source") == "remote_provider":
            source["provider_id"] = self._bridge_provider_id()
        return source

    def _bridge_response(self, payload: dict) -> dict:
        result = dict(payload)
        result["data_source"] = self._bridge_metadata(result.get("data_source", {}))
        products = []
        for raw_product in result.get("products", []):
            product = dict(raw_product)
            if product.get("source") == "remote_provider":
                product["provider_id"] = self._bridge_provider_id()
            products.append(product)
        if "products" in result:
            result["products"] = products
        return result

    def _handle_bridge_get(self, path: str) -> bool:
        prefix = "/v1/catalog/"
        if not path.startswith(prefix):
            return False
        if not self.server.retail_bridge_enabled:
            self._send(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return True
        if not self._bridge_authorized():
            return True
        try:
            pack_id = unquote(path[len(prefix) :])
            runtime = self.server.domain_runtime(pack_id)
            if runtime.data_ports is None:
                raise RetailDataPortError("bridge_data_ports_unavailable")
            catalog, metadata = runtime.data_ports.catalog_port.load()
            payload = asdict(catalog)
            payload["data_source"] = self._bridge_metadata(metadata)
            self._send(HTTPStatus.OK, payload)
        except ValidationError as error:
            self._send(HTTPStatus.BAD_REQUEST, error.to_dict())
        except RetailDataPortError as error:
            self._send(
                HTTPStatus.SERVICE_UNAVAILABLE,
                {"error": "retail_data_unavailable", "code": error.code},
            )
        except Exception:
            LOGGER.exception("Retail bridge catalog request failed")
            self._send(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                {"error": "internal_server_error"},
            )
        return True

    def _handle_bridge_post(self, path: str, payload: dict) -> bool:
        fields_by_path = {
            "/v1/reviews/query": "product_ids",
            "/v1/prices/quote": "offer_ids",
        }
        request_field = fields_by_path.get(path)
        if request_field is None:
            return False
        if not self.server.retail_bridge_enabled:
            self._send(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return True
        if not self._bridge_authorized():
            return True
        allowed = {"domain_pack_id", request_field}
        unknown = set(payload) - allowed
        if unknown:
            raise ValidationError(
                "request", "unknown fields: " + ", ".join(sorted(unknown))
            )
        domain_pack_id = payload.get("domain_pack_id")
        if (
            not isinstance(domain_pack_id, str)
            or not domain_pack_id
            or domain_pack_id != domain_pack_id.strip()
        ):
            raise ValidationError(
                "domain_pack_id",
                "must be a non-empty versioned identifier without surrounding whitespace",
            )
        runtime = self.server.domain_runtime(domain_pack_id)
        if runtime.data_ports is None:
            raise RetailDataPortError("bridge_data_ports_unavailable")
        port_payload = {request_field: payload.get(request_field)}
        if path == "/v1/reviews/query":
            result = runtime.data_ports.reviews.get(port_payload)
        else:
            result = runtime.data_ports.pricing.quote(port_payload)
        self._send(HTTPStatus.OK, self._bridge_response(result))
        return True

    def _readiness(self) -> dict:
        def fallback_source(
            runtime: DomainRuntime, name: str, version: str | None
        ) -> dict:
            return {
                "configuredMode": "static",
                "effectiveSource": "local_snapshot",
                "status": "up",
                "fallbackActive": False,
                "version": version,
                "providerId": runtime.pack.pack_id,
                "effectiveProviderId": runtime.pack.pack_id,
                "telemetry": {"requests": 0, "errors": 0, "fallbacks": 0},
            }

        def runtime_components(runtime: DomainRuntime) -> dict:
            catalog = runtime.discovery.catalog
            sellable_item_count = len(runtime.discovery.items)
            review_product_count = len(runtime.decision.reviews.products)
            compatibility_rule_count = len(runtime.decision.compatibility.rules)
            source_health = (
                runtime.data_ports.health()
                if runtime.data_ports is not None
                else {
                    "catalog": fallback_source(
                        runtime, "catalog", catalog.catalog_version
                    ),
                    "reviews": fallback_source(
                        runtime, "reviews", runtime.decision.reviews.version
                    ),
                    "pricing": fallback_source(
                        runtime,
                        "pricing",
                        getattr(
                            getattr(runtime.decision.pricing, "metadata", None),
                            "source_version",
                            catalog.quote_version,
                        ),
                    ),
                }
            )
            catalog_status = source_health["catalog"]["status"]
            if not sellable_item_count:
                catalog_status = "down"
            review_status = source_health["reviews"]["status"]
            if (
                source_health["reviews"]["configuredMode"] == "static"
                and (not runtime.decision.reviews.version or not review_product_count)
            ):
                review_status = "down"
            return {
                "catalog": {
                    "status": catalog_status,
                    "version": catalog.catalog_version,
                    "sellable_items": sellable_item_count,
                },
                "reviews": {
                    "status": review_status,
                    "version": source_health["reviews"]["version"],
                    "products": review_product_count,
                },
                "pricing": {
                    "status": source_health["pricing"]["status"],
                    "version": source_health["pricing"]["version"],
                },
                "compatibility": {
                    "status": "up"
                    if runtime.decision.compatibility.version
                    and compatibility_rule_count
                    else "down",
                    "version": runtime.decision.compatibility.version,
                    "rules": compatibility_rule_count,
                },
                "retailSources": source_health,
            }

        def aggregate_sources(pack_components: dict[str, dict]) -> dict:
            aggregate: dict[str, dict] = {}
            for source_name in ("catalog", "reviews", "pricing"):
                values = [
                    components["retailSources"][source_name]
                    for components in pack_components.values()
                ]
                statuses = {str(value["status"]) for value in values}
                status = (
                    "down"
                    if "down" in statuses
                    else "degraded"
                    if "degraded" in statuses
                    else "up"
                )

                def common(field: str):
                    distinct = {value.get(field) for value in values}
                    return next(iter(distinct)) if len(distinct) == 1 else None

                item = {
                    "configuredMode": common("configuredMode") or "mixed",
                    "effectiveSource": common("effectiveSource") or "mixed",
                    "status": status,
                    "fallbackActive": any(
                        bool(value["fallbackActive"]) for value in values
                    ),
                    "version": common("version"),
                    "providerId": common("providerId"),
                    "effectiveProviderId": common("effectiveProviderId"),
                    "telemetry": {
                        key: sum(
                            int(value["telemetry"][key]) for value in values
                        )
                        for key in ("requests", "errors", "fallbacks")
                    },
                }
                error_codes = {
                    str(value["lastErrorCode"])
                    for value in values
                    if value.get("lastErrorCode")
                }
                if error_codes:
                    item["lastErrorCode"] = (
                        next(iter(error_codes))
                        if len(error_codes) == 1
                        else "multiple_source_errors"
                    )
                aggregate[source_name] = item
            return aggregate

        pack_components = {
            pack_id: runtime_components(runtime)
            for pack_id, runtime in self.server.domain_runtimes.items()
        }
        default_runtime = self.server.domain_runtime()
        components = pack_components[default_runtime.pack.pack_id]
        retail_sources = aggregate_sources(pack_components)
        ready = all(
            component["status"] != "down"
            for current in pack_components.values()
            for name, component in current.items()
            if name != "retailSources"
        )
        degraded = any(
            component["status"] == "degraded"
            for current in pack_components.values()
            for name, component in current.items()
            if name != "retailSources"
        )
        catalog = default_runtime.discovery.catalog
        return {
            "status": "DOWN" if not ready else "DEGRADED" if degraded else "UP",
            "ready": ready,
            "service": "moyuan-sar-discovery-v2",
            "domain_pack_id": self.server.domain_pack_registry.default_pack_id,
            "registered_domain_packs": len(self.server.domain_runtimes),
            "domain_packs": pack_components,
            "retailSources": retail_sources,
            "agent_model_mode": self.server.buyer_agent.model.mode,
            "retail_catalog_version": catalog.catalog_version,
            "review_snapshot_version": components["reviews"]["version"],
            "compatibility_graph_version": self.server.retail_decision_service.compatibility.version,
            "components": components,
        }

    def _json_body(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as error:
            raise ValidationError("Content-Length", "must be an integer") from error
        if length < 0:
            raise ValidationError("Content-Length", "must not be negative")
        if length > MAX_BODY_BYTES:
            raise ValidationError(
                "request", f"body exceeds {MAX_BODY_BYTES} bytes"
            )
        if length == 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def _send(self, status: HTTPStatus, data: dict) -> None:
        body = json.dumps(data, ensure_ascii=False, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_html(self, status: HTTPStatus, body: str) -> None:
        encoded = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if self._handle_bridge_get(path):
            return
        if path == "/api/v2/domain-packs":
            self._send(
                HTTPStatus.OK,
                {
                    "defaultPackId": self.server.domain_pack_registry.default_pack_id,
                    "packs": self.server.domain_pack_registry.metadata(),
                },
            )
        elif path == "/health/live":
            self._send(
                HTTPStatus.OK,
                {
                    "status": "UP",
                    "service": "moyuan-sar-discovery-v2",
                },
            )
        elif path in {"/health", "/health/ready"}:
            try:
                readiness = self._readiness()
            except Exception:
                LOGGER.exception("Readiness check failed")
                readiness = {
                    "status": "DOWN",
                    "ready": False,
                    "service": "moyuan-sar-discovery-v2",
                    "error": "readiness_check_failed",
                    "components": {},
                }
            status = (
                HTTPStatus.OK
                if path == "/health" or readiness["ready"]
                else HTTPStatus.SERVICE_UNAVAILABLE
            )
            self._send(status, readiness)
        elif path in {"/agent-lab", "/agent-lab/"}:
            page = Path(__file__).resolve().parent / "static" / "agent_lab.html"
            self._send_html(HTTPStatus.OK, page.read_text(encoding="utf-8"))
        else:
            self._send(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:
        try:
            payload = self._json_body()
            path = urlparse(self.path).path
            if not isinstance(payload, dict):
                raise ValidationError("request", "must be an object")
            if self._handle_bridge_post(path, payload):
                return
            if path == "/api/agent":
                allowed = {"session_id", "user_id", "message", "confirmed"}
                unknown = set(payload) - allowed
                if unknown:
                    raise ValidationError(
                        "request", "unknown fields: " + ", ".join(sorted(unknown))
                    )
                if "confirmed" in payload and not isinstance(payload["confirmed"], bool):
                    raise ValidationError("confirmed", "must be a boolean")
                for field in ("session_id", "user_id", "message"):
                    if field in payload and not isinstance(payload[field], str):
                        raise ValidationError(field, "must be a string")
                reply = self.server.buyer_agent.handle(
                    str(payload.get("session_id", "web-session")),
                    str(payload.get("user_id", "u001")),
                    payload.get("message", ""),
                    confirmed=payload.get("confirmed", False) is True,
                )
                self._send(HTTPStatus.OK, reply.to_dict())
                return
            routes = {
                "/api/search": self.server.service.search,
                "/api/recommend": self.server.service.recommend,
                "/api/valuation": self.server.service.value_device,
                "/api/events": self.server.service.record_event,
            }
            if path.startswith("/api/v2/"):
                if not isinstance(payload, dict):
                    raise ValidationError("request", "must be an object")
                domain_pack_id = payload.pop(
                    "domain_pack_id", self.server.domain_pack_registry.default_pack_id
                )
                if (
                    not isinstance(domain_pack_id, str)
                    or not domain_pack_id
                    or domain_pack_id != domain_pack_id.strip()
                ):
                    raise ValidationError(
                        "domain_pack_id",
                        "must be a non-empty versioned identifier without surrounding whitespace",
                    )
                runtime = self.server.domain_runtime(domain_pack_id)
                routes.update({
                    "/api/v2/discovery/search": runtime.discovery.search,
                    "/api/v2/discovery/recommend": runtime.discovery.recommend,
                    "/api/v2/discovery/ads": runtime.discovery.ads,
                    "/api/v2/evidence/reviews": runtime.decision.review_aspects,
                    "/api/v2/evidence/compatibility": runtime.decision.check_compatibility,
                    "/api/v2/pricing/quote": runtime.decision.quote,
                    "/api/v2/decision/fuse": runtime.decision.fuse,
                    "/api/v2/decision/bundles": runtime.decision.optimize_bundles,
                })
            action = routes.get(path)
            if not action:
                self._send(HTTPStatus.NOT_FOUND, {"error": "not_found"})
                return
            self._send(HTTPStatus.OK, action(payload))
        except ValidationError as error:
            self._send(HTTPStatus.BAD_REQUEST, error.to_dict())
        except RetailDataPortError as error:
            self._send(
                HTTPStatus.SERVICE_UNAVAILABLE,
                {"error": "retail_data_unavailable", "code": error.code},
            )
        except (json.JSONDecodeError, UnicodeDecodeError) as error:
            self._send(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid_json", "message": str(error)},
            )
        except Exception:
            LOGGER.exception("Unhandled request error")
            self._send(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                {"error": "internal_server_error"},
            )

    def log_message(self, format: str, *args) -> None:
        print(f"[http] {self.address_string()} {format % args}")


def build_server(
    host: str,
    port: int,
    service=None,
    buyer_agent=None,
    retail_service=None,
    retail_decision_service=None,
    domain_pack_registry: DomainPackRegistry | None = None,
) -> MoyuanHTTPServer:
    if not 0 <= port <= 65535:
        raise ValueError(f"port must be between 0 and 65535, got {port}")
    runtime_registry = domain_pack_registry or DOMAIN_PACK_REGISTRY
    packs = runtime_registry.all()

    def has_pack_override(pack: RetailDomainPack) -> bool:
        return pack.pack_id == runtime_registry.default_pack_id and (
            retail_service is not None or retail_decision_service is not None
        )

    # A Shopify store is a single upstream cohort. Stage every catalog before
    # constructing services so an explicitly enabled startup fallback replaces
    # the whole cohort instead of mixing remote and static packs.
    staged_data_ports: dict[str, RetailDataPorts] = {}
    mode = os.environ.get("MOYUAN_RETAIL_DATA_MODE", "static").strip().lower()
    provider = (
        os.environ.get("MOYUAN_RETAIL_DATA_PROVIDER", "generic").strip().lower()
        or "generic"
    )
    if mode == "http" and provider == "shopify":
        from .shopify_retail_provider import create_shopify_retail_context

        shared_context = create_shopify_retail_context()
        managed_packs = [pack for pack in packs if not has_pack_override(pack)]
        try:
            for pack in managed_packs:
                staged_data_ports[pack.pack_id] = create_retail_data_ports(
                    pack=pack, context=shared_context
                )
        except RetailDataPortError as error:
            shared_context.activate_catalog_fallback(error)
            staged_data_ports = {
                pack.pack_id: create_retail_data_ports(
                    pack=pack, context=shared_context
                )
                for pack in managed_packs
            }
        shared_context.seal_catalog_cohort()
        for data_ports in staged_data_ports.values():
            shared_context.probe_runtime_ports(data_ports)

    domain_runtimes: dict[str, DomainRuntime] = {}
    for pack in packs:
        if has_pack_override(pack):
            discovery = retail_service or create_retail_discovery_service(pack=pack)
            decision = retail_decision_service or create_retail_decision_service(
                discovery.catalog, pack=pack
            )
            data_ports = None
        else:
            data_ports = staged_data_ports.get(pack.pack_id)
            if data_ports is None:
                data_ports = create_retail_data_ports(pack=pack)
            discovery = create_retail_discovery_service(
                data_ports.catalog,
                pack=pack,
                catalog_metadata=data_ports.catalog_metadata,
            )
            decision = create_retail_decision_service(
                data_ports.catalog,
                pack=pack,
                reviews=data_ports.reviews,
                pricing=data_ports.pricing,
            )
        domain_runtimes[pack.pack_id] = DomainRuntime(
            pack, discovery, decision, data_ports
        )
    bridge_flag = os.environ.get("MOYUAN_RETAIL_BRIDGE_ENABLED", "false").strip().lower()
    if bridge_flag not in {"true", "false", "1", "0", "yes", "no", "on", "off"}:
        raise ValueError("MOYUAN_RETAIL_BRIDGE_ENABLED must be a boolean")
    retail_bridge_enabled = bridge_flag in {"true", "1", "yes", "on"}
    retail_bridge_api_key = os.environ.get("MOYUAN_RETAIL_BRIDGE_KEY", "")
    if retail_bridge_enabled and not retail_bridge_api_key:
        raise ValueError("enabled retail bridge requires MOYUAN_RETAIL_BRIDGE_KEY")
    if (
        chr(13) in retail_bridge_api_key
        or chr(10) in retail_bridge_api_key
        or len(retail_bridge_api_key) > 4096
    ):
        raise ValueError("retail bridge API key contains invalid characters")

    default_runtime = domain_runtimes[runtime_registry.default_pack_id]
    return MoyuanHTTPServer(
        (host, port),
        Handler,
        service or create_demo_service(),
        buyer_agent or create_buyer_agent(),
        default_runtime.discovery,
        default_runtime.decision,
        runtime_registry,
        domain_runtimes,
        retail_bridge_enabled,
        retail_bridge_api_key,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Moyuan search/recommendation Agent teaching server")
    parser.add_argument(
        "--host",
        default=os.environ.get("SHOPREC_HOST", "0.0.0.0"),
        help="listen address (default: SHOPREC_HOST or 0.0.0.0)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.environ.get("SHOPREC_PORT", "8080")),
        help="listen port (default: SHOPREC_PORT or 8080; use 0 for any free port)",
    )
    parser.add_argument(
        "--config",
        default=os.environ.get("SHOPREC_EXPERIMENT_CONFIG"),
        help="experiment JSON path",
    )
    parser.add_argument(
        "--agent-model-mode",
        choices=("replay", "modelport"),
        default=os.environ.get("MOYUAN_AGENT_MODEL_MODE", "replay"),
        help="Agent model mode; real mode always calls the external ModelPort gateway",
    )
    return parser


def main() -> None:
    args = build_parser().parse_args()
    service = create_demo_service(args.config)
    buyer_agent = create_buyer_agent(
        model_mode=args.agent_model_mode,
        experiment_path=args.config,
    )
    server = build_server(args.host, args.port, service, buyer_agent)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, _raise_keyboard_interrupt)
    actual_host, actual_port = server.server_address
    display_host = "127.0.0.1" if actual_host == "0.0.0.0" else actual_host
    print(f"Moyuan lab is listening on http://{display_host}:{actual_port}")
    print(f"Agent lab: http://{display_host}:{actual_port}/agent-lab")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nMoyuan lab stopped")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
