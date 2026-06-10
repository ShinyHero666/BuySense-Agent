from __future__ import annotations

from .models import DeviceValuationRequest, DeviceValuationResponse
from .runtime import IdGenerator, UUIDIdGenerator


class DeviceValuationEngine:
    """Explainable pre-inspection quote for the second-hand teaching case."""

    MODEL_REFERENCE_PRICES = {
        ("apple", "iphone 15 pro"): 7999,
        ("apple", "iphone 13"): 4999,
        ("huawei", "mate 60 pro"): 6999,
        ("xiaomi", "小米 14"): 3999,
        ("apple", "macbook air m2"): 8999,
        ("lenovo", "thinkpad x1 carbon"): 9999,
    }
    CATEGORY_REFERENCE_PRICES = {
        "手机": 4500,
        "电脑": 7500,
        "平板": 4000,
        "数码配件": 1500,
    }
    CONDITION_FACTORS = {
        "A": 0.92,
        "B": 0.82,
        "C": 0.68,
        "D": 0.48,
    }
    ISSUE_FACTORS = {
        "screen_damage": 0.68,
        "camera_fault": 0.85,
        "biometric_fault": 0.78,
        "water_damage": 0.45,
        "mainboard_repair": 0.62,
        "non_original_part": 0.80,
    }

    def __init__(self, id_generator: IdGenerator | None = None) -> None:
        self.id_generator = id_generator or UUIDIdGenerator()

    @staticmethod
    def _storage_factor(storage_gb: int) -> float:
        if storage_gb <= 64:
            return 0.90
        if storage_gb <= 128:
            return 1.00
        if storage_gb <= 256:
            return 1.10
        if storage_gb <= 512:
            return 1.20
        return 1.28

    @staticmethod
    def _battery_factor(battery_health: int | None) -> float:
        if battery_health is None or battery_health >= 95:
            return 1.00
        if battery_health >= 90:
            return 0.97
        if battery_health >= 85:
            return 0.91
        if battery_health >= 80:
            return 0.83
        return 0.70

    def estimate(self, request: DeviceValuationRequest) -> DeviceValuationResponse:
        key = (
            " ".join(request.brand.casefold().split()),
            " ".join(request.model.casefold().split()),
        )
        if key in self.MODEL_REFERENCE_PRICES:
            reference_price = self.MODEL_REFERENCE_PRICES[key]
            reference_source = "model_reference"
        elif request.category in self.CATEGORY_REFERENCE_PRICES:
            reference_price = self.CATEGORY_REFERENCE_PRICES[request.category]
            reference_source = "category_reference"
        else:
            reference_price = 3000
            reference_source = "generic_fallback"
        age_factor = max(0.28, 0.985**request.age_months)
        condition_factor = self.CONDITION_FACTORS[request.condition_grade]
        battery_factor = self._battery_factor(request.battery_health)
        storage_factor = self._storage_factor(request.storage_gb)
        issue_factor = 1.0
        for issue in request.functional_issues:
            issue_factor *= self.ISSUE_FACTORS[issue]
        issue_factor = max(0.25, issue_factor)

        factors = {
            "reference_price": float(reference_price),
            "age": round(age_factor, 4),
            "condition": condition_factor,
            "battery": battery_factor,
            "storage": storage_factor,
            "functional_issues": round(issue_factor, 4),
        }
        raw_mid = (
            reference_price
            * age_factor
            * condition_factor
            * battery_factor
            * storage_factor
            * issue_factor
        )
        estimated_mid = max(50, int(round(raw_mid / 10.0) * 10))
        estimated_low = max(50, int(round(estimated_mid * 0.92 / 10.0) * 10))
        estimated_high = max(
            estimated_low,
            int(round(estimated_mid * 1.08 / 10.0) * 10),
        )

        risk_flags: list[str] = []
        if request.battery_health is not None and request.battery_health < 80:
            risk_flags.append("BATTERY_BELOW_80")
        if request.functional_issues:
            risk_flags.append("FUNCTIONAL_ISSUES_DECLARED")
        if any(
            issue in {"water_damage", "mainboard_repair"}
            for issue in request.functional_issues
        ):
            risk_flags.append("MANUAL_REVIEW_REQUIRED")

        return DeviceValuationResponse(
            request_id=self.id_generator.new_id("valuation"),
            quote_type="pre_inspection_estimate",
            reference_source=reference_source,
            currency="CNY",
            estimated_low=estimated_low,
            estimated_high=estimated_high,
            estimated_mid=estimated_mid,
            final_price_requires_inspection=True,
            factors=factors,
            risk_flags=risk_flags,
            next_steps=[
                f"choose_{request.inspection_method}_inspection",
                "complete_device_and_identity_checks",
                "receive_final_quote",
                "accept_quote_or_request_return",
            ],
        )
