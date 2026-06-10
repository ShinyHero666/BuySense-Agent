from __future__ import annotations

from typing import Protocol

from .models import Candidate


class ModelReranker(Protocol):
    def rerank(self, candidates: list[Candidate]) -> list[Candidate]: ...


class HeuristicModelReranker:
    """Small stand-in for a learned listwise model."""

    def rerank(self, candidates: list[Candidate]) -> list[Candidate]:
        for candidate in candidates:
            candidate.scores["model_rerank"] = (
                candidate.scores["rank"]
                + 0.04 * candidate.features["novelty"]
                + 0.02 * candidate.features["freshness"]
            )
        return sorted(
            candidates,
            key=lambda candidate: candidate.scores["model_rerank"],
            reverse=True,
        )


class FailingModelReranker:
    """Deterministic failure used to teach timeout degradation."""

    def rerank(self, candidates: list[Candidate]) -> list[Candidate]:
        raise TimeoutError("simulated model rerank timeout")

