#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
PYTHON="$CODE_ROOT/.venv/bin/python"
if [[ ! -x "$PYTHON" ]]; then
  PYTHON="$(command -v python3)"
fi

exec bash "$SCRIPT_DIR/with-modelport-env.sh" \
  env PYTHONPATH="$CODE_ROOT/src:$CODE_ROOT" \
  "$PYTHON" -m shoprec.cli modelport-check
