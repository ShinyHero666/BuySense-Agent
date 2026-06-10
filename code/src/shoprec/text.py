from __future__ import annotations

import re
import unicodedata

from .models import QueryContext


_PUNCTUATION = re.compile(r"[^\w\u4e00-\u9fff]+", re.UNICODE)
_ASCII_WORD = re.compile(r"[a-z0-9]+")
_CJK_RUN = re.compile(r"[\u4e00-\u9fff]+")


class QueryAnalyzer:
    """Teaching implementation of normalize -> rewrite -> intent -> broad query."""

    def __init__(self) -> None:
        self.synonyms = {
            "苹果手机": "iphone",
            "苹果 手机": "iphone",
            "iphone手机": "iphone",
            "笔记本电脑": "笔记本",
            "手提电脑": "笔记本",
            "耳麦": "耳机",
            "单反相机": "相机",
        }
        self.corrections = {
            "iphnoe": "iphone",
            "ipone": "iphone",
            "华位": "华为",
            "照像机": "相机",
        }
        self.category_lexicon = {
            "手机": "手机",
            "iphone": "手机",
            "华为": "手机",
            "小米": "手机",
            "笔记本": "电脑",
            "电脑": "电脑",
            "macbook": "电脑",
            "耳机": "数码配件",
            "相机": "摄影",
            "镜头": "摄影",
            "包": "箱包",
            "背包": "箱包",
            "自行车": "运动户外",
            "球鞋": "运动户外",
            "书": "图书",
        }
        self.brand_lexicon = {
            "iphone": "Apple",
            "苹果": "Apple",
            "apple": "Apple",
            "华为": "Huawei",
            "huawei": "Huawei",
            "小米": "Xiaomi",
            "xiaomi": "Xiaomi",
            "macbook": "Apple",
            "thinkpad": "Lenovo",
            "索尼": "Sony",
            "sony": "Sony",
            "佳能": "Canon",
            "canon": "Canon",
            "耐克": "Nike",
            "nike": "Nike",
        }
        self.broad_words = {"手机", "电脑", "笔记本", "耳机", "相机", "包", "书", "自行车", "球鞋"}
        self.risk_words = {"违禁品", "假证", "枪支"}

    @staticmethod
    def normalize(value: str) -> str:
        value = unicodedata.normalize("NFKC", value).strip().lower()
        value = _PUNCTUATION.sub(" ", value)
        return " ".join(value.split())

    @staticmethod
    def terms(value: str) -> set[str]:
        result: set[str] = set(_ASCII_WORD.findall(value))
        for run in _CJK_RUN.findall(value):
            result.add(run)
            if len(run) > 1:
                result.update(run[i : i + 2] for i in range(len(run) - 1))
        return {term for term in result if term}

    def analyze(self, query: str) -> QueryContext:
        normalized = self.normalize(query)
        rewritten = self.corrections.get(normalized, normalized)
        rewritten = self.synonyms.get(rewritten, rewritten)
        reasons: list[str] = []

        if rewritten != normalized:
            reasons.append(f"query_rewrite:{normalized}->{rewritten}")

        blocked_terms = [word for word in self.risk_words if word in rewritten]
        blocked = bool(blocked_terms)
        if blocked:
            reasons.append("risk_word:" + ",".join(blocked_terms))

        categories = []
        brands = []
        for word, category in self.category_lexicon.items():
            if word in rewritten and category not in categories:
                categories.append(category)
        for word, brand in self.brand_lexicon.items():
            if word in rewritten and brand not in brands:
                brands.append(brand)

        # A brand/model/price token is a strong precise signal. Otherwise a
        # dictionary-confirmed category word is treated as a broad query.
        has_precise_signal = bool(brands) or bool(re.search(r"\d", rewritten))
        is_broad = rewritten in self.broad_words and not has_precise_signal
        reasons.append("broad_query" if is_broad else "precise_query")

        return QueryContext(
            raw_query=query,
            normalized_query=normalized,
            rewritten_query=rewritten,
            terms=self.terms(rewritten),
            category_intents=categories,
            brand_intents=brands,
            is_broad=is_broad,
            blocked=blocked,
            reasons=reasons,
        )
