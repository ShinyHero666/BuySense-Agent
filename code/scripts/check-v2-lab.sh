#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
CONTROL_ROOT="$CODE_ROOT/agent-control-plane"
LAB="${1:-}"

if [[ ! "$LAB" =~ ^[1-6]$ ]]; then
  echo "usage: bash scripts/check-v2-lab.sh <1-6>" >&2
  exit 2
fi
if [[ ! -d "$CONTROL_ROOT/node_modules" ]]; then
  echo "Control-plane dependencies are missing. Run make sar-agent-install first." >&2
  exit 1
fi

PYTHON="$CODE_ROOT/.venv/bin/python"
if [[ ! -x "$PYTHON" ]]; then
  PYTHON="$(command -v python3)"
fi
export PYTHONPATH="$CODE_ROOT/src:$CODE_ROOT${PYTHONPATH:+:$PYTHONPATH}"

case "$LAB" in
  1)
    cd "$CONTROL_ROOT"
    node --import tsx --test test/v2-server.test.ts test/v2-cancel.test.ts
    ;;
  2)
    cd "$CONTROL_ROOT"
    node --import tsx --test test/model-policy.test.ts
    ;;
  3)
    cd "$CONTROL_ROOT"
    node --import tsx --test test/collaboration.test.ts test/orchestrator.test.ts
    ;;
  4)
    "$PYTHON" -m unittest tests.test_retail_discovery tests.test_retail_decision -v
    ;;
  5)
    cd "$CONTROL_ROOT"
    node --import tsx --test test/buyer-agent.test.ts test/server.test.ts
    ;;
  6)
    cd "$CONTROL_ROOT"
    node --import tsx --test test/collaboration.test.ts test/v2-cancel.test.ts test/evaluation.test.ts
    npm run eval
    ;;
esac

echo "V2 Lab $LAB checkpoint passed."
