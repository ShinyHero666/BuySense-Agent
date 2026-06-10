from __future__ import annotations

import math
from collections import Counter
from datetime import datetime, timezone

from .models import Candidate, Product, QueryContext, UserProfile
from .text import QueryAnalyzer


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def sigmoid(value: float) -> float:
    return 1.0 / (1.0 + math.exp(-value))


def freshness_score(product: Product, now: datetime | None = None) -> float:
    now = now or datetime.now(timezone.utc)
    published = product.publish_time
    if published.tzinfo is None:
        published = published.replace(tzinfo=timezone.utc)
    age_days = max(0.0, (now - published).total_seconds() / 86400)
    return math.exp(-age_days / 30.0)


def lexical_score(query: QueryContext, product: Product) -> float:
    title_terms = QueryAnalyzer.terms(QueryAnalyzer.normalize(product.title))
    tag_terms = set(product.tags)
    if not query.terms:
        return 0.0
    overlap = len(query.terms & (title_terms | tag_terms)) / len(query.terms)
    exact_bonus = 0.25 if query.rewritten_query in QueryAnalyzer.normalize(product.title) else 0.0
    return clamp(overlap + exact_bonus)


def trust_score(product: Product) -> float:
    grade = {"A": 1.0, "B": 0.9, "C": 0.72, "D": 0.45}.get(
        product.inspection_grade, 0.5
    )
    inspection = 1.0 if product.inspection_status == "passed" else 0.0
    warranty = min(1.0, product.warranty_days / 365.0)
    returns = min(1.0, product.return_window_days / 7.0)
    finding_penalty = min(0.3, len(product.inspection_findings) * 0.06)
    return clamp(
        0.45 * inspection
        + 0.30 * grade
        + 0.15 * warranty
        + 0.10 * returns
        - finding_penalty
    )


def search_features(
    candidate: Candidate,
    query: QueryContext,
    user: UserProfile,
    now: datetime | None = None,
) -> dict[str, float]:
    product = candidate.product
    intent = max(
        float(product.category in query.category_intents),
        float(product.brand in query.brand_intents),
    )
    user_interest = user.category_interests.get(product.category, 0.0)
    source_strength = min(1.0, len(candidate.recall_sources) / 3.0)
    pctr = clamp(
        sigmoid(
            -3.2
            + 1.4 * lexical_score(query, product)
            + 0.8 * intent
            + 0.7 * user_interest
            + 0.6 * product.quality_score
        )
    )
    pcvr = clamp(
        sigmoid(
            -4.0
            + 0.8 * intent
            + 0.7 * product.quality_score
            + 0.4 * product.historical_cvr * 10
        )
    )
    return {
        "lexical": lexical_score(query, product),
        "intent": intent,
        "quality": product.quality_score,
        "trust": trust_score(product),
        "freshness": freshness_score(product, now),
        "user_interest": user_interest,
        "source_strength": source_strength,
        "pctr": pctr,
        "pcvr": pcvr,
    }


def recommendation_features(
    candidate: Candidate,
    user: UserProfile,
    product_by_id: dict[str, Product],
    now: datetime | None = None,
) -> dict[str, float]:
    product = candidate.product
    interest = user.category_interests.get(product.category, 0.0)
    similarities: list[float] = []
    for clicked_id in user.recent_clicks[-10:]:
        clicked = product_by_id.get(clicked_id)
        if not clicked:
            continue
        similarity = 0.0
        similarity += 0.55 if clicked.category == product.category else 0.0
        similarity += 0.25 if clicked.brand == product.brand else 0.0
        if clicked.tags or product.tags:
            union = set(clicked.tags) | set(product.tags)
            similarity += 0.20 * len(set(clicked.tags) & set(product.tags)) / len(union)
        similarities.append(similarity)
    item_similarity = max(similarities, default=0.0)
    fresh = freshness_score(product, now)
    novelty = 1.0 if product.category not in user.category_interests else 0.25
    pctr = clamp(
        sigmoid(
            -3.0
            + 1.3 * interest
            + 1.1 * item_similarity
            + 0.7 * product.quality_score
            + 0.3 * fresh
        )
    )
    pcvr = clamp(
        sigmoid(
            -4.1
            + 0.9 * interest
            + 0.5 * item_similarity
            + 0.8 * product.quality_score
        )
    )
    return {
        "interest": interest,
        "item_similarity": item_similarity,
        "quality": product.quality_score,
        "trust": trust_score(product),
        "freshness": fresh,
        "novelty": novelty,
        "pctr": pctr,
        "pcvr": pcvr,
    }


def weighted_score(features: dict[str, float], weights: dict[str, float]) -> float:
    return sum(features.get(name, 0.0) * weight for name, weight in weights.items())


def diversity_rerank(
    candidates: list[Candidate],
    score_key: str,
    limit: int,
    max_per_seller: int = 1,
    category_window: int = 4,
) -> list[Candidate]:
    """Greedy rule rerank: preserve relevance while limiting repetition."""

    remaining = list(candidates)
    selected: list[Candidate] = []
    seller_counts: Counter[str] = Counter()
    for position, candidate in enumerate(remaining, start=1):
        candidate.before_position = position
        candidate.after_position = None
        candidate.rerank_reasons = []

    while remaining and len(selected) < limit:
        recent_categories = [
            item.product.category for item in selected[-category_window:]
        ]

        def adjusted(candidate: Candidate) -> float:
            penalty = 0.0
            if candidate.product.category in recent_categories:
                penalty += 0.12
            penalty += 0.08 * seller_counts[candidate.product.seller_id]
            return candidate.scores[score_key] - penalty

        remaining.sort(key=adjusted, reverse=True)
        chosen_index = next(
            (
                index
                for index, candidate in enumerate(remaining)
                if seller_counts[candidate.product.seller_id] < max_per_seller
            ),
            None,
        )
        if chosen_index is None:
            break
        chosen = remaining.pop(chosen_index)
        if chosen.product.category in recent_categories:
            chosen.rerank_reasons.append("CATEGORY_REPEAT_PENALTY")
        if seller_counts[chosen.product.seller_id] > 0:
            chosen.rerank_reasons.append("SELLER_REPEAT_PENALTY")
        selected.append(chosen)
        seller_counts[chosen.product.seller_id] += 1

    for position, candidate in enumerate(selected, start=1):
        candidate.after_position = position
        if candidate.before_position != candidate.after_position:
            candidate.rerank_reasons.append("POSITION_CHANGED")
    return selected
