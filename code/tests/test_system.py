from __future__ import annotations

import unittest

from shoprec.experiments import ExperimentManager
from shoprec.text import QueryAnalyzer
from tests.support import fixed_service


class QueryAnalyzerTest(unittest.TestCase):
    def test_rewrite_and_intent(self) -> None:
        query = QueryAnalyzer().analyze("  苹果手机  ")
        self.assertEqual(query.rewritten_query, "iphone")
        self.assertIn("手机", query.category_intents)
        self.assertIn("Apple", query.brand_intents)
        self.assertFalse(query.is_broad)

    def test_broad_query(self) -> None:
        query = QueryAnalyzer().analyze("手机")
        self.assertTrue(query.is_broad)
        self.assertEqual(query.category_intents, ["手机"])

    def test_chinese_terms_do_not_create_single_character_false_matches(self) -> None:
        self.assertEqual(QueryAnalyzer.terms("手机"), {"手机"})

    def test_risk_query(self) -> None:
        query = QueryAnalyzer().analyze("出售假证")
        self.assertTrue(query.blocked)


class ExperimentTest(unittest.TestCase):
    def test_assignment_is_stable(self) -> None:
        manager = ExperimentManager.default()
        first = manager.assign("search_rank", "same-user").name
        second = manager.assign("search_rank", "same-user").name
        self.assertEqual(first, second)


class SearchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.service, _ = fixed_service()

    def test_search_filters_stock_and_price(self) -> None:
        response = self.service.search(
            {
                "query": "iphone",
                "user_id": "u001",
                "filters": {"max_price": 3000},
            }
        )
        ids = {item["product_id"] for item in response["items"]}
        self.assertIn("p002", ids)
        self.assertNotIn("p001", ids)
        self.assertNotIn("p016", ids)
        self.assertGreaterEqual(response["filter_reasons"].get("out_of_stock", 0), 1)

    def test_search_has_all_pipeline_stages(self) -> None:
        response = self.service.search({"query": "手机", "user_id": "u001"})
        stages = [record["name"] for record in response["trace"]]
        self.assertEqual(
            stages,
            [
                "QUERY",
                "RECALL",
                "FILTER",
                "FEATURE",
                "ROUGHRANK",
                "RANK",
                "RERANK",
                "RESULT",
            ],
        )
        self.assertIn("category", response["facets"])

    def test_broad_explore_uses_related_categories(self) -> None:
        response = self.service.search({"query": "手机", "user_id": "u001"})
        explored = [
            item
            for item in response["items"]
            if "broad_explore" in item["recall_sources"]
        ]
        self.assertTrue(explored)
        self.assertTrue(
            all(item["category"] in {"数码配件", "电脑"} for item in explored)
        )


class RecommendationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.service, _ = fixed_service()

    def test_recommendation_filters_purchased_and_negative_feedback(self) -> None:
        response = self.service.recommend(
            {"user_id": "u001", "size": 8, "exclude_seen": False}
        )
        ids = {item["product_id"] for item in response["items"]}
        self.assertNotIn("p002", ids)
        self.assertNotIn("p011", ids)
        self.assertGreaterEqual(response["filter_reasons"].get("purchased", 0), 1)

    def test_exposure_changes_next_page(self) -> None:
        first = self.service.recommend({"user_id": "u001", "size": 5})
        second = self.service.recommend({"user_id": "u001", "size": 5})
        first_ids = {item["product_id"] for item in first["items"]}
        second_ids = {item["product_id"] for item in second["items"]}
        self.assertTrue(first_ids.isdisjoint(second_ids))
        self.assertGreater(second["filter_reasons"].get("already_exposed", 0), 0)


if __name__ == "__main__":
    unittest.main()
