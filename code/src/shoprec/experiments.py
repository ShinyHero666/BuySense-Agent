from __future__ import annotations

import hashlib
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any


EXPECTED_LAYER_PARAMETERS = {
    "search_rank": {
        "lexical",
        "intent",
        "quality",
        "freshness",
        "pctr",
        "pcvr",
        "trust",
    },
    "recommend_flowpool": {"interest", "explore", "fresh"},
}


@dataclass(frozen=True)
class Variant:
    name: str
    traffic: int
    parameters: dict[str, Any]


class ExperimentManager:
    """Stable bucketing and layered experiment parameters."""

    def __init__(self, layers: dict[str, list[Variant]]) -> None:
        self._validate(layers)
        self.layers = layers

    @staticmethod
    def _validate(layers: dict[str, list[Variant]]) -> None:
        required_layers = {"search_rank", "recommend_flowpool"}
        missing = required_layers - set(layers)
        if missing:
            raise ValueError(
                "experiment config missing required layers: " + ", ".join(sorted(missing))
            )

        for layer, variants in layers.items():
            if not variants:
                raise ValueError(f"experiment layer {layer!r} has no variants")
            if any(
                not isinstance(variant.name, str) or not variant.name.strip()
                for variant in variants
            ):
                raise ValueError(f"experiment layer {layer!r} has an empty variant name")
            names = [variant.name for variant in variants]
            if len(names) != len(set(names)):
                raise ValueError(f"experiment layer {layer!r} has duplicate variant names")
            if any(
                isinstance(variant.traffic, bool)
                or not isinstance(variant.traffic, int)
                for variant in variants
            ):
                raise ValueError(f"experiment layer {layer!r} traffic must be an integer")
            if any(variant.traffic <= 0 for variant in variants):
                raise ValueError(f"experiment layer {layer!r} traffic must be positive")
            traffic = sum(variant.traffic for variant in variants)
            if traffic != 100:
                raise ValueError(
                    f"experiment layer {layer!r} traffic must total 100, got {traffic}"
                )
            parameter_names = set(variants[0].parameters)
            for variant in variants:
                if not variant.parameters:
                    raise ValueError(
                        f"experiment {layer}/{variant.name} has no parameters"
                    )
                if set(variant.parameters) != parameter_names:
                    raise ValueError(
                        f"experiment layer {layer!r} variants must use the same "
                        "parameter names"
                    )
                values = list(variant.parameters.values())
                if not all(
                    not isinstance(value, bool)
                    and isinstance(value, (int, float))
                    and math.isfinite(float(value))
                    for value in values
                ):
                    raise ValueError(
                        f"experiment {layer}/{variant.name} parameters must be "
                        "finite numbers"
                    )
                if any(float(value) < 0 for value in values):
                    raise ValueError(
                        f"experiment {layer}/{variant.name} parameters must be non-negative"
                    )
                total = sum(float(value) for value in values)
                if abs(total - 1.0) > 1e-9:
                    raise ValueError(
                        f"experiment {layer}/{variant.name} parameters must total 1.0, got {total}"
                    )
            expected_parameters = EXPECTED_LAYER_PARAMETERS.get(layer)
            if (
                expected_parameters is not None
                and parameter_names != expected_parameters
            ):
                missing_parameters = sorted(expected_parameters - parameter_names)
                unknown_parameters = sorted(parameter_names - expected_parameters)
                raise ValueError(
                    f"experiment layer {layer!r} parameter contract mismatch; "
                    f"missing={missing_parameters}, unknown={unknown_parameters}"
                )

    @classmethod
    def default(cls) -> "ExperimentManager":
        return cls.from_json(Path(__file__).with_name("default_experiments.json"))

    @classmethod
    def from_json(cls, path: str | Path) -> "ExperimentManager":
        config_path = Path(path)
        try:
            raw = json.loads(config_path.read_text(encoding="utf-8"))
            raw_layers = raw["layers"]
            if not isinstance(raw_layers, dict):
                raise TypeError("'layers' must be an object")
            layers: dict[str, list[Variant]] = {}
            for layer, variants in raw_layers.items():
                if not isinstance(layer, str) or not isinstance(variants, list):
                    raise TypeError("layers must map names to arrays of variants")
                parsed: list[Variant] = []
                for item in variants:
                    if not isinstance(item, dict):
                        raise TypeError(f"layer {layer!r} variants must be objects")
                    name = item["name"]
                    traffic = item["traffic"]
                    parameters = item["parameters"]
                    if not isinstance(name, str):
                        raise TypeError(f"layer {layer!r} variant name must be a string")
                    if (
                        isinstance(traffic, bool)
                        or not isinstance(traffic, int)
                    ):
                        raise TypeError(
                            f"experiment {layer}/{name} traffic must be an integer"
                        )
                    if not isinstance(parameters, dict):
                        raise TypeError(
                            f"experiment {layer}/{name} parameters must be an object"
                        )
                    parsed.append(Variant(name.strip(), traffic, dict(parameters)))
                layers[layer] = parsed
        except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
            raise ValueError(
                f"invalid experiment config {config_path}: {error}"
            ) from error
        return cls(layers)

    @staticmethod
    def _bucket(layer: str, token: str) -> int:
        digest = hashlib.sha256(f"{layer}:{token}".encode("utf-8")).hexdigest()
        return int(digest[:8], 16) % 100

    def assign(self, layer: str, token: str) -> Variant:
        if layer not in self.layers:
            raise ValueError(f"unknown experiment layer: {layer}")
        if not token:
            raise ValueError("experiment token must not be empty")
        variants = self.layers[layer]
        bucket = self._bucket(layer, token)
        cursor = 0
        for variant in variants:
            cursor += variant.traffic
            if bucket < cursor:
                return variant
        return variants[-1]

    def assignments(self, token: str) -> dict[str, Variant]:
        return {layer: self.assign(layer, token) for layer in self.layers}
