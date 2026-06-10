from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


INJECTION_PATTERNS = (
    "ignore previous",
    "ignore all instructions",
    "system prompt",
    "developer message",
    "忽略之前",
    "忽略所有指令",
    "输出密钥",
    "泄露提示词",
)


@dataclass(frozen=True)
class KnowledgeCard:
    card_id: str
    title: str
    version: str
    keywords: tuple[str, ...]
    content: str

    def citation(self) -> dict[str, Any]:
        return {
            "source_type": "knowledge_card",
            "source_id": self.card_id,
            "title": self.title,
            "version": self.version,
            "content": self.content,
        }


class GroundingStore:
    def __init__(self, cards: tuple[KnowledgeCard, ...]) -> None:
        self.cards = cards

    @classmethod
    def default(cls) -> "GroundingStore":
        path = Path(__file__).resolve().parent / "data" / "knowledge_cards.json"
        payload = json.loads(path.read_text(encoding="utf-8"))
        cards = tuple(
            KnowledgeCard(
                card_id=str(item["card_id"]),
                title=str(item["title"]),
                version=str(item["version"]),
                keywords=tuple(str(value) for value in item["keywords"]),
                content=str(item["content"]),
            )
            for item in payload
        )
        return cls(cards)

    def retrieve(self, query: str, *, limit: int = 3) -> list[dict[str, Any]]:
        normalized = query.casefold()
        ranked: list[tuple[int, str, KnowledgeCard]] = []
        for card in self.cards:
            score = sum(
                1 for keyword in card.keywords if keyword.casefold() in normalized
            )
            if score:
                ranked.append((-score, card.card_id, card))
        ranked.sort()
        return [card.citation() for _, _, card in ranked[:limit]]


def contains_prompt_injection(text: str) -> bool:
    normalized = re.sub(r"\s+", " ", text.casefold())
    return any(pattern in normalized for pattern in INJECTION_PATTERNS)


def product_citations(item: dict[str, Any]) -> list[dict[str, Any]]:
    product_id = str(item.get("product_id", "unknown"))
    service = item.get("service", {}) if isinstance(item.get("service"), dict) else {}
    fields = {
        "price": item.get("price"),
        "model_name": item.get("model_name"),
        "storage_gb": item.get("storage_gb"),
        "condition": item.get("condition"),
        "battery_health": service.get("battery_health"),
        "inspection_grade": service.get("inspection_grade"),
        "inspection_status": service.get("inspection_status"),
        "warranty_days": service.get("warranty_days"),
        "repair_history": service.get("repair_history", []),
    }
    return [
        {
            "source_type": "product",
            "source_id": product_id,
            "field": field,
            "value": value,
            "version": "catalog-v1",
        }
        for field, value in fields.items()
        if value not in (None, "")
    ]
