from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


DATA_DIRECTORY = Path(__file__).resolve().parent / "data"
DEFAULT_DOMAIN_PACK_ID = "normal-3c-v1"
REQUIRED_ASSETS = frozenset({"catalog", "reviews", "compatibility"})
VERSIONED_IDENTIFIER = re.compile(r"^[a-z][a-z0-9-]*-v[0-9]+$")
CATEGORY_IDENTIFIER = re.compile(r"^[a-z][a-z0-9_]{0,63}$")


def _string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"domain pack {field} must be a non-empty string")
    return value.strip()


def _bounded_string(value: Any, field: str, maximum: int) -> str:
    parsed = _string(value, field)
    if len(parsed) > maximum:
        raise ValueError(
            f"domain pack {field} must contain at most {maximum} characters"
        )
    return parsed


def _identifier(
    value: Any,
    field: str,
    *,
    pattern: re.Pattern[str] = VERSIONED_IDENTIFIER,
) -> str:
    parsed = _string(value, field)
    if len(parsed) > 64 or pattern.fullmatch(parsed) is None:
        raise ValueError(f"domain pack {field} has an invalid identifier")
    return parsed


def _asset_name(value: Any, field: str) -> str:
    parsed = _string(value, field)
    if Path(parsed).name != parsed or not parsed.endswith(".json"):
        raise ValueError(f"domain pack {field} must be a local JSON filename")
    return parsed


def _string_tuple(value: Any, field: str, *, allow_empty: bool = False) -> tuple[str, ...]:
    if not isinstance(value, list) or not all(
        isinstance(item, str) and item.strip() for item in value
    ):
        raise ValueError(f"domain pack {field} must be an array of non-empty strings")
    if not allow_empty and not value:
        raise ValueError(f"domain pack {field} must not be empty")
    return tuple(item.strip() for item in value)


@dataclass(frozen=True)
class CategoryRequirement:
    connectors_any: tuple[str, ...] = ()
    protocols_any: tuple[str, ...] = ()

    def accepts(self, connectors: Iterable[str], protocols: Iterable[str]) -> bool:
        connector_values = set(connectors)
        protocol_values = set(protocols)
        connector_ok = not self.connectors_any or bool(
            connector_values.intersection(self.connectors_any)
        )
        protocol_ok = not self.protocols_any or bool(
            protocol_values.intersection(self.protocols_any)
        )
        return connector_ok and protocol_ok


@dataclass(frozen=True)
class RetailDomainPack:
    schema_version: str
    pack_id: str
    display_name: str
    description: str
    workflow_id: str
    capability_profile_id: str
    source_path: Path
    assets: dict[str, str]
    default_category: str
    primary_category: str
    default_bundle_categories: tuple[str, ...]
    categories: tuple[dict[str, Any], ...]
    use_cases: tuple[str, ...]
    brands: tuple[dict[str, Any], ...]
    protocol_terms: tuple[str, ...]
    example_queries: tuple[str, ...]
    category_requirements: dict[str, CategoryRequirement]

    @property
    def product_categories(self) -> frozenset[str]:
        return frozenset(str(item["id"]) for item in self.categories)

    @property
    def commerce_terms(self) -> tuple[str, ...]:
        values = [
            str(term).lower()
            for category in self.categories
            for term in category["terms"]
        ]
        values.extend(term.lower() for term in self.use_cases)
        values.extend(
            str(term).lower()
            for brand in self.brands
            for term in brand["terms"]
        )
        values.extend(term.lower() for term in self.protocol_terms)
        return tuple(dict.fromkeys(values))

    def asset_path(self, name: str) -> Path:
        filename = self.assets.get(name)
        if filename is None:
            raise ValueError(f"domain pack {self.pack_id} does not declare asset: {name}")
        path = (self.source_path.parent / filename).resolve()
        try:
            path.relative_to(self.source_path.parent.resolve())
        except ValueError as error:
            raise ValueError(
                f"domain pack {self.pack_id} asset {name} escapes its data directory"
            ) from error
        return path

    def metadata(self) -> dict[str, Any]:
        return {
            "id": self.pack_id,
            "displayName": self.display_name,
            "description": self.description,
            "schemaVersion": self.schema_version,
            "workflowId": self.workflow_id,
            "capabilityProfileId": self.capability_profile_id,
            "categories": [
                {"id": str(item["id"]), "label": str(item["label"])}
                for item in self.categories
            ],
            "exampleQueries": list(self.example_queries),
        }


