from __future__ import annotations

import time
from collections import Counter
from dataclasses import asdict, dataclass, field
from typing import Any


@dataclass
class StageRecord:
    name: str
    input_count: int
    output_count: int
    elapsed_ms: float
    metadata: dict[str, Any] = field(default_factory=dict)


class DebugTrace:
    """Request-level trace similar to the debug nodes in the source material."""

    def __init__(self, enabled: bool = True) -> None:
        self.enabled = enabled
        self.records: list[StageRecord] = []

    def measure(
        self,
        name: str,
        input_count: int,
        action,
        output_count=None,
        metadata=None,
    ):
        started = time.perf_counter()
        result = action()
        elapsed_ms = (time.perf_counter() - started) * 1000
        if self.enabled:
            count = output_count(result) if output_count else len(result)
            self.records.append(
                StageRecord(
                    name=name,
                    input_count=input_count,
                    output_count=count,
                    elapsed_ms=round(elapsed_ms, 3),
                    metadata=metadata(result) if callable(metadata) else (metadata or {}),
                )
            )
        return result

    def add(
        self,
        name: str,
        input_count: int,
        output_count: int,
        started: float,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        if not self.enabled:
            return
        self.records.append(
            StageRecord(
                name=name,
                input_count=input_count,
                output_count=output_count,
                elapsed_ms=round((time.perf_counter() - started) * 1000, 3),
                metadata=metadata or {},
            )
        )

    def to_dict(self) -> list[dict[str, Any]]:
        return [asdict(record) for record in self.records]


def count_reason(counter: Counter[str], reason: str) -> None:
    counter[reason] += 1

