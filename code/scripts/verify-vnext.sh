#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
PYTHON="$CODE_ROOT/.venv/bin/python"
if [[ ! -x "$PYTHON" ]]; then
  PYTHON="$(command -v python3)"
fi

export PYTHONPATH="$CODE_ROOT/src:$CODE_ROOT${PYTHONPATH:+:$PYTHONPATH}"

echo "[1/7] Shared contract drift"
"$PYTHON" "$CODE_ROOT/packages/contracts/generate_contracts.py" --check

echo "[2/7] Python unit and integration tests"
"$PYTHON" -m unittest discover -s "$CODE_ROOT/tests" -v

echo "[3/7] TypeScript/Pi control-plane gates"
npm --prefix "$CODE_ROOT/agent-control-plane" run check
npm --prefix "$CODE_ROOT/agent-control-plane" test
npm --prefix "$CODE_ROOT/agent-control-plane" run eval

echo "[4/7] Reproducible scale data and retrieval quality"
BENCHMARK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/moyuan-v2-benchmark.XXXXXX")"
cleanup() {
  if [[ "$BENCHMARK_DIR" == *moyuan-v2-benchmark.* && -d "$BENCHMARK_DIR" ]]; then
    rm -rf -- "$BENCHMARK_DIR"
  fi
}
trap cleanup EXIT
"$PYTHON" "$CODE_ROOT/scripts/generate_v2_benchmarks.py" --output-dir "$BENCHMARK_DIR"
cmp "$BENCHMARK_DIR/manifest.json" "$CODE_ROOT/data/v2/manifest.json"
cmp "$BENCHMARK_DIR/golden_queries_v2.json" "$CODE_ROOT/data/v2/golden_queries_v2.json"
cmp "$BENCHMARK_DIR/scale_catalog_v2.json" "$CODE_ROOT/data/v2/scale_catalog_v2.json"
"$PYTHON" "$CODE_ROOT/scripts/evaluate_retail_v2.py" --data-dir "$BENCHMARK_DIR"

echo "[5/7] React/ECharts console"
npm --prefix "$CODE_ROOT/apps/commerce-console" run check
npm --prefix "$CODE_ROOT/apps/commerce-console" run build
npm --prefix "$CODE_ROOT/apps/commerce-console" run test:e2e

echo "[6/7] Linux scripts"
bash -n "$CODE_ROOT"/scripts/*.sh

echo "[7/7] Generated Python compile"
"$PYTHON" -m compileall -q "$CODE_ROOT/src"

echo "V2 search-ads-recommendation Agent gates passed."
