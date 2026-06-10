from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from shoprec.scenarios import run_scenario
from tests.support import fixed_service


SCENARIO_ROOT = Path(__file__).resolve().parents[1] / "scenarios"


class ScenarioTest(unittest.TestCase):
    def test_all_builtin_scenarios_pass(self) -> None:
        for path in sorted(SCENARIO_ROOT.glob("*.json")):
            with self.subTest(path=path.name):
                service, _ = fixed_service()
                result = run_scenario(path, service)
                self.assertEqual(result["status"], "PASS")

    def test_unknown_expectation_field_is_rejected(self) -> None:
        raw = {
            "name": "typo-check",
            "steps": [
                {
                    "action": "search",
                    "payload": {"query": "手机"},
                    "expect": {"top_itme": "p001"},
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "scenario.json"
            path.write_text(json.dumps(raw), encoding="utf-8")
            service, _ = fixed_service()
            with self.assertRaisesRegex(ValueError, "unknown fields: top_itme"):
                run_scenario(path, service)

    def test_generic_top_level_field_expectation(self) -> None:
        raw = {
            "name": "valuation-reference",
            "steps": [
                {
                    "action": "valuation",
                    "payload": {
                        "brand": "Apple",
                        "model": "iPhone 13",
                    },
                    "expect": {
                        "fields_equal": {
                            "reference_source": "model_reference"
                        }
                    },
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "scenario.json"
            path.write_text(json.dumps(raw), encoding="utf-8")
            service, _ = fixed_service()
            result = run_scenario(path, service)
            self.assertEqual(result["status"], "PASS")
