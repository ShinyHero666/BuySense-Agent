#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
PROJECT_ROOT="$(cd -- "$CODE_ROOT/.." && pwd -P)"

if [[ -z "${MOYUAN_MODELPORT_API_KEY:-}" ]]; then
  if [[ -n "${MODELPORT_ENV_FILE:-}" ]]; then
    RESOLVED_ENV_FILE="$MODELPORT_ENV_FILE"
  elif [[ -f "$CODE_ROOT/.env" ]]; then
    RESOLVED_ENV_FILE="$CODE_ROOT/.env"
  elif [[ -n "${MODELPORT_ROOT:-}" && -f "$MODELPORT_ROOT/.env" ]]; then
    RESOLVED_ENV_FILE="$MODELPORT_ROOT/.env"
  elif [[ -f "$PROJECT_ROOT/../ModelPort/.env" ]]; then
    RESOLVED_ENV_FILE="$PROJECT_ROOT/../ModelPort/.env"
  else
    RESOLVED_ENV_FILE=""
  fi

  if [[ -z "$RESOLVED_ENV_FILE" || ! -f "$RESOLVED_ENV_FILE" ]]; then
    echo "No ModelPort client configuration was found." >&2
    echo "Copy code/.env.example to code/.env and set a scoped client key," >&2
    echo "or export MOYUAN_MODELPORT_API_KEY / MODELPORT_ENV_FILE." >&2
    exit 2
  fi

  # shellcheck disable=SC1090
  set -a
  source "$RESOLVED_ENV_FILE"
  set +a
fi

export MOYUAN_MODELPORT_BASE_URL="${MOYUAN_MODELPORT_BASE_URL:-http://127.0.0.1:38082}"
export MOYUAN_MODELPORT_MODEL="${MOYUAN_MODELPORT_MODEL:-moyuan-shoprec-agent}"
export MOYUAN_MODELPORT_API_KEY="${MOYUAN_MODELPORT_API_KEY:-${MODELPORT_CLIENT_KEY:-${MODELPORT_AUTH_TOKEN:-}}}"

if [[ -z "$MOYUAN_MODELPORT_API_KEY" || "$MOYUAN_MODELPORT_API_KEY" == replace-* ]]; then
  echo "Set MOYUAN_MODELPORT_API_KEY or issue a scoped ModelPort client key." >&2
  exit 2
fi
if [[ $# -eq 0 ]]; then
  echo "usage: bash scripts/with-modelport-env.sh <command> [args...]" >&2
  exit 2
fi

exec "$@"
