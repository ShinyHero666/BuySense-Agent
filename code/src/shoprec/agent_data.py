from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from .models import Product


def default_catalog_path() -> Path:
    return Path(__file__).resolve().parent / "data" / "iphone_catalog.jsonl"


def load_agent_products(path: str | Path | None = None) -> list[Product]:
    catalog_path = Path(path) if path is not None else default_catalog_path()
    products: list[Product] = []
    seen: set[str] = set()
    with catalog_path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            item = json.loads(line)
            product_id = str(item["product_id"])
            if product_id in seen:
                raise ValueError(
                    f"duplicate product_id at {catalog_path}:{line_number}: {product_id}"
                )
            seen.add(product_id)
            products.append(
                Product(
                    product_id=product_id,
                    title=str(item["title"]),
                    category="手机",
                    brand="Apple",
                    price=float(item["price"]),
                    condition=str(item["condition"]),
                    city=str(item["city"]),
                    seller_id=str(item["seller_id"]),
                    stock=int(item["stock"]),
                    publish_time=datetime.fromisoformat(str(item["publish_time"])),
                    tags=tuple(str(value) for value in item.get("tags", [])),
                    quality_score=float(item["quality_score"]),
                    historical_ctr=float(item["historical_ctr"]),
                    historical_cvr=float(item["historical_cvr"]),
                    service_mode=str(item["service_mode"]),
                    inspection_status=str(item["inspection_status"]),
                    inspection_grade=str(item["inspection_grade"]),
                    warranty_days=int(item["warranty_days"]),
                    return_window_days=int(item["return_window_days"]),
                    battery_health=int(item["battery_health"]),
                    inspection_findings=tuple(
                        str(value) for value in item.get("inspection_findings", [])
                    ),
                    model_name=str(item["model_name"]),
                    storage_gb=int(item["storage_gb"]),
                    color=str(item["color"]),
                    repair_history=tuple(
                        str(value) for value in item.get("repair_history", [])
                    ),
                )
            )
    if len(products) != 200:
        raise ValueError(f"agent catalog must contain 200 products, got {len(products)}")
    return products
