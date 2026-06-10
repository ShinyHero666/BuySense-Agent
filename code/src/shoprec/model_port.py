from __future__ import annotations

import hashlib
import json
import os
import socket
from dataclasses import dataclass, field
from typing import Any, Protocol
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class ModelToolCall:
    call_id: str
    name: str
    arguments: dict[str, Any]


@dataclass(frozen=True)
class ModelRequest:
    request_id: str
    session_id: str
    messages: tuple[dict[str, Any], ...]
    tools: tuple[dict[str, Any], ...] = ()
    metadata: dict[str, Any] = field(default_factory=dict)
    idempotency_key: str | None = None


@dataclass(frozen=True)
class ModelResponse:
    content: str
    tool_calls: tuple[ModelToolCall, ...] = ()
    finish_reason: str = "stop"
    usage: dict[str, Any] = field(default_factory=dict)
    request_id: str = ""
    routing_decision_id: str = ""
    routing_mode: str = ""


class ModelClientPort(Protocol):
    mode: str

    def respond(self, request: ModelRequest) -> ModelResponse: ...


class ModelPortError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        status: int | None = None,
        code: str = "model_port_error",
        retry_after: str | None = None,
    ) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.retry_after = retry_after


class ReplayModel:
    """Deterministic model fixture used by default, in CI, and in labs."""

    mode = "replay"

    def respond(self, request: ModelRequest) -> ModelResponse:
        desired_tool = request.metadata.get("desired_tool")
        if desired_tool:
            return ModelResponse(
                content="",
                tool_calls=(
                    ModelToolCall(
                        call_id=f"call-{request.request_id}",
                        name=str(desired_tool),
                        arguments=dict(request.metadata.get("desired_arguments", {})),
                    ),
                ),
                finish_reason="tool_calls",
                usage={"input_tokens": 0, "output_tokens": 0, "source": "replay"},
                request_id=request.request_id,
                routing_mode="offline",
            )
        return ModelResponse(
            content=str(request.metadata.get("fallback_text", "请继续说明你的需求。")),
            usage={"input_tokens": 0, "output_tokens": 0, "source": "replay"},
            request_id=request.request_id,
            routing_mode="offline",
        )


