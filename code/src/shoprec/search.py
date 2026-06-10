from __future__ import annotations

import time
from collections import Counter

from .experiments import ExperimentManager
from .models import (
    Candidate,
    Product,
    SearchRequest,
    SearchResponse,
    UserProfile,
)
from .observability import DebugTrace, count_reason
from .ranking import diversity_rerank, search_features, weighted_score
from .runtime import Clock, IdGenerator, SystemClock, UUIDIdGenerator
from .text import QueryAnalyzer


class SearchEngine:
    def __init__(
        self,
        products: list[Product],
        experiments: ExperimentManager,
        analyzer: QueryAnalyzer | None = None,
        clock: Clock | None = None,
        id_generator: IdGenerator | None = None,
    ) -> None:
        self.products = products
        self.experiments = experiments
        self.analyzer = analyzer or QueryAnalyzer()
        self.clock = clock or SystemClock()
        self.id_generator = id_generator or UUIDIdGenerator()

    @staticmethod
    def _merge(
        groups: list[tuple[str, list[Product]]],
    ) -> list[Candidate]:
        merged: dict[str, Candidate] = {}
        for source, products in groups:
            for product in products:
                candidate = merged.setdefault(product.product_id, Candidate(product))
                candidate.recall_sources.add(source)
        return list(merged.values())

    def _recall(self, query) -> list[Candidate]:
        lexical = [
            product
            for product in self.products
            if query.terms
            & QueryAnalyzer.terms(QueryAnalyzer.normalize(product.title + " " + " ".join(product.tags)))
        ]
        category = [
            product
            for product in self.products
            if product.category in query.category_intents
        ]
        brand = [
            product for product in self.products if product.brand in query.brand_intents
        ]
        intent_product_ids = {
            product.product_id for product in lexical + category + brand
        }
        related_categories = {
            "手机": {"数码配件", "电脑"},
            "电脑": {"数码配件", "图书"},
            "摄影": {"数码配件", "电脑"},
            "箱包": {"电脑", "运动户外"},
            "运动户外": {"箱包"},
            "图书": {"电脑"},
        }
        explore_categories = {
            related
            for category_name in query.category_intents
            for related in related_categories.get(category_name, set())
        }
        broad_explore = (
            [
                product
                for product in sorted(
                    self.products,
                    key=lambda product: (
                        product.historical_ctr * product.quality_score,
                        product.publish_time,
                    ),
                    reverse=True,
                )
                if product.product_id not in intent_product_ids
                and product.stock > 0
                and (
                    not explore_categories
                    or product.category in explore_categories
                )
            ][:4]
            if query.is_broad
            else []
        )
        hot = sorted(
            self.products,
            key=lambda product: product.historical_ctr * product.quality_score,
            reverse=True,
        )[:8]
        return self._merge(
            [
                ("lexical", lexical),
                ("category_intent", category),
                ("brand_intent", brand),
                ("broad_explore", broad_explore),
                (
                    "hot_fallback",
                    hot if not lexical and not category and not brand else [],
                ),
            ]
        )

    @staticmethod
    def _filter(
        candidates: list[Candidate],
        request: SearchRequest,
    ) -> tuple[list[Candidate], Counter[str]]:
        result: list[Candidate] = []
        reasons: Counter[str] = Counter()
        filters = request.filters
        for candidate in candidates:
            product = candidate.product
            reason = None
            if product.stock <= 0:
                reason = "out_of_stock"
            elif product.inspection_status != "passed":
                reason = "inspection_not_passed"
            elif filters.categories and product.category not in filters.categories:
                reason = "category_mismatch"
            elif filters.brands and product.brand not in filters.brands:
                reason = "brand_mismatch"
            elif filters.city and product.city != filters.city:
                reason = "city_mismatch"
            elif filters.min_price is not None and product.price < filters.min_price:
                reason = "below_min_price"
            elif filters.max_price is not None and product.price > filters.max_price:
                reason = "above_max_price"
            elif filters.conditions and product.condition not in filters.conditions:
                reason = "condition_mismatch"
            elif (
                filters.service_modes
                and product.service_mode not in filters.service_modes
            ):
                reason = "service_mode_mismatch"
            elif (
                filters.inspection_grades
                and product.inspection_grade not in filters.inspection_grades
            ):
                reason = "inspection_grade_mismatch"
            elif (
                filters.min_battery_health is not None
                and (
                    product.battery_health is None
                    or product.battery_health < filters.min_battery_health
                )
            ):
                reason = "battery_health_below_min"
            elif filters.warranty_required and product.warranty_days <= 0:
                reason = "warranty_unavailable"
            if reason:
                count_reason(reasons, reason)
            else:
                result.append(candidate)
        return result, reasons

    @staticmethod
    def _facets(candidates: list[Candidate]) -> dict[str, dict[str, int]]:
        return {
            "category": dict(Counter(c.product.category for c in candidates)),
            "brand": dict(Counter(c.product.brand for c in candidates)),
            "city": dict(Counter(c.product.city for c in candidates)),
            "condition": dict(Counter(c.product.condition for c in candidates)),
        }

    @staticmethod
    def _sort(candidates: list[Candidate], mode: str) -> list[Candidate]:
        keys = {
            "price_asc": (lambda c: c.product.price, False),
            "price_desc": (lambda c: c.product.price, True),
            "newest": (lambda c: c.product.publish_time, True),
            "relevance": (lambda c: c.scores["rank"], True),
        }
        key, reverse = keys.get(mode, keys["relevance"])
        return sorted(candidates, key=key, reverse=reverse)

    def search(self, request: SearchRequest, user: UserProfile) -> SearchResponse:
        trace = DebugTrace(request.debug)
        request_id = request.request_id or self.id_generator.new_id("search")
        token = request.experiment_token or user.user_id or request_id
        variant = self.experiments.assign("search_rank", token)

        query = trace.measure(
            "QUERY",
            1,
            lambda: self.analyzer.analyze(request.query),
            output_count=lambda _: 1,
            metadata=lambda value: {
                "rewritten_query": value.rewritten_query,
                "is_broad": value.is_broad,
                "blocked": value.blocked,
            },
        )
        if query.blocked or not query.rewritten_query:
            result_started = time.perf_counter()
            trace.add(
                "RESULT",
                0,
                0,
                result_started,
                {"reason": "risk_or_empty_query"},
            )
            return SearchResponse(
                request_id,
                0,
                [],
                {},
                query.to_dict(),
                {"search_rank": variant.name},
                trace.to_dict(),
                {"risk_or_empty_query": 1},
            )

        candidates = trace.measure(
            "RECALL",
            len(self.products),
            lambda: self._recall(query),
            metadata=lambda values: dict(
                Counter(source for item in values for source in item.recall_sources)
            ),
        )

        filter_started = time.perf_counter()
        filtered, filter_reasons = self._filter(candidates, request)
        trace.add(
            "FILTER",
            len(candidates),
            len(filtered),
            filter_started,
            {"reasons": dict(filter_reasons)},
        )
        facets = self._facets(filtered)

        feature_started = time.perf_counter()
        now = self.clock.now()
        for candidate in filtered:
            candidate.features = search_features(candidate, query, user, now)
        trace.add("FEATURE", len(filtered), len(filtered), feature_started)

        rough_started = time.perf_counter()
        for candidate in filtered:
            candidate.scores["rough"] = (
                0.45 * candidate.features["lexical"]
                + 0.25 * candidate.features["intent"]
                + 0.15 * candidate.features["source_strength"]
                + 0.10 * candidate.features["quality"]
                + 0.05 * candidate.features["trust"]
            )
        rough = sorted(filtered, key=lambda c: c.scores["rough"], reverse=True)[:100]
        trace.add("ROUGHRANK", len(filtered), len(rough), rough_started, {"limit": 100})

        rank_started = time.perf_counter()
        for candidate in rough:
            candidate.scores["rank"] = weighted_score(
                candidate.features, variant.parameters
            )
        ranked = self._sort(rough, request.sort)
        trace.add(
            "RANK",
            len(rough),
            len(ranked),
            rank_started,
            {"variant": variant.name, "weights": variant.parameters},
        )

        rerank_started = time.perf_counter()
        if request.sort == "relevance" and query.is_broad:
            ranked = diversity_rerank(
                ranked,
                score_key="rank",
                limit=len(ranked),
                max_per_seller=2,
                category_window=4,
            )
        trace.add(
            "RERANK",
            len(rough),
            len(ranked),
            rerank_started,
            {"broad_query_diversity": request.sort == "relevance" and query.is_broad},
        )

        total = len(ranked)
        start = max(0, (request.page - 1) * request.page_size)
        page = ranked[start : start + request.page_size]
        result_started = time.perf_counter()
        items = [candidate.explain() for candidate in page]
        trace.add(
            "RESULT",
            len(ranked),
            len(items),
            result_started,
            {
                "page": request.page,
                "page_size": request.page_size,
                "total": total,
            },
        )
        return SearchResponse(
            request_id=request_id,
            total=total,
            items=items,
            facets=facets,
            query_info=query.to_dict(),
            experiments={"search_rank": variant.name},
            trace=trace.to_dict(),
            filter_reasons=dict(filter_reasons),
        )
