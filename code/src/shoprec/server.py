from __future__ import annotations

import argparse
import json
import logging
import os
import signal
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from .retail_decision import create_retail_decision_service
from .retail_discovery import create_retail_discovery_service
from .service import create_demo_service
from .shoprec_agent import create_buyer_agent
from .validation import ValidationError


MAX_BODY_BYTES = 1024 * 1024
LOGGER = logging.getLogger(__name__)


def _raise_keyboard_interrupt(_signum, _frame) -> None:
    raise KeyboardInterrupt


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
    ) -> None:
        self.service = service
        self.buyer_agent = buyer_agent
        self.retail_service = retail_service
        self.retail_decision_service = retail_decision_service
        super().__init__(address, handler)


class Handler(BaseHTTPRequestHandler):
    server_version = "MoyuanLab/1.0"

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
        if path in {"/health", "/health/live", "/health/ready"}:
            self._send(
                HTTPStatus.OK,
                {
                    "status": "UP",
                    "agent_model_mode": self.server.buyer_agent.model.mode,
                    "retail_catalog_version": self.server.retail_service.catalog.catalog_version,
                    "review_snapshot_version": self.server.retail_decision_service.reviews.version,
                    "compatibility_graph_version": self.server.retail_decision_service.compatibility.version,
                },
            )
        elif path in {"/agent-lab", "/agent-lab/"}:
            page = Path(__file__).resolve().parent / "static" / "agent_lab.html"
            self._send_html(HTTPStatus.OK, page.read_text(encoding="utf-8"))
        else:
            self._send(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:
        try:
            payload = self._json_body()
            path = urlparse(self.path).path
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
                "/api/v2/discovery/search": self.server.retail_service.search,
                "/api/v2/discovery/recommend": self.server.retail_service.recommend,
                "/api/v2/discovery/ads": self.server.retail_service.ads,
                "/api/v2/evidence/reviews": self.server.retail_decision_service.review_aspects,
                "/api/v2/evidence/compatibility": self.server.retail_decision_service.check_compatibility,
                "/api/v2/pricing/quote": self.server.retail_decision_service.quote,
                "/api/v2/decision/fuse": self.server.retail_decision_service.fuse,
                "/api/v2/decision/bundles": self.server.retail_decision_service.optimize_bundles,
            }
            action = routes.get(path)
            if not action:
                self._send(HTTPStatus.NOT_FOUND, {"error": "not_found"})
                return
            self._send(HTTPStatus.OK, action(payload))
        except ValidationError as error:
            self._send(HTTPStatus.BAD_REQUEST, error.to_dict())
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
) -> MoyuanHTTPServer:
    if not 0 <= port <= 65535:
        raise ValueError(f"port must be between 0 and 65535, got {port}")
    runtime_retail_service = retail_service or create_retail_discovery_service()
    return MoyuanHTTPServer(
        (host, port),
        Handler,
        service or create_demo_service(),
        buyer_agent or create_buyer_agent(),
        runtime_retail_service,
        retail_decision_service
        or create_retail_decision_service(runtime_retail_service.catalog),
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
