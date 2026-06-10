#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
VENV_ROOT="${SHOPREC_VENV:-$CODE_ROOT/.venv}"
LAB="${1:-}"

if [[ ! "$LAB" =~ ^([1-9]|1[0-8])$ ]]; then
  echo "usage: bash scripts/check-lab.sh <1-18>" >&2
  exit 2
fi
if [[ -x "$VENV_ROOT/bin/python" ]]; then
  PYTHON="$VENV_ROOT/bin/python"
elif command -v python3 >/dev/null 2>&1; then
  PYTHON="$(command -v python3)"
else
  echo "No Python 3 interpreter was found." >&2
  exit 1
fi

export PYTHONPATH="$CODE_ROOT/src:$CODE_ROOT${PYTHONPATH:+:$PYTHONPATH}"
printf -v TEST_NAME 'tests.test_labs.Lab%02dTest' "$LAB"
"$PYTHON" -m unittest "$TEST_NAME" -v