def load_domain_pack(path: str | Path) -> RetailDomainPack:
    source = Path(path).resolve()
    raw = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ValueError(f"domain pack {source.name} must be an object")

    schema_version = _string(raw.get("schema_version"), "schema_version")
    if schema_version != "1.0":
        raise ValueError("unsupported domain pack schema_version")
    pack_id = _identifier(raw.get("pack_id"), "pack_id")
    display_name = _bounded_string(raw.get("display_name"), "display_name", 120)
    description = _bounded_string(raw.get("description"), "description", 500)
    workflow_id = _identifier(raw.get("workflow_id"), "workflow_id")
    capability_profile_id = _identifier(
        raw.get("capability_profile_id"),
        "capability_profile_id",
    )

    raw_assets = raw.get("assets")
    if not isinstance(raw_assets, dict):
        raise ValueError(f"domain pack {pack_id} assets must be an object")
    assets = {
        _string(name, "assets key"): _asset_name(filename, f"assets.{name}")
        for name, filename in raw_assets.items()
    }
    missing_assets = REQUIRED_ASSETS - set(assets)
    if missing_assets:
        raise ValueError(
            f"domain pack {pack_id} is missing assets: {', '.join(sorted(missing_assets))}"
        )

    raw_categories = raw.get("categories")
    if (
        not isinstance(raw_categories, list)
        or not raw_categories
        or len(raw_categories) > 100
    ):
        raise ValueError(
            f"domain pack {pack_id} categories must contain between 1 and 100 items"
        )
    categories: list[dict[str, Any]] = []
    for index, raw_category in enumerate(raw_categories):
        if not isinstance(raw_category, dict):
            raise ValueError(f"domain pack {pack_id} categories[{index}] must be an object")
        categories.append(
            {
                "id": _identifier(
                    raw_category.get("id"),
                    f"categories[{index}].id",
                    pattern=CATEGORY_IDENTIFIER,
                ),
                "label": _bounded_string(
                    raw_category.get("label"), f"categories[{index}].label", 120
                ),
                "terms": _string_tuple(
                    raw_category.get("terms"), f"categories[{index}].terms"
                ),
            }
        )
    category_ids = [str(item["id"]) for item in categories]
    if len(category_ids) != len(set(category_ids)):
        raise ValueError(f"domain pack {pack_id} category ids must be unique")
    declared_categories = set(category_ids)

    default_category = _string(raw.get("default_category"), "default_category")
    primary_category = _string(raw.get("primary_category"), "primary_category")
    default_bundle_categories = _string_tuple(
        raw.get("default_bundle_categories"), "default_bundle_categories"
    )
    references = (default_category, primary_category, *default_bundle_categories)
    if not all(value in declared_categories for value in references):
        raise ValueError(f"domain pack {pack_id} category references must be declared")

    raw_brands = raw.get("brands")
    if not isinstance(raw_brands, list) or not raw_brands:
        raise ValueError(f"domain pack {pack_id} brands must be a non-empty array")
    brands: list[dict[str, Any]] = []
    for index, raw_brand in enumerate(raw_brands):
        if not isinstance(raw_brand, dict):
            raise ValueError(f"domain pack {pack_id} brands[{index}] must be an object")
        brands.append(
            {
                "name": _string(raw_brand.get("name"), f"brands[{index}].name"),
                "terms": _string_tuple(raw_brand.get("terms"), f"brands[{index}].terms"),
            }
        )
    brand_names = [str(item["name"]) for item in brands]
    if len(brand_names) != len(set(brand_names)):
        raise ValueError(f"domain pack {pack_id} brand names must be unique")

    raw_requirements = raw.get("category_requirements", {})
    if not isinstance(raw_requirements, dict):
        raise ValueError(f"domain pack {pack_id} category_requirements must be an object")
    category_requirements: dict[str, CategoryRequirement] = {}
    for category_id, raw_requirement in raw_requirements.items():
        category_id = _string(category_id, "category_requirements key")
        if category_id not in declared_categories:
            raise ValueError(
                f"domain pack {pack_id} category requirement references unknown category: {category_id}"
            )
        if not isinstance(raw_requirement, dict):
            raise ValueError(
                f"domain pack {pack_id} category_requirements.{category_id} must be an object"
            )
        unknown = set(raw_requirement) - {"connectors_any", "protocols_any"}
        if unknown:
            raise ValueError(
                f"domain pack {pack_id} category_requirements.{category_id} has unknown fields: "
                + ", ".join(sorted(unknown))
            )
        category_requirements[category_id] = CategoryRequirement(
            connectors_any=_string_tuple(
                raw_requirement.get("connectors_any", []),
                f"category_requirements.{category_id}.connectors_any",
                allow_empty=True,
            ),
            protocols_any=_string_tuple(
                raw_requirement.get("protocols_any", []),
                f"category_requirements.{category_id}.protocols_any",
                allow_empty=True,
            ),
        )

    example_queries = _string_tuple(raw.get("example_queries"), "example_queries")
    if len(example_queries) > 20:
        raise ValueError("domain pack example_queries must contain at most 20 items")
    pack = RetailDomainPack(
        schema_version=schema_version,
        pack_id=pack_id,
        display_name=display_name,
        description=description,
        workflow_id=workflow_id,
        capability_profile_id=capability_profile_id,
        source_path=source,
        assets=assets,
        default_category=default_category,
        primary_category=primary_category,
        default_bundle_categories=default_bundle_categories,
        categories=tuple(categories),
        use_cases=_string_tuple(raw.get("use_cases"), "use_cases"),
        brands=tuple(brands),
        protocol_terms=_string_tuple(raw.get("protocol_terms"), "protocol_terms"),
        example_queries=tuple(
            _bounded_string(query, f"example_queries[{index}]", 2_000)
            for index, query in enumerate(example_queries)
        ),
        category_requirements=category_requirements,
    )
    for asset_name in assets:
        asset = pack.asset_path(asset_name)
        if not asset.is_file():
            raise ValueError(
                f"domain pack {pack_id} asset {asset_name} does not exist: {asset.name}"
            )
    return pack


