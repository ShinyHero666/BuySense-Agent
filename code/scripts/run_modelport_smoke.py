#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

from shoprec.model_port import ModelPortChatCompletionsAdapter
from shoprec.shoprec_agent import create_buyer_agent


def main() -> None:
    parser = argparse.ArgumentParser(description="Run one real local ModelPort Agent turn")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    adapter = ModelPortChatCompletionsAdapter.from_env()
    preflight = adapter.preflight()
    agent = create_buyer_agent(model=adapter)
    reply = agent.handle(
        "modelport-smoke",
        "u001",
        "预算不超过3500元，128G以上，电池至少86，必须质保，不接受维修",
    )
    model_trace = next(
        (
            event["detail"]
            for event in reply.trace
            if event["event_type"] in {"model_call", "model_degraded"}
        ),
        {},
    )
    report = {
        "suite": "moyuan-modelport-local-smoke-v1",
        "preflight": {
            "ready": preflight.get("ready", {}).get("status") == "ok",
            "model_visible": preflight["model_visible"],
            "model": preflight["model"],
        },
        "agent": {
            "model_degraded": reply.model_degraded,
            "tool": reply.tool["name"] if reply.tool else None,
            "candidate_count": len(reply.items),
            "routing_decision_id": model_trace.get("routing_decision_id", ""),
            "routing_mode": model_trace.get("routing_mode", ""),
            "latency_ms": model_trace.get("latency_ms", 0.0),
            "usage": model_trace.get("usage", {}),
            "arguments_normalized_by_policy": model_trace.get(
                "arguments_normalized_by_policy", False
            ),
        },
    }
    report["passed"] = bool(
        report["preflight"]["ready"]
        and report["preflight"]["model_visible"]
        and not report["agent"]["model_degraded"]
        and report["agent"]["tool"] == "search_products"
        and 1 <= report["agent"]["candidate_count"] <= 3
        and report["agent"]["routing_decision_id"]
    )
    rendered = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True)
    print(rendered)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(rendered + "\n", encoding="utf-8")
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
