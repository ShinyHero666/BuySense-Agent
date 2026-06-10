from __future__ import annotations

from collections import Counter
import unittest

from shoprec.rankers import FailingModelReranker
from shoprec.runtime import FixedClock, SequenceIdGenerator
from shoprec.service import create_demo_service
from tests.support import REFERENCE_TIME, fixed_service, token_for


SEARCH_STAGES = [
    "QUERY",
    "RECALL",
    "FILTER",
    "FEATURE",
    "ROUGHRANK",
    "RANK",
    "RERANK",
    "RESULT",
]

RECOMMEND_STAGES = [
    "QUERY",
    "RECALL",
    "FILTER",
    "FEATURE",
    "ROUGHRANK",
    "FLOWPOOL",
    "RANK",
    "MODEL_RERANK",
    "RULE_RERANK",
    "RESULT",
]


class PipelineContractTest(unittest.TestCase):
    def test_search_stage_contract(self) -> None:
        service, _ = fixed_service()
        result = service.search({"query": "手机", "user_id": "u001"})
        self.assertEqual([stage["name"] for stage in result["trace"]], SEARCH_STAGES)

    def test_recommend_stage_contract(self) -> None:
        service, _ = fixed_service()
        result = service.recommend(
            {"user_id": "u001", "size": 8, "exclude_seen": False}
        )
        self.assertEqual(
            [stage["name"] for stage in result["trace"]], RECOMMEND_STAGES
        )

    def test_flowpool_variants_change_the_slate(self) -> None:
        results = {}
        for variant in ("control", "explore_more"):
            service, _ = fixed_service()
            results[variant] = service.recommend(
                {
                    "user_id": "u001",
                    "size": 8,
                    "exclude_seen": False,
                    "experiment_token": token_for(
                        "recommend_flowpool", variant
                    ),
                }
            )
        control_ids = [item["product_id"] for item in results["control"]["items"]]
        explore_ids = [
            item["product_id"] for item in results["explore_more"]["items"]
        ]
        self.assertNotEqual(control_ids, explore_ids)
        self.assertNotEqual(
            results["control"]["pool_targets"],
            results["explore_more"]["pool_targets"],
        )

    def test_filter_count_is_conserved(self) -> None:
        service, _ = fixed_service()
        result = service.search(
            {
                "query": "iphone",
                "filters": {"max_price": 3000},
            }
        )
        stage = next(item for item in result["trace"] if item["name"] == "FILTER")
        self.assertEqual(
            stage["input_count"],
            stage["output_count"] + sum(result["filter_reasons"].values()),
        )

    def test_rerank_explanation_is_present(self) -> None:
        service, _ = fixed_service()
        result = service.search({"query": "手机", "user_id": "u001"})
        self.assertTrue(all("rerank" in item for item in result["items"]))
        self.assertTrue(
            any(
                item["rerank"]["before_position"]
                != item["rerank"]["after_position"]
                for item in result["items"]
            )
        )

    def test_fixed_runtime_produces_repeatable_ids_and_scores(self) -> None:
        first, _ = fixed_service()
        second, _ = fixed_service()
        left = first.search({"query": "iphone", "user_id": "u001"})
        right = second.search({"query": "iphone", "user_id": "u001"})
        self.assertEqual(left["request_id"], "search-000001")
        self.assertEqual(left["request_id"], right["request_id"])
        self.assertEqual(left["items"], right["items"])

    def test_exposure_ttl_expires(self) -> None:
        service, clock = fixed_service()
        first = service.recommend({"user_id": "u001", "size": 5})
        second = service.recommend({"user_id": "u001", "size": 5})
        clock.advance(hours=25)
        third = service.recommend({"user_id": "u001", "size": 5})
        first_ids = [item["product_id"] for item in first["items"]]
        second_ids = [item["product_id"] for item in second["items"]]
        third_ids = [item["product_id"] for item in third["items"]]
        self.assertTrue(set(first_ids).isdisjoint(second_ids))
        self.assertEqual(first_ids, third_ids)

    def test_model_rerank_timeout_degrades_to_rank(self) -> None:
        service = create_demo_service(
            clock=FixedClock(REFERENCE_TIME),
            id_generator=SequenceIdGenerator(),
            model_reranker=FailingModelReranker(),
        )
        result = service.recommend(
            {"user_id": "u001", "size": 5, "exclude_seen": False}
        )
        stage = next(
            item for item in result["trace"] if item["name"] == "MODEL_RERANK"
        )
        self.assertTrue(stage["metadata"]["degraded"])
        self.assertEqual(stage["metadata"]["error"], "TimeoutError")
        self.assertEqual(len(result["items"]), 5)

    def test_recommendation_seller_cap_is_a_hard_limit(self) -> None:
        service, _ = fixed_service()
        result = service.recommend(
            {
                "user_id": "guest",
                "size": 20,
                "exclude_seen": False,
            }
        )
        seller_counts = Counter(item["seller_id"] for item in result["items"])
        self.assertTrue(seller_counts)
        self.assertLessEqual(max(seller_counts.values()), 1)
        rule_stage = next(
            stage for stage in result["trace"] if stage["name"] == "RULE_RERANK"
        )
        self.assertEqual(rule_stage["metadata"]["max_per_seller"], 1)
