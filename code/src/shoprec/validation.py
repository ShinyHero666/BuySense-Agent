from __future__ import annotations

from typing import Any


class ValidationError(ValueError):
    def __init__(self, field: str, message: str) -> None:
        self.field = field
        self.message = message
        super().__init__(f"{field}: {message}")

    def to_dict(self) -> dict[str, str]:
        return {
            "error": "validation_error",
            "field": self.field,
            "message": self.message,
        }


def require_mapping(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise ValidationError("request", "JSON body must be an object")
    return payload


def reject_unknown_fields(
    payload: dict[str, Any],
    allowed: set[str],
    *,
    field: str = "request",
) -> None:
    unknown = set(payload) - allowed
    if unknown:
        names = ", ".join(sorted(str(name) for name in unknown))
        raise ValidationError(field, f"unknown fields: {names}")


def string_value(
    payload: dict[str, Any],
    field: str,
    default: str = "",
    *,
    required: bool = False,
    max_length: int = 128,
) -> str:
    value = payload.get(field, default)
    if not isinstance(value, str):
        raise ValidationError(field, "must be a string")
    value = value.strip()
    if required and not value:
        raise ValidationError(field, "must not be empty")
    if len(value) > max_length:
        raise ValidationError(field, f"must be at most {max_length} characters")
    return value


def integer_value(
    payload: dict[str, Any],
    field: str,
    default: int,
    *,
    minimum: int,
    maximum: int,
) -> int:
    value = payload.get(field, default)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValidationError(field, "must be an integer")
    if value < minimum or value > maximum:
        raise ValidationError(
            field, f"must be between {minimum} and {maximum}, got {value}"
        )
    return value


def boolean_value(payload: dict[str, Any], field: str, default: bool) -> bool:
    value = payload.get(field, default)
    if not isinstance(value, bool):
        raise ValidationError(field, "must be true or false")
    return value
