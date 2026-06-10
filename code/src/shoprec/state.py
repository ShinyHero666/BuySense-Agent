from __future__ import annotations

import copy
import threading
from datetime import datetime, timedelta

from .models import UserProfile
from .runtime import Clock, SystemClock


class InMemoryExposureStore:
    """Thread-safe TTL exposure state keyed by user, scene and product."""

    def __init__(
        self,
        clock: Clock | None = None,
        ttl: timedelta = timedelta(hours=24),
    ) -> None:
        self.clock = clock or SystemClock()
        self.ttl = ttl
        self._expires_at: dict[tuple[str, str, str], datetime] = {}
        self._lock = threading.RLock()

    def _purge_expired(self) -> None:
        now = self.clock.now()
        expired = [
            key for key, expires_at in self._expires_at.items() if expires_at <= now
        ]
        for key in expired:
            del self._expires_at[key]

    def seen_product_ids(self, user_id: str, scene: str) -> set[str]:
        with self._lock:
            self._purge_expired()
            return {
                product_id
                for (stored_user, stored_scene, product_id) in self._expires_at
                if stored_user == user_id and stored_scene == scene
            }

    def add_many(self, user_id: str, scene: str, product_ids) -> None:
        expires_at = self.clock.now() + self.ttl
        with self._lock:
            for product_id in product_ids:
                self._expires_at[(user_id, scene, product_id)] = expires_at

    def add(self, user_id: str, scene: str, product_id: str) -> None:
        self.add_many(user_id, scene, [product_id])


class InMemoryUserStore:
    """Returns snapshots so request threads never share mutable profiles."""

    def __init__(self, users: dict[str, UserProfile]) -> None:
        self._users = copy.deepcopy(users)
        self._lock = threading.RLock()

    def get(self, user_id: str) -> UserProfile:
        with self._lock:
            user = self._users.get(user_id)
            if user is None:
                return UserProfile(user_id=user_id, city="", category_interests={})
            return copy.deepcopy(user)

    def record_event(self, user_id: str, product_id: str, event_type: str) -> None:
        with self._lock:
            user = self._users.setdefault(
                user_id,
                UserProfile(user_id=user_id, city="", category_interests={}),
            )
            if event_type == "click":
                user.recent_clicks.append(product_id)
                user.recent_clicks = user.recent_clicks[-100:]
            elif event_type == "purchase":
                user.purchased.add(product_id)
            elif event_type == "dislike":
                user.disliked_products.add(product_id)
            else:
                raise ValueError(f"unsupported profile event_type: {event_type}")