class ModelPortChatCompletionsAdapter:
    """Small stdlib client for the external ModelPort gateway."""

    mode = "modelport"

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        *,
        timeout_seconds: float = 30.0,
        routing_profile: str = "balanced",
        hybrid_mode: str = "",
        data_classification: str = "",
        max_completion_tokens: int = 512,
    ) -> None:
        if not base_url.strip():
            raise ValueError("ModelPort base_url is required")
        if not api_key.strip():
            raise ValueError("ModelPort api_key is required")
        if not model.strip():
            raise ValueError("ModelPort model or alias is required")
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.routing_profile = routing_profile
        self.hybrid_mode = hybrid_mode
        self.data_classification = data_classification
        if max_completion_tokens < 1 or max_completion_tokens > 4096:
            raise ValueError("max_completion_tokens must be between 1 and 4096")
        self.max_completion_tokens = max_completion_tokens

    @classmethod
    def from_env(cls) -> "ModelPortChatCompletionsAdapter":
        return cls(
            os.environ.get(
                "MOYUAN_MODELPORT_BASE_URL", "http://127.0.0.1:38082"
            ),
            os.environ.get("MOYUAN_MODELPORT_API_KEY", ""),
            os.environ.get("MOYUAN_MODELPORT_MODEL", "moyuan-shoprec-agent"),
            timeout_seconds=float(
                os.environ.get("MOYUAN_MODELPORT_TIMEOUT_SECONDS", "30")
            ),
            routing_profile=os.environ.get(
                "MOYUAN_MODELPORT_ROUTING_PROFILE", "balanced"
            ),
            hybrid_mode=os.environ.get(
                "MOYUAN_MODELPORT_HYBRID_MODE", ""
            ),
            data_classification=os.environ.get(
                "MOYUAN_MODELPORT_DATA_CLASSIFICATION", ""
            ),
            max_completion_tokens=int(
                os.environ.get("MOYUAN_MODELPORT_MAX_COMPLETION_TOKENS", "512")
            ),
        )

    def _v1_url(self, path: str) -> str:
        if self.base_url.endswith("/v1"):
            return f"{self.base_url}/{path.lstrip('/')}"
        return f"{self.base_url}/v1/{path.lstrip('/')}"

    def _root_url(self, path: str) -> str:
        root = self.base_url[:-3] if self.base_url.endswith("/v1") else self.base_url
        return f"{root}/{path.lstrip('/')}"

    @staticmethod
    def _safe_error(error: HTTPError) -> tuple[str, str]:
        code = "model_port_http_error"
        message = f"ModelPort returned HTTP {error.code}"
        try:
            payload = json.loads(error.read(65536).decode("utf-8"))
            envelope = payload.get("error", {}) if isinstance(payload, dict) else {}
            if isinstance(envelope, dict):
                code = str(envelope.get("code") or envelope.get("type") or code)
                raw_message = str(envelope.get("message") or "")
                if raw_message and len(raw_message) <= 240:
                    message = raw_message
        except (UnicodeDecodeError, json.JSONDecodeError, OSError):
            pass
        return code, message

    def _headers(self, request: ModelRequest) -> dict[str, str]:
        session_hash = hashlib.sha256(request.session_id.encode("utf-8")).hexdigest()[:24]
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
            "x-request-id": request.request_id,
            "x-modelport-session-id": session_hash,
            "x-modelport-routing-profile": self.routing_profile,
            "x-modelport-traffic-class": str(
                request.metadata.get("traffic_class", "business")
            ),
        }
        if self.hybrid_mode:
            headers["x-modelport-hybrid-mode"] = self.hybrid_mode
        if self.data_classification:
            headers["x-modelport-data-classification"] = self.data_classification
        if request.idempotency_key:
            headers["Idempotency-Key"] = request.idempotency_key
        return headers

    def _json_request(
        self,
        method: str,
        url: str,
        *,
        body: dict[str, Any] | None = None,
        request: ModelRequest | None = None,
    ) -> tuple[Any, Any]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = (
            {"Authorization": f"Bearer {self.api_key}"}
            if request is None
            else self._headers(request)
        )
        http_request = Request(url, data=data, headers=headers, method=method)
        try:
            with urlopen(http_request, timeout=self.timeout_seconds) as response:
                raw = response.read(32 * 1024 * 1024)
                payload = json.loads(raw.decode("utf-8"))
                return payload, response.headers
        except HTTPError as error:
            code, message = self._safe_error(error)
            raise ModelPortError(
                message,
                status=error.code,
                code=code,
                retry_after=error.headers.get("Retry-After"),
            ) from error
        except (URLError, socket.timeout, TimeoutError) as error:
            raise ModelPortError("ModelPort is unavailable", code="unavailable") from error
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ModelPortError(
                "ModelPort returned invalid JSON", code="invalid_response"
            ) from error

    def preflight(self) -> dict[str, Any]:
        ready, _ = self._json_request("GET", self._root_url("readyz"))
        models, _ = self._json_request("GET", self._v1_url("models"))
        rows = models.get("data", []) if isinstance(models, dict) else []
        model_ids = {
            str(row.get("id")) for row in rows if isinstance(row, dict) and row.get("id")
        }
        return {
            "ready": ready,
            "model_visible": self.model in model_ids,
            "model": self.model,
            "catalog_size": len(model_ids),
        }

    def respond(self, request: ModelRequest) -> ModelResponse:
        body: dict[str, Any] = {
            "model": self.model,
            "messages": list(request.messages),
            "stream": False,
            "n": 1,
            "parallel_tool_calls": False,
            "max_completion_tokens": self.max_completion_tokens,
            "temperature": 0.0,
        }
        if request.tools:
            body["tools"] = list(request.tools)
            body["tool_choice"] = "auto"
        payload, headers = self._json_request(
            "POST",
            self._v1_url("chat/completions"),
            body=body,
            request=request,
        )
        choices = payload.get("choices", []) if isinstance(payload, dict) else []
        if not choices or not isinstance(choices[0], dict):
            raise ModelPortError("ModelPort response has no choice", code="invalid_response")
        choice = choices[0]
        message = choice.get("message", {})
        if not isinstance(message, dict):
            raise ModelPortError("ModelPort response has no message", code="invalid_response")
        calls: list[ModelToolCall] = []
        for index, raw_call in enumerate(message.get("tool_calls") or []):
            function = raw_call.get("function", {}) if isinstance(raw_call, dict) else {}
            name = function.get("name") if isinstance(function, dict) else None
            raw_arguments = function.get("arguments", "{}") if isinstance(function, dict) else "{}"
            try:
                arguments = json.loads(raw_arguments)
            except (TypeError, json.JSONDecodeError) as error:
                raise ModelPortError(
                    "ModelPort returned invalid tool arguments",
                    code="invalid_tool_arguments",
                ) from error
            if not name or not isinstance(arguments, dict):
                raise ModelPortError(
                    "ModelPort returned malformed tool call",
                    code="invalid_tool_call",
                )
            calls.append(
                ModelToolCall(
                    call_id=str(raw_call.get("id") or f"call-{index}"),
                    name=str(name),
                    arguments=arguments,
                )
            )
        return ModelResponse(
            content=str(message.get("content") or ""),
            tool_calls=tuple(calls),
            finish_reason=str(choice.get("finish_reason") or "stop"),
            usage=dict(payload.get("usage") or {}),
            request_id=str(headers.get("x-request-id") or request.request_id),
            routing_decision_id=str(
                headers.get("x-modelport-routing-decision-id") or ""
            ),
            routing_mode=str(headers.get("x-modelport-routing-mode") or ""),
        )
