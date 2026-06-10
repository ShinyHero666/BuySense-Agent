from __future__ import annotations

import json
import math
import tempfile
import unittest
from pathlib import Path

from shoprec.experiments import ExperimentManager, Variant
from shoprec.runtime import FixedClock, SequenceIdGenerator
from shoprec.service import create_demo_service
from tests.support import REFERENCE_TIME


CONFIG_PATH = Path(__file__).resolve().parents[1] / "config" / "experiments.json"
PACKAGE_CONFIG_PATH = (
    Path(__file__).resolve().parents[1] / "src" / "shoprec" / "default_experiments.json"
)


class ExperimentConfigTest(unittest.TestCase):
    def test_json_config_loads_and_matches_packaged_fallback(self) -> None:
        external = ExperimentManager.from_json(CONFIG_PATH)
        packaged = ExperimentManager.from_json(PACKAGE_CONFIG_PATH)
        self.assertEqual(external.layers, packaged.layers)

    def test_runtime_uses_supplied_config(self) -> None:
        raw = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        raw["layers"]["search_rank"][0]["name"] = "renamed_control"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "experiments.json"
            path.write_text(json.dumps(raw), encoding="utf-8")
            service = create_demo_service(
                path,
                clock=FixedClock(REFERENCE_TIME),
                id_generator=SequenceIdGenerator(),
            )
            names = {
                variant.name
                for variant in service.experiments.layers["search_rank"]
            }
            self.assertIn("renamed_control", names)

    def test_invalid_traffic_is_rejected(self) -> None:
        layers = ExperimentManager.default().layers.copy()
        layers["search_rank"] = [
            Variant("a", 60, {"score": 1.0}),
            Variant("b", 60, {"score": 1.0}),
        ]
        with self.assertRaisesRegex(ValueError, "traffic must total 100"):
            ExperimentManager(layers)

    def test_invalid_parameter_sum_is_rejected(self) -> None:
        layers = ExperimentManager.default().layers.copy()
        layers["recommend_flowpool"] = [
            Variant("a", 100, {"interest": 0.5, "explore": 0.2, "fresh": 0.1})
        ]
        with self.assertRaisesRegex(ValueError, "parameters must total 1.0"):
            ExperimentManager(layers)

    def test_non_finite_and_boolean_parameters_are_rejected(self) -> None:
        for value in (math.nan, math.inf, True):
            with self.subTest(value=value):
                layers = ExperimentManager.default().layers.copy()
                parameters = dict(
                    layers["recommend_flowpool"][0].parameters
                )
                parameters["interest"] = value
                layers["recommend_flowpool"] = [
                    Variant("a", 100, parameters)
                ]
                with self.assertRaisesRegex(ValueError, "finite numbers"):
                    ExperimentManager(layers)

    def test_fractional_json_traffic_is_not_truncated(self) -> None:
        raw = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        raw["layers"]["search_rank"][0]["traffic"] = 50.5
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "experiments.json"
            path.write_text(json.dumps(raw), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "traffic must be an integer"):
                ExperimentManager.from_json(path)

    def test_variants_in_a_layer_use_the_same_parameter_contract(self) -> None:
        layers = ExperimentManager.default().layers.copy()
        layers["search_rank"] = [
            Variant("a", 50, {"lexical": 1.0}),
            Variant("b", 50, {"freshness": 1.0}),
        ]
        with self.assertRaisesRegex(ValueError, "same parameter names"):
            ExperimentManager(layers)

    def test_known_layer_rejects_misspelled_parameter_names(self) -> None:
        layers = ExperimentManager.default().layers.copy()
        parameters = dict(layers["search_rank"][0].parameters)
        parameters["lexcal"] = parameters.pop("lexical")
        layers["search_rank"] = [
            Variant("a", 50, parameters),
            Variant("b", 50, dict(parameters)),
        ]
        with self.assertRaisesRegex(ValueError, "parameter contract mismatch"):
            ExperimentManager(layers)
