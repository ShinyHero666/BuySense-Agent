from __future__ import annotations

import math
import time
from collections import Counter

from .experiments import ExperimentManager
from .models import (
    Candidate,
    Product,
    RecommendRequest,
    RecommendResponse,
    UserProfile,
)
from .observability import DebugTrace, count_reason
from .ranking import (
    diversity_rerank,
    recommendation_features,
    weighted_score,
)
from .rankers import HeuristicModelReranker, ModelReranker
from .runtime import Clock, IdGenerator, SystemClock, UUIDIdGenerator
from .state import InMemoryExposureStore


class RecommendationEngine:
    def __init__(
        self,
        products: list[Product],
        experiments: ExperimentManager,
        exposures: InMemoryExposureStore,
        clock: Clock | None = None,
        id_generator: IdGenerator | None = None,
        model_reranker: ModelReranker | None = None,
    ) -> None:
        self.products = products
        self.product_by_id = {product.product_id: product for product in products}
        self.experiments = experiments
        self.exposures = exposures
        self.clock = clock or SystemClock()
        self.id_generator = id_generator or UUIDIdGenerator()
        self.model_reranker = model_reranker or HeuristicModelReranker()

    @staticmethod
    def _merge(groups: list[tuple[str, list[Product]]]) -> list[Candidate]:
        merged: dict[str, Candidate] = {}
        for source, products in groups:
            for product in products:
                candidate = merged.setdefault(product.product_id, Candidate(product))
                candidate.recall_sources.add(source)
        return list(merged.values())

    def _recall(self, user: UserProfile) -> list[Candidate]:
        top_categories = {
            category
            for category, _ in sorted(
                user.category_interests.items(), key=lambda item: item[1], reverse=True
            )[:3]
        }
        interest = [
            product for product in self.products if product.category in top_categories
        ]

        clicked = [
            self.product_by_id[product_id]
            for product_id in user.recent_clicks
            if product_id in self.product_by_id
        ]
        clicked_categories = {product.category for product in clicked}
        clicked_brands = {product.brand for product in clicked}
        item_cf = [
            product
            for product in self.products
            if product.category in clicked_categories or product.brand in clicked_brands
        ]
        hot = sorted(
            self.products,
            key=lambda product: product.historical_ctr
            * product.historical_cvr
            * product.quality_score,
            reverse=True,
        )[:10]
        fresh = sorted(
            self.products, key=lambda product: product.publish_time, reverse=True
        )[:8]
        return self._merge(
            [
                ("interest", interest),
                ("item_cf", item_cf),
                ("hot", hot),
                ("fresh", fresh),
            ]
        )

    def _filter(
        self,
        candidates: list[Candidate],
        request: RecommendRequest,
        user: UserProfile,
    ) -> tuple[list[Candidate], Counter[str]]:
        result: list[Candidate] = []
        reasons: Counter[str] = Counter()
        seen = self.exposures.seen_product_ids(user.user_id, request.scene)
        for candidate in candidates:
            product = candidate.product
            reason = None
            if product.stock <= 0:
                reason = "out_of_stock"
            elif product.inspection_status != "passed":
                reason = "inspection_not_passed"
            elif product.product_id in user.purchased:
                reason = "purchased"
            elif product.product_id in user.disliked_products:
                reason = "negative_feedback"
            elif request.exclude_seen and product.product_id in seen:
                reason = "already_exposed"
            if reason:
                count_reason(reasons, reason)
            else:
                result.append(candidate)
        return result, reasons

    @staticmethod
    def _assign_pool(candidate: Candidate) -> str:
        if candidate.features["interest"] >= 0.55 or candidate.features["item_similarity"] >= 0.55:
            return "interest"
        if candidate.features["freshness"] >= 0.72:
            return "fresh"
        return "explore"

    @staticmethod
    def _assign_flow_pools(
        candidates: list[Candidate],
    ) -> list[Candidate]:
        for candidate in candidates:
            candidate.pool = RecommendationEngine._assign_pool(candidate)
        return candidates

    @staticmethod
    def _quota_targets(size: int, quotas: dict[str, float]) -> dict[str, int]:
        pool_order = ("interest", "explore", "fresh")
        raw = {pool: size * quotas.get(pool, 0.0) for pool in pool_order}
        targets = {pool: math.floor(raw[pool]) for pool in pool_order}
        remaining = size - sum(targets.values())
        remainder_order = sorted(
            pool_order,
            key=lambda pool: (raw[pool] - targets[pool], -pool_order.index(pool)),
            reverse=True,
        )
        for pool in remainder_order[:remaining]:
            targets[pool] += 1
        return targets

    @staticmethod
    def _quota_select(
        candidates: list[Candidate],
        size: int,
        quotas: dict[str, float],
    ) -> tuple[list[Candidate], dict[str, int]]:
        targets = RecommendationEngine._quota_targets(size, quotas)
        by_pool: dict[str, list[Candidate]] = {
            "interest": [],
            "explore": [],
            "fresh": [],
        }
        for candidate in candidates:
            by_pool[candidate.pool or "explore"].append(candidate)

        selected: list[Candidate] = []
        selected_ids: set[str] = set()
        for pool in ("interest", "explore", "fresh"):
            for candidate in by_pool[pool][: targets[pool]]:
                selected.append(candidate)
                selected_ids.add(candidate.product.product_id)

        remaining = sorted(
            candidates,
            key=lambda candidate: candidate.scores["model_rerank"],
            reverse=True,
        )
        for candidate in remaining:
            if len(selected) >= size:
                break
            if candidate.product.product_id not in selected_ids:
                selected.append(candidate)
                selected_ids.add(candidate.product.product_id)
        selected.sort(
            key=lambda candidate: candidate.scores["model_rerank"],
            reverse=True,
        )
        return selected, targets

    def recommend(
        self,
        request: RecommendRequest,
        user: UserProfile,
    ) -> RecommendResponse:
        trace = DebugTrace(request.debug)
        request_id = request.request_id or self.id_generator.new_id("rec")
        token = request.experiment_token or user.user_id or request_id
        variant = self.experiments.assign("recommend_flowpool", token)

        query_started = time.perf_counter()
        trace.add(
            "QUERY",
            1,
            1,
            query_started,
            {
                "scene": request.scene,
                "user_id": user.user_id,
                "interest_categories": len(user.category_interests),
                "recent_clicks": len(user.recent_clicks),
                "variant": variant.name,
            },
        )

        candidates = trace.measure(
            "RECALL",
            len(self.products),
            lambda: self._recall(user),
            metadata=lambda values: dict(
                Counter(source for item in values for source in item.recall_sources)
            ),
        )

        filter_started = time.perf_counter()
        filtered, filter_reasons = self._filter(candidates, request, user)
        trace.add(
            "FILTER",
            len(candidates),
            len(filtered),
            filter_started,
            {"reasons": dict(filter_reasons)},
        )

        feature_started = time.perf_counter()
        now = self.clock.now()
        for candidate in filtered:
            candidate.features = recommendation_features(
                candidate, user, self.product_by_id, now
            )
        trace.add("FEATURE", len(filtered), len(filtered), feature_started)

        rough_started = time.perf_counter()
        for candidate in filtered:
            candidate.scores["rough"] = weighted_score(
                candidate.features,
                {
                    "interest": 0.28,
                    "item_similarity": 0.24,
                    "quality": 0.14,
                    "trust": 0.08,
                    "freshness": 0.12,
                    "pctr": 0.10,
                    "pcvr": 0.04,
                },
            )
        rough = sorted(filtered, key=lambda c: c.scores["rough"], reverse=True)[:100]
        trace.add("ROUGHRANK", len(filtered), len(rough), rough_started, {"limit": 100})

        pool_started = time.perf_counter()
        pooled = self._assign_flow_pools(rough)
        available = dict(Counter(candidate.pool or "unknown" for candidate in pooled))
        trace.add(
            "FLOWPOOL",
            len(rough),
            len(pooled),
            pool_started,
            {
                "variant": variant.name,
                "quotas": variant.parameters,
                "available": available,
                "targets": self._quota_targets(request.size, variant.parameters),
            },
        )

        rank_started = time.perf_counter()
        for candidate in pooled:
            candidate.scores["rank"] = weighted_score(
                candidate.features,
                {
                    "pctr": 0.38,
                    "pcvr": 0.22,
                    "interest": 0.12,
                    "item_similarity": 0.08,
                    "quality": 0.08,
                    "freshness": 0.04,
                    "trust": 0.08,
                },
            )
        ranked = sorted(pooled, key=lambda c: c.scores["rank"], reverse=True)
        trace.add("RANK", len(pooled), len(ranked), rank_started)

        model_rerank_started = time.perf_counter()
        degraded = False
        error_name = None
        try:
            model_reranked = self.model_reranker.rerank(ranked)
        except (TimeoutError, RuntimeError) as error:
            degraded = True
            error_name = type(error).__name__
            for candidate in ranked:
                candidate.scores["model_rerank"] = candidate.scores["rank"]
            model_reranked = list(ranked)
        trace.add(
            "MODEL_RERANK",
            len(ranked),
            len(model_reranked),
            model_rerank_started,
            {
                "formula": "rank + 0.04*novelty + 0.02*freshness",
                "degraded": degraded,
                "error": error_name,
            },
        )

        rule_rerank_started = time.perf_counter()
        quota_selected, pool_targets = self._quota_select(
            model_reranked,
            request.size,
            variant.parameters,
        )
        reranked = diversity_rerank(
            quota_selected,
            score_key="model_rerank",
            limit=request.size,
            max_per_seller=1,
            category_window=3,
        )
        trace.add(
            "RULE_RERANK",
            len(model_reranked),
            len(reranked),
            rule_rerank_started,
            {
                "max_per_seller": 1,
                "category_window": 3,
                "pool_targets": pool_targets,
            },
        )

        result_started = time.perf_counter()
        self.exposures.add_many(
            user.user_id,
            request.scene,
            (candidate.product.product_id for candidate in reranked),
        )
        pool_counts = dict(Counter(candidate.pool or "unknown" for candidate in reranked))
        items = [candidate.explain() for candidate in reranked]
        trace.add(
            "RESULT",
            len(reranked),
            len(items),
            result_started,
            {"pool_counts": pool_counts, "exposure_ttl": "24h"},
        )
        return RecommendResponse(
            request_id=request_id,
            items=items,
            experiments={"recommend_flowpool": variant.name},
            trace=trace.to_dict(),
            filter_reasons=dict(filter_reasons),
            pool_counts=pool_counts,
            pool_targets=pool_targets,
        )
