from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from .diagnostic_agent import SearchRecDiagnosticAgent
from .experiments import ExperimentManager
from .model_port import ModelPortChatCompletionsAdapter, ModelPortError
from .scenarios import run_scenario
from .service import create_demo_service, default_experiment_path
from .shoprec_agent import create_buyer_agent
from .validation import ValidationError


def _print_json(data: dict[str, Any]) -> None:
    print(json.dumps(data, ensure_ascii=False, indent=2, default=str))


def _print_trace(data: dict[str, Any]) -> None:
    print("stage          input  output  elapsed_ms  metadata")
    print("-------------  -----  ------  ----------  --------")
    for record in data.get("trace", []):
        metadata = json.dumps(record["metadata"], ensure_ascii=False, separators=(",", ":"))
        print(
            f"{record['name']:<13}  {record['input_count']:>5}  "
            f"{record['output_count']:>6}  {record['elapsed_ms']:>10.3f}  {metadata}"
        )


def _print_summary(data: dict[str, Any]) -> None:
    print(f"request_id: {data.get('request_id', '-')}")
    print(
        "experiments: "
        + json.dumps(data.get("experiments", {}), ensure_ascii=False)
    )
    if "query_info" in data:
        query = data["query_info"]
        print(
            f"query: {query.get('raw_query')} -> {query.get('rewritten_query')} "
            f"(broad={query.get('is_broad')})"
        )
        print(f"total: {data.get('total', 0)}")
    if "pool_targets" in data:
        print(f"pool targets: {data['pool_targets']}")
        print(f"pool actual : {data['pool_counts']}")

    print("pos  id     pool       score     price     category    title")
    print("---  -----  ---------  --------  --------  ----------  ------------------------------")
    for position, item in enumerate(data.get("items", []), start=1):
        score = item.get("scores", {}).get(
            "model_rerank", item.get("scores", {}).get("rank", 0.0)
        )
        print(
            f"{position:>3}  {item['product_id']:<5}  "
            f"{str(item.get('pool') or '-'):9}  {score:>8.5f}  "
            f"{item.get('price', 0):>8.2f}  {item.get('category', '-'):<10}  "
            f"{item['title']}"
        )


def _format_values(values: list[Any] | set[Any] | tuple[Any, ...]) -> str:
    return ", ".join(str(value) for value in values) if values else "-"


