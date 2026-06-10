from __future__ import annotations

import io
import json
import unittest
from contextlib import redirect_stderr, redirect_stdout

from shoprec.cli import run


class CLILearningExperienceTest(unittest.TestCase):
    @staticmethod
    def invoke(arguments: list[str]) -> tuple[int, str, str]:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            status = run(arguments)
        return status, stdout.getvalue(), stderr.getvalue()

    def test_explain_view_connects_decisions_funnel_and_items(self) -> None:
        status, output, error = self.invoke(
            ["search", "苹果手机", "--size", "3", "--view", "explain"]
        )
        self.assertEqual(status, 0, error)
        self.assertIn("[Query decision]", output)
        self.assertIn("[Pipeline funnel]", output)
        self.assertIn("[Result explanations]", output)
        self.assertIn("query_rewrite:苹果手机->iphone", output)

    def test_search_tour_can_run_one_section(self) -> None:
        status, output, error = self.invoke(
            ["search-tour", "--section", "safety", "--size", "3"]
        )
        self.assertEqual(status, 0, error)
        self.assertIn("Risk-query interception", output)
        self.assertIn("risk_word:假证", output)
        self.assertIn("Tour complete.", output)

    def test_search_cli_exposes_repeatable_filters(self) -> None:
        status, output, error = self.invoke(
            [
                "search",
                "手机",
                "--category",
                "手机",
                "--brand",
                "Apple",
                "--max-price",
                "7000",
                "--view",
                "full",
            ]
        )
        self.assertEqual(status, 0, error)
        result = json.loads(output)
        self.assertTrue(result["items"])
        self.assertTrue(
            all(
                item["category"] == "手机" and item["brand"] == "Apple"
                for item in result["items"]
            )
        )

    def test_catalog_is_filterable(self) -> None:
        status, output, error = self.invoke(
            ["catalog", "--category", "摄影", "--limit", "10"]
        )
        self.assertEqual(status, 0, error)
        self.assertIn("p009", output)
        self.assertIn("p010", output)
        self.assertIn("shown: 2", output)

    def test_secondhand_business_tour_runs(self) -> None:
        status, output, error = self.invoke(["secondhand-tour", "--user", "u001"])
        self.assertEqual(status, 0, error)
        self.assertIn("Moyuan Select inspected phone search", output)
        self.assertIn("Pre-inspection recycling quote", output)
        self.assertIn("Second-hand business tour complete.", output)