class DomainPackRegistry:
    def __init__(self, packs: Iterable[RetailDomainPack], *, default_pack_id: str) -> None:
        by_id: dict[str, RetailDomainPack] = {}
        for pack in packs:
            if pack.pack_id in by_id:
                raise ValueError(f"duplicate domain pack id: {pack.pack_id}")
            by_id[pack.pack_id] = pack
        if default_pack_id not in by_id:
            raise ValueError(f"default domain pack is not registered: {default_pack_id}")
        self._packs = by_id
        self.default_pack_id = default_pack_id

    @classmethod
    def discover(
        cls,
        data_directory: str | Path = DATA_DIRECTORY,
        *,
        default_pack_id: str = DEFAULT_DOMAIN_PACK_ID,
    ) -> DomainPackRegistry:
        root = Path(data_directory)
        packs: list[RetailDomainPack] = []
        for path in sorted(root.glob("*.json")):
            raw = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(raw, dict) and "pack_id" in raw:
                packs.append(load_domain_pack(path))
        return cls(packs, default_pack_id=default_pack_id)

    def get(self, pack_id: str | None = None) -> RetailDomainPack:
        resolved_id = pack_id or self.default_pack_id
        try:
            return self._packs[resolved_id]
        except KeyError as error:
            raise KeyError(f"unknown domain pack: {resolved_id}") from error

    def all(self) -> tuple[RetailDomainPack, ...]:
        return tuple(self._packs[key] for key in sorted(self._packs))

    def metadata(self) -> list[dict[str, Any]]:
        return [pack.metadata() for pack in self.all()]


DOMAIN_PACK_REGISTRY = DomainPackRegistry.discover()
NORMAL_3C_DOMAIN_PACK_MODEL = DOMAIN_PACK_REGISTRY.get(DEFAULT_DOMAIN_PACK_ID)

# Compatibility exports for the existing normal-3C call sites and tests.
DOMAIN_PACK_PATH = NORMAL_3C_DOMAIN_PACK_MODEL.source_path
NORMAL_3C_DOMAIN_PACK = json.loads(DOMAIN_PACK_PATH.read_text(encoding="utf-8"))
PRODUCT_CATEGORIES = NORMAL_3C_DOMAIN_PACK_MODEL.product_categories
DEFAULT_CATEGORY = NORMAL_3C_DOMAIN_PACK_MODEL.default_category
PRIMARY_CATEGORY = NORMAL_3C_DOMAIN_PACK_MODEL.primary_category
DEFAULT_BUNDLE_CATEGORIES = NORMAL_3C_DOMAIN_PACK_MODEL.default_bundle_categories
COMMERCE_TERMS = NORMAL_3C_DOMAIN_PACK_MODEL.commerce_terms