def _print_explain(data: dict[str, Any]) -> None:
    _print_summary(data)

    query = data.get("query_info")
    if query:
        print("\n[Query decision]")
        print(f"normalized : {query.get('normalized_query')}")
        print(f"rewritten  : {query.get('rewritten_query')}")
        print(f"terms      : {_format_values(query.get('terms', []))}")
        print(f"categories : {_format_values(query.get('category_intents', []))}")
        print(f"brands     : {_format_values(query.get('brand_intents', []))}")
        print(f"broad/risk : {query.get('is_broad')} / {query.get('blocked')}")
        print(f"reasons    : {_format_values(query.get('reasons', []))}")

    print("\n[Pipeline funnel]")
    for record in data.get("trace", []):
        metadata = record.get("metadata", {})
        details = (
            " " + json.dumps(metadata, ensure_ascii=False, separators=(",", ":"))
            if metadata
            else ""
        )
        print(
            f"{record['name']:<13} {record['input_count']:>4} -> "
            f"{record['output_count']:<4} {record['elapsed_ms']:>8.3f} ms{details}"
        )

    print("\n[Filter reasons]")
    reasons = data.get("filter_reasons", {})
    if reasons:
        for reason, count in sorted(reasons.items()):
            print(f"{reason:<24} {count}")
    else:
        print("-")

    facets = data.get("facets", {})
    if facets:
        print("\n[Facets after filtering]")
        for name, values in facets.items():
            rendered = ", ".join(
                f"{key}={count}"
                for key, count in sorted(
                    values.items(), key=lambda item: (-item[1], item[0])
                )
            )
            print(f"{name:<10} {rendered}")

    print("\n[Result explanations]")
    if not data.get("items"):
        print("No items. Inspect Query decision and RESULT metadata above.")
    for position, item in enumerate(data.get("items", []), start=1):
        features = ", ".join(
            f"{name}={value:.4f}"
            for name, value in sorted(item.get("features", {}).items())
        )
        scores = ", ".join(
            f"{name}={value:.5f}"
            for name, value in item.get("scores", {}).items()
        )
        rerank = item.get("rerank", {})
        service = item.get("service", {})
        rerank_position = (
            f"{rerank.get('before_position')} -> {rerank.get('after_position')}"
            if rerank.get("before_position") is not None
            else "-"
        )
        print(f"\n#{position} {item['product_id']} {item['title']}")
        print(
            "  attributes: "
            f"{item.get('category')}/{item.get('brand')} | "
            f"price={item.get('price')} | {item.get('condition')} | "
            f"city={item.get('city')} | seller={item.get('seller_id')}"
        )
        print(f"  recall    : {_format_values(item.get('recall_sources', []))}")
        if service:
            print(
                "  service   : "
                f"{service.get('mode')} | inspection={service.get('inspection_status')}"
                f"/{service.get('inspection_grade')} | "
                f"battery={service.get('battery_health')} | "
                f"warranty={service.get('warranty_days')}d | "
                f"return={service.get('return_window_days')}d"
            )
            print(f"  findings  : {_format_values(service.get('findings', []))}")
        if item.get("pool"):
            print(f"  pool      : {item['pool']}")
        print(f"  features  : {features or '-'}")
        print(f"  scores    : {scores or '-'}")
        print(
            f"  rerank    : position {rerank_position}; "
            f"reasons={_format_values(rerank.get('reasons', []))}"
        )


def _print_valuation(data: dict[str, Any]) -> None:
    print(f"request_id : {data['request_id']}")
    print(f"quote type : {data['quote_type']}")
    print(f"reference  : {data['reference_source']}")
    print(
        f"estimate   : {data['currency']} {data['estimated_low']} - "
        f"{data['estimated_high']} (mid={data['estimated_mid']})"
    )
    print(f"inspection : required={data['final_price_requires_inspection']}")
    print(f"risk flags : {_format_values(data.get('risk_flags', []))}")
    print("factors:")
    for name, value in data.get("factors", {}).items():
        print(f"  {name:<20} {value}")
    print("next steps:")
    for index, step in enumerate(data.get("next_steps", []), start=1):
        print(f"  {index}. {step}")


def _render(data: dict[str, Any], view: str) -> None:
    if view == "full":
        _print_json(data)
    elif view == "trace":
        _print_trace(data)
    elif view == "explain":
        _print_explain(data)
    else:
        _print_summary(data)


def _variant_tokens(manager: ExperimentManager, layer: str) -> dict[str, str]:
    tokens: dict[str, str] = {}
    for index in range(10000):
        token = f"compare-{layer}-{index}"
        name = manager.assign(layer, token).name
        tokens.setdefault(name, token)
        if len(tokens) == len(manager.layers[layer]):
            return tokens
    raise RuntimeError(f"could not find tokens for all variants in {layer}")


def _add_view(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--view",
        choices=("summary", "trace", "explain", "full"),
        default="summary",
    )


def _print_heading(title: str, focus: str) -> None:
    print("\n" + "=" * 78)
    print(title)
    print(f"Learning focus: {focus}")
    print("=" * 78)


