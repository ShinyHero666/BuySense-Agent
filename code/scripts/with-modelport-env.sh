#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
PROJECT_ROOT="$(cd -- "$CODE_ROOT/.." && pwd -P)"

MODELPORT_ENV_KEYS=(
  MOYUAN_MODELPORT_BASE_URL
  MOYUAN_MODELPORT_HEALTH_URL
  MOYUAN_MODELPORT_API_KEY
  MOYUAN_MODELPORT_MODEL
  MOYUAN_MODELPORT_TIMEOUT_SECONDS
  MOYUAN_MODELPORT_MAX_COMPLETION_TOKENS
  MOYUAN_MODELPORT_ROUTING_PROFILE
  MOYUAN_MODELPORT_HYBRID_MODE
  MOYUAN_MODELPORT_DATA_CLASSIFICATION
  MOYUAN_MODELPORT_MODEL_LEAD
  MOYUAN_MODELPORT_MODEL_INTENT_ROUTER
  MOYUAN_MODELPORT_MODEL_SEARCH
  MOYUAN_MODELPORT_MODEL_RECOMMENDATION
  MOYUAN_MODELPORT_MODEL_ADS
  MOYUAN_MODELPORT_MODEL_COMPATIBILITY
  MOYUAN_MODELPORT_MODEL_PRICING
  MOYUAN_MODELPORT_MODEL_REVIEW_EVIDENCE
  MOYUAN_MODELPORT_MODEL_CART
  MOYUAN_MODELPORT_MODEL_CRITIC
  MODELPORT_CLIENT_KEY
  MODELPORT_AUTH_TOKEN
)

is_modelport_env_key() {
  local candidate="$1"
  local allowed
  for allowed in "${MODELPORT_ENV_KEYS[@]}"; do
    if [[ "$candidate" == "$allowed" ]]; then
      return 0
    fi
  done
  return 1
}

trim_whitespace() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

load_modelport_env_file() {
  local env_file="$1"
  local line key value
  declare -A seen=()

  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    if [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]]; then
      continue
    fi
    if [[ ! "$line" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      if [[ "$line" =~ MOYUAN_MODELPORT_ || "$line" =~ MODELPORT_CLIENT_KEY ||
            "$line" =~ MODELPORT_AUTH_TOKEN ]]; then
        echo "Malformed ModelPort setting in $env_file" >&2
        exit 2
      fi
      continue
    fi
    key="${BASH_REMATCH[2]}"
    value="$(trim_whitespace "${BASH_REMATCH[3]}")"
    if [[ ( "$key" == MOYUAN_MODELPORT_* || "$key" == MODELPORT_CLIENT_KEY ||
            "$key" == MODELPORT_AUTH_TOKEN ) ]] && ! is_modelport_env_key "$key"; then
      echo "Unsupported ModelPort setting in $env_file: $key" >&2
      exit 2
    fi
    is_modelport_env_key "$key" || continue
    if [[ -n "${seen[$key]:-}" ]]; then
      echo "Duplicate ModelPort setting in $env_file: $key" >&2
      exit 2
    fi
    seen[$key]=1
    if [[ "$value" == \"* || "$value" == \'* ]]; then
      if [[ ${#value} -lt 2 || "${value: -1}" != "${value:0:1}" ]]; then
        echo "Unterminated quoted ModelPort setting in $env_file: $key" >&2
        exit 2
      fi
      value="${value:1:${#value}-2}"
    elif [[ "$value" =~ [[:space:]] ]]; then
      echo "Unquoted whitespace in ModelPort setting in $env_file: $key" >&2
      exit 2
    fi
    if [[ -z "${!key+x}" ]]; then
      printf -v "$key" '%s' "$value"
      export "$key"
    fi
  done < "$env_file"
}

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

  load_modelport_env_file "$RESOLVED_ENV_FILE"
fi

export MOYUAN_MODELPORT_BASE_URL="${MOYUAN_MODELPORT_BASE_URL:-http://127.0.0.1:38082}"
export MOYUAN_MODELPORT_MODEL="${MOYUAN_MODELPORT_MODEL:-moyuan-shoprec-agent}"
export MOYUAN_MODELPORT_API_KEY="${MOYUAN_MODELPORT_API_KEY:-${MODELPORT_CLIENT_KEY:-${MODELPORT_AUTH_TOKEN:-}}}"

if [[ -z "$MOYUAN_MODELPORT_API_KEY" || "$MOYUAN_MODELPORT_API_KEY" == replace-* ]]; then
  echo "Set MOYUAN_MODELPORT_API_KEY or issue a scoped ModelPort client key." >&2
  exit 2
fi
if (( ${#MOYUAN_MODELPORT_API_KEY} > 4096 )) ||
   [[ "$MOYUAN_MODELPORT_API_KEY" == *$'\n'* || "$MOYUAN_MODELPORT_API_KEY" == *$'\r'* ]]; then
  echo "MOYUAN_MODELPORT_API_KEY contains invalid characters or is too long." >&2
  exit 2
fi
if [[ $# -eq 0 ]]; then
  echo "usage: bash scripts/with-modelport-env.sh <command> [args...]" >&2
  exit 2
fi

exec "$@"
