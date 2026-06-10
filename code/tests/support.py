from __future__ import annotations

from datetime import datetime, timezone

from shoprec.experiments import ExperimentManager
from shoprec.runtime import FixedClock, SequenceIdGenerator
from shoprec.service import create_demo_service


REFERENCE_TIME = datetime(2026, 7, 29, 12, 0, tzinfo=timezone.utc)


def fixed_service():
    clock = FixedClock(REFERENCE_TIME)
    return (
        create_demo_service(clock=clock, id_generator=SequenceIdGenerator()),
        clock,
    )


def token_for(layer: str, variant_name: str) -> str:
    manager = ExperimentManager.default()
    for index in range(10000):
        token = f"test-{layer}-{index}"
        if manager.assign(layer, token).name == variant_name:
            return token
    raise AssertionError(f"no token found for {layer}/{variant_name}")

