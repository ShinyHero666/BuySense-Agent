from __future__ import annotations

import threading
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Protocol


class Clock(Protocol):
    def now(self) -> datetime: ...


class SystemClock:
    def now(self) -> datetime:
        return datetime.now(timezone.utc)


@dataclass
class FixedClock:
    current: datetime

    def now(self) -> datetime:
        value = self.current
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value

    def advance(self, **delta) -> None:
        self.current = self.now() + timedelta(**delta)


class IdGenerator(Protocol):
    def new_id(self, prefix: str) -> str: ...


class UUIDIdGenerator:
    def new_id(self, prefix: str) -> str:
        return f"{prefix}-{uuid.uuid4().hex[:12]}"


class SequenceIdGenerator:
    def __init__(self) -> None:
        self._value = 0
        self._lock = threading.Lock()

    def new_id(self, prefix: str) -> str:
        with self._lock:
            self._value += 1
            return f"{prefix}-{self._value:06d}"