def _run_search_tour(
    service,
    *,
    user_id: str,
    size: int,
    section: str,
    config: str,
) -> None:
    def selected(name: str) -> bool:
        return section in {"all", name}

    if selected("rewrite"):
        _print_heading(
            "1. Query rewrite and multi-source recall",
            "苹果手机 -> iphone; lexical/category/brand signals are merged.",
        )
        _print_explain(
            service.search(
                {
                    "query": "苹果手机",
                    "user_id": user_id,
                    "page_size": size,
                    "filters": {"max_price": 7000},
                }
            )
        )

    if selected("broad"):
        _print_heading(
            "2. Broad-query diversity",
            "A category query activates list reranking and position explanations.",
        )
        _print_explain(
            service.search(
                {"query": "手机", "user_id": user_id, "page_size": size}
            )
        )

    if selected("filters"):
        _print_heading(
            "3. Filters and explicit sort",
            "Hard filters shrink the funnel; price sort replaces relevance order.",
        )
        _print_explain(
            service.search(
                {
                    "query": "手机",
                    "user_id": user_id,
                    "page_size": size,
                    "sort": "price_asc",
                    "filters": {
                        "categories": ["手机"],
                        "max_price": 5000,
                    },
                }
            )
        )

    if selected("fallback"):
        _print_heading(
            "4. Recall fallback",
            "An uncovered query uses hot_fallback so the service remains useful.",
        )
        _print_explain(
            service.search(
                {"query": "露营灯", "user_id": user_id, "page_size": size}
            )
        )

    if selected("experiments"):
        _print_heading(
            "5. Ranking experiment comparison",
            "Stable tokens select each variant; compare weights and item positions.",
        )
        tokens = _variant_tokens(service.experiments, "search_rank")
        results: dict[str, dict[str, Any]] = {}
        for variant, token in tokens.items():
            variant_service = create_demo_service(config)
            result = variant_service.search(
                {
                    "query": "手机",
                    "user_id": user_id,
                    "page_size": size,
                    "experiment_token": token,
                }
            )
            results[variant] = result
            rank_stage = next(
                record for record in result["trace"] if record["name"] == "RANK"
            )
            ids = [item["product_id"] for item in result["items"]]
            print(f"{variant:<16} token={token}")
            print(f"  weights : {rank_stage['metadata']['weights']}")
            print(f"  item ids: {ids}")
        variants = list(results)
        if len(variants) >= 2:
            left = [item["product_id"] for item in results[variants[0]]["items"]]
            right = [item["product_id"] for item in results[variants[1]]["items"]]
            print(f"changed: {left != right}")

    if selected("safety"):
        _print_heading(
            "6. Risk-query interception",
            "Risk decisions stop recall early but still return an explainable response.",
        )
        _print_explain(
            service.search(
                {"query": "假证", "user_id": user_id, "page_size": size}
            )
        )

    print("\nTour complete.")
    print("Try one section: bash scripts/quick-search.sh --section broad")
    print('Try your query  : .venv/bin/shoprec search "相机" --view explain')


