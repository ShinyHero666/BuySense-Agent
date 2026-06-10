#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
VENV_ROOT="${SHOPREC_VENV:-$CODE_ROOT/.venv}"
SHOPREC="$VENV_ROOT/bin/shoprec"

if [[ ! -x "$SHOPREC" ]]; then
  echo "[setup] Virtual environment not found; running bootstrap."
  SHOPREC_VENV="$VENV_ROOT" bash "$SCRIPT_DIR/bootstrap.sh"
fi

echo "[tour] Starting the guided search demonstration."
"$SHOPREC" \
  --config "$CODE_ROOT/config/experiments.json" \
  search-tour "$@"
