#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
VENV_ROOT="${SHOPREC_VENV:-$CODE_ROOT/.venv}"

if [[ -x "$VENV_ROOT/bin/python" ]]; then
  PYTHON="$VENV_ROOT/bin/python"
elif command -v python3 >/dev/null 2>&1; then
  PYTHON="$(command -v python3)"
else
  echo "No Python 3 interpreter was found." >&2
  exit 1
fi

export PYTHONPATH="$CODE_ROOT/src:$CODE_ROOT${PYTHONPATH:+:$PYTHONPATH}"

echo "[1/5] Compile"
"$PYTHON" -m compileall -q "$CODE_ROOT/src"

echo "[2/5] Unit and integration tests"
"$PYTHON" -m unittest discover -s "$CODE_ROOT/tests" -v

echo "[3/5] Learning scenarios"
mapfile -d '' SCENARIOS < <(
  find "$CODE_ROOT/scenarios" -maxdepth 1 -type f -name '*.json' -print0 | sort -z
)
if (( ${#SCENARIOS[@]} == 0 )); then
  echo "No scenario JSON files found." >&2
  exit 1
fi
for scenario in "${SCENARIOS[@]}"; do
  "$PYTHON" -m shoprec.cli \
    --config "$CODE_ROOT/config/experiments.json" \
    scenario "$scenario"
done

echo "[4/5] Deterministic Agent evaluation"
"$PYTHON" "$CODE_ROOT/scripts/run_agent_eval.py" \
  --json-out "$CODE_ROOT/agent_eval/reports/offline-v1.json" \
  --markdown-out "$CODE_ROOT/agent_eval/reports/offline-v1.md"

echo "[5/5] Linux scripts and course documents"
bash -n "$CODE_ROOT"/scripts/*.sh
"$PYTHON" "$CODE_ROOT/scripts/check_course.py"

echo "All checks passed."