def _run_secondhand_business_tour(service, *, user_id: str) -> None:
    print(
        "Teaching case based on publicly visible second-hand service patterns; "
        "this is not a description of any company's internal implementation."
    )
    _print_heading(
        "1. Moyuan Select inspected phone search",
        "Trust, inspection grade, battery health, warranty and price act together.",
    )
    _print_explain(
        service.search(
            {
                "query": "iphone",
                "user_id": user_id,
                "page_size": 5,
                "filters": {
                    "categories": ["手机"],
                    "inspection_grades": ["A", "B"],
                    "min_battery_health": 85,
                    "warranty_required": True,
                    "max_price": 4000,
                },
            }
        )
    )

    _print_heading(
        "2. Pre-inspection recycling quote",
        "An online estimate is explainable, but the final price requires inspection.",
    )
    _print_valuation(
        service.value_device(
            {
                "brand": "Apple",
                "model": "iPhone 13",
                "category": "手机",
                "storage_gb": 128,
                "age_months": 36,
                "condition_grade": "B",
                "battery_health": 90,
                "inspection_method": "door",
            }
        )
    )

    _print_heading(
        "3. Consignment inventory search",
        "Supply channel is a filterable contract, not an opaque ranking side effect.",
    )
    _print_explain(
        service.search(
            {
                "query": "笔记本",
                "user_id": user_id,
                "page_size": 5,
                "filters": {
                    "service_modes": ["consignment"],
                    "warranty_required": True,
                },
            }
        )
    )

    _print_heading(
        "4. Trust-aware recommendation",
        "Recommendation keeps interest and discovery while exposing inspection trust.",
    )
    _print_explain(
        service.recommend(
            {
                "user_id": user_id,
                "size": 5,
                "exclude_seen": False,
            }
        )
    )
    print("\nSecond-hand business tour complete.")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Moyuan search, recommendation, and buyer Agent lab"
    )
    parser.add_argument(
        "--config",
        default=str(default_experiment_path()),
        help="experiment JSON path",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    search = subparsers.add_parser("search", help="run a search request")
    search.add_argument("query")
    search.add_argument("--user", default="u001")
    search.add_argument(
        "--sort",
        choices=("relevance", "price_asc", "price_desc", "newest"),
        default="relevance",
    )
    search.add_argument("--city")
    search.add_argument("--min-price", type=float)
    search.add_argument("--max-price", type=float)
    search.add_argument(
        "--category",
        action="append",
        default=[],
        help="category filter; repeat for multiple values",
    )
    search.add_argument(
        "--brand",
        action="append",
        default=[],
        help="brand filter; repeat for multiple values",
    )
    search.add_argument(
        "--condition",
        action="append",
        default=[],
        help="condition filter; repeat for multiple values",
    )
    search.add_argument(
        "--service-mode",
        action="append",
        default=[],
        choices=(
            "platform_inspected",
            "recycle_inventory",
            "consignment",
            "store_inventory",
        ),
        help="second-hand supply channel; repeat for multiple values",
    )
    search.add_argument(
        "--inspection-grade",
        action="append",
        default=[],
        choices=("A", "B", "C", "D"),
        help="inspection grade; repeat for multiple values",
    )
    search.add_argument("--min-battery-health", type=int)
    search.add_argument("--warranty-required", action="store_true")
    search.add_argument("--page", type=int, default=1)
    search.add_argument("--size", type=int, default=10)
    search.add_argument("--token", default="")
    _add_view(search)

    recommend = subparsers.add_parser("recommend", help="run a recommendation")
    recommend.add_argument("--user", default="u001")
    recommend.add_argument("--size", type=int, default=10)
    recommend.add_argument("--token", default="")
    recommend.add_argument("--include-seen", action="store_true")
    _add_view(recommend)

    compare_search = subparsers.add_parser(
        "compare-search", help="compare all search rank variants"
    )
    compare_search.add_argument("query")
    compare_search.add_argument("--user", default="u001")
    compare_search.add_argument("--size", type=int, default=8)

    compare_flowpool = subparsers.add_parser(
        "compare-flowpool", help="compare recommendation pool variants"
    )
    compare_flowpool.add_argument("--user", default="u001")
    compare_flowpool.add_argument("--size", type=int, default=8)

    scenario = subparsers.add_parser("scenario", help="run a JSON learning scenario")
    scenario.add_argument("path", type=Path)
    scenario.add_argument("--full", action="store_true")

    search_tour = subparsers.add_parser(
        "search-tour", help="run a guided search-system demonstration"
    )
    search_tour.add_argument("--user", default="u001")
    search_tour.add_argument("--size", type=int, default=5)
    search_tour.add_argument(
        "--section",
        choices=(
            "all",
            "rewrite",
            "broad",
            "filters",
            "fallback",
            "experiments",
            "safety",
        ),
        default="all",
    )

    catalog = subparsers.add_parser(
        "catalog", help="inspect the built-in teaching catalog"
    )
    catalog.add_argument("--category")
    catalog.add_argument("--brand")
    catalog.add_argument("--limit", type=int, default=100)

    valuation = subparsers.add_parser(
        "value-device", help="estimate a pre-inspection recycling quote"
    )
    valuation.add_argument("--brand", required=True)
    valuation.add_argument("--model", required=True)
    valuation.add_argument("--category", default="手机")
    valuation.add_argument("--storage", type=int, default=128)
    valuation.add_argument("--age-months", type=int, default=24)
    valuation.add_argument(
        "--condition-grade", choices=("A", "B", "C", "D"), default="B"
    )
    valuation.add_argument("--battery-health", type=int)
    valuation.add_argument(
        "--issue",
        action="append",
        default=[],
        choices=tuple(sorted(
            (
                "screen_damage",
                "camera_fault",
                "biometric_fault",
                "water_damage",
                "mainboard_repair",
                "non_original_part",
            )
        )),
    )
    valuation.add_argument(
        "--inspection-method", choices=("mail", "door", "store"), default="mail"
    )
    valuation.add_argument("--full", action="store_true")

    secondhand_tour = subparsers.add_parser(
        "secondhand-tour",
        help="run a platform-inspection, recycling and consignment teaching case",
    )
    secondhand_tour.add_argument("--user", default="u001")

    agent = subparsers.add_parser(
        "agent", help="run the bounded Moyuan buyer search/recommendation Agent"
    )
    agent.add_argument("--session", default="cli-session")
    agent.add_argument("--user", default="u001")
    agent.add_argument(
        "--model-mode", choices=("replay", "modelport"), default="replay"
    )
    agent.add_argument(
        "--message",
        action="append",
        default=[],
        help="send a turn; repeat to replay a full conversation",
    )
    agent.add_argument("--full", action="store_true")

    subparsers.add_parser(
        "modelport-check", help="verify the external ModelPort gateway and logical alias"
    )

    diagnose = subparsers.add_parser(
        "diagnose-search", help="run the advanced read-only search/rec diagnostic Agent"
    )
    diagnose.add_argument("query")
    diagnose.add_argument("--user", default="u001")
    diagnose.add_argument("--max-price", type=float)

    demo = subparsers.add_parser("demo", help="run search and recommendation")
    demo.add_argument("--user", default="u001")
    _add_view(demo)
    return parser


def run(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "modelport-check":
            result = ModelPortChatCompletionsAdapter.from_env().preflight()
            _print_json(result)
            return 0 if result["model_visible"] else 2
        if args.command == "agent":
            buyer_agent = create_buyer_agent(
                model_mode=args.model_mode,
                experiment_path=args.config,
            )
            if args.message:
                for value in args.message:
                    reply = buyer_agent.handle(args.session, args.user, value)
                    if args.full:
                        _print_json(reply.to_dict())
                    else:
                        print(f"\n> {value}\n{reply.message}")
                return 0
            print("墨圆电商搜推 Agent（输入 exit 结束）")
            while True:
                try:
                    value = input("> ").strip()
                except EOFError:
                    break
                if value.casefold() in {"exit", "quit", "退出"}:
                    break
                if value:
                    reply = buyer_agent.handle(args.session, args.user, value)
                    _print_json(reply.to_dict()) if args.full else print(reply.message)
            return 0

        service = create_demo_service(args.config)
        if args.command == "search":
            result = service.search(
                {
                    "query": args.query,
                    "user_id": args.user,
                    "sort": args.sort,
                    "page": args.page,
                    "page_size": args.size,
                    "experiment_token": args.token,
                    "filters": {
                        "categories": args.category,
                        "brands": args.brand,
                        "city": args.city,
                        "min_price": args.min_price,
                        "max_price": args.max_price,
                        "conditions": args.condition,
                        "service_modes": args.service_mode,
                        "inspection_grades": args.inspection_grade,
                        "min_battery_health": args.min_battery_health,
                        "warranty_required": args.warranty_required,
                    },
                }
            )
            _render(result, args.view)
        elif args.command == "diagnose-search":
            report = SearchRecDiagnosticAgent(service).diagnose_search(
                {
                    "query": args.query,
                    "user_id": args.user,
                    "page_size": 10,
                    "filters": {"max_price": args.max_price},
                }
            )
            _print_json(report.to_dict())
        elif args.command == "recommend":
            result = service.recommend(
                {
                    "user_id": args.user,
                    "size": args.size,
                    "experiment_token": args.token,
                    "exclude_seen": not args.include_seen,
                }
            )
            _render(result, args.view)
        elif args.command == "compare-search":
            tokens = _variant_tokens(service.experiments, "search_rank")
            for variant, token in tokens.items():
                print(f"\n=== search_rank/{variant} token={token} ===")
                variant_service = create_demo_service(args.config)
                _print_summary(
                    variant_service.search(
                        {
                            "query": args.query,
                            "user_id": args.user,
                            "page_size": args.size,
                            "experiment_token": token,
                        }
                    )
                )
        elif args.command == "compare-flowpool":
            tokens = _variant_tokens(service.experiments, "recommend_flowpool")
            for variant, token in tokens.items():
                print(f"\n=== recommend_flowpool/{variant} token={token} ===")
                variant_service = create_demo_service(args.config)
                _print_summary(
                    variant_service.recommend(
                        {
                            "user_id": args.user,
                            "size": args.size,
                            "experiment_token": token,
                            "exclude_seen": False,
                        }
                    )
                )
        elif args.command == "scenario":
            result = run_scenario(args.path, service)
            if args.full:
                _print_json(result)
            else:
                print(f"scenario: {result['scenario']} [{result['status']}]")
                for step in result["steps"]:
                    print(f"  {step['name']}: {step['status']}")
        elif args.command == "search-tour":
            _run_search_tour(
                service,
                user_id=args.user,
                size=args.size,
                section=args.section,
                config=args.config,
            )
        elif args.command == "catalog":
            if args.limit < 1 or args.limit > 1000:
                raise ValidationError("limit", "must be between 1 and 1000")
            products = [
                product
                for product in service.products
                if (not args.category or product.category == args.category)
                and (not args.brand or product.brand == args.brand)
            ][: args.limit]
            print(
                "id     category    brand       price     stock  grade  "
                "service             title"
            )
            print(
                "-----  ----------  ----------  --------  -----  -----  "
                "------------------  ------------------------------"
            )
            for product in products:
                print(
                    f"{product.product_id:<5}  {product.category:<10}  "
                    f"{product.brand:<10}  {product.price:>8.2f}  "
                    f"{product.stock:>5}  {product.inspection_grade:<5}  "
                    f"{product.service_mode:<18}  {product.title}"
                )
            print(f"shown: {len(products)} / catalog: {len(service.products)}")
        elif args.command == "value-device":
            result = service.value_device(
                {
                    "brand": args.brand,
                    "model": args.model,
                    "category": args.category,
                    "storage_gb": args.storage,
                    "age_months": args.age_months,
                    "condition_grade": args.condition_grade,
                    "battery_health": args.battery_health,
                    "functional_issues": args.issue,
                    "inspection_method": args.inspection_method,
                }
            )
            _print_json(result) if args.full else _print_valuation(result)
        elif args.command == "secondhand-tour":
            _run_secondhand_business_tour(service, user_id=args.user)
        else:
            _render(
                service.search(
                    {"query": "手机", "user_id": args.user, "page_size": 5}
                ),
                args.view,
            )
            print()
            _render(
                service.recommend({"user_id": args.user, "size": 5}),
                args.view,
            )
        return 0
    except (ValidationError, ModelPortError, ValueError, AssertionError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


def main() -> None:
    raise SystemExit(run())


if __name__ == "__main__":
    main()
