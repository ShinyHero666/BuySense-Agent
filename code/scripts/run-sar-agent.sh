#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
CONTROL_ROOT="$CODE_ROOT/agent-control-plane"
FRONTEND_ROOT="$CODE_ROOT/apps/commerce-console"

MODE="offline"
CHECK_ONLY=false

RETAIL_ENV_KEYS=(
  MOYUAN_RETAIL_DATA_MODE
  MOYUAN_RETAIL_DATA_PROVIDER
  MOYUAN_RETAIL_DATA_BASE_URL
  MOYUAN_RETAIL_DATA_API_KEY
  MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS
  MOYUAN_RETAIL_DATA_MAX_RESPONSE_BYTES
  MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP
  MOYUAN_RETAIL_DATA_FALLBACK_ENABLED
  MOYUAN_SHOPIFY_STORE_DOMAIN
  MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN
  MOYUAN_SHOPIFY_API_VERSION
  MOYUAN_SHOPIFY_CANARY_TIMEOUT_SECONDS
  MOYUAN_SHOPIFY_CANARY_MAX_RESPONSE_BYTES
)
RETAIL_SERVICE_COMMON_ENV_KEYS=(
  MOYUAN_RETAIL_DATA_MODE
  MOYUAN_RETAIL_DATA_PROVIDER
  MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS
  MOYUAN_RETAIL_DATA_MAX_RESPONSE_BYTES
  MOYUAN_RETAIL_DATA_FALLBACK_ENABLED
)
GENERIC_RETAIL_SERVICE_ENV_KEYS=(
  MOYUAN_RETAIL_DATA_BASE_URL
  MOYUAN_RETAIL_DATA_API_KEY
  MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP
)
SHOPIFY_RETAIL_SERVICE_ENV_KEYS=(
  MOYUAN_SHOPIFY_STORE_DOMAIN
  MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN
  MOYUAN_SHOPIFY_API_VERSION
)

is_retail_env_key() {
  local candidate="$1"
  local allowed
  for allowed in "${RETAIL_ENV_KEYS[@]}"; do
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

load_retail_env() {
  local env_file="$CODE_ROOT/.env"
  local line key value
  declare -A seen=()
  [[ -f "$env_file" ]] || return 0

  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    if [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]]; then
      continue
    fi
    if [[ ! "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      if [[ "$line" =~ MOYUAN_RETAIL_DATA_ || "$line" =~ MOYUAN_SHOPIFY_ ]]; then
        echo "Malformed retail data setting in $env_file" >&2
        exit 2
      fi
      continue
    fi
    key="${BASH_REMATCH[1]}"
    value="$(trim_whitespace "${BASH_REMATCH[2]}")"
    if [[ ( "$key" == MOYUAN_RETAIL_DATA_* || "$key" == MOYUAN_SHOPIFY_* ) ]] &&
       ! is_retail_env_key "$key"; then
      echo "Unsupported retail data setting in $env_file: $key" >&2
      exit 2
    fi
    is_retail_env_key "$key" || continue
    if [[ -n "${seen[$key]:-}" ]]; then
      echo "Duplicate retail data setting in $env_file: $key" >&2
      exit 2
    fi
    seen[$key]=1
    if [[ "$value" == \"* || "$value" == \'* ]]; then
      if [[ ${#value} -lt 2 || "${value: -1}" != "${value:0:1}" ]]; then
        echo "Unterminated quoted retail data setting in $env_file: $key" >&2
        exit 2
      fi
      value="${value:1:${#value}-2}"
    elif [[ "$value" =~ [[:space:]] ]]; then
      echo "Unquoted whitespace in retail data setting in $env_file: $key" >&2
      exit 2
    fi
    if [[ -z "${!key+x}" ]]; then
      printf -v "$key" '%s' "$value"
    fi
  done < "$env_file"
}

validate_retail_env() {
  local retail_mode="${MOYUAN_RETAIL_DATA_MODE:-static}"
  local retail_provider="${MOYUAN_RETAIL_DATA_PROVIDER:-generic}"
  local fallback_enabled="${MOYUAN_RETAIL_DATA_FALLBACK_ENABLED:-false}"
  local allow_insecure_http="${MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP:-false}"
  local timeout_seconds="${MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS:-2}"
  local max_response_bytes="${MOYUAN_RETAIL_DATA_MAX_RESPONSE_BYTES:-1048576}"
  local api_key="${MOYUAN_RETAIL_DATA_API_KEY:-}"
  local shopify_domain="${MOYUAN_SHOPIFY_STORE_DOMAIN:-}"
  local shopify_token="${MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN:-}"
  local shopify_version="${MOYUAN_SHOPIFY_API_VERSION:-2026-07}"
  if [[ "$retail_mode" != "static" && "$retail_mode" != "http" ]]; then
    echo "MOYUAN_RETAIL_DATA_MODE must be static or http" >&2
    exit 2
  fi
  if [[ "$retail_provider" != "generic" && "$retail_provider" != "shopify" ]]; then
    echo "MOYUAN_RETAIL_DATA_PROVIDER must be generic or shopify" >&2
    exit 2
  fi
  if [[ "$retail_mode" == "http" && "$retail_provider" == "generic" &&
        -z "${MOYUAN_RETAIL_DATA_BASE_URL:-}" ]]; then
    echo "MOYUAN_RETAIL_DATA_BASE_URL is required when MOYUAN_RETAIL_DATA_MODE=http" >&2
    exit 2
  fi
  if (( ${#api_key} > 4096 )) || [[ "$api_key" == *$'\n'* || "$api_key" == *$'\r'* ]]; then
    echo "MOYUAN_RETAIL_DATA_API_KEY contains invalid characters or is too long" >&2
    exit 2
  fi
  if [[ "$retail_mode" == "http" && "$retail_provider" == "shopify" ]]; then
    if [[ ! "$shopify_domain" =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.myshopify\.com$ ]]; then
      echo "MOYUAN_SHOPIFY_STORE_DOMAIN must be a lowercase *.myshopify.com domain" >&2
      exit 2
    fi
    if [[ -z "$shopify_token" || "$shopify_token" == replace-* ||
          ${#shopify_token} -gt 4096 || "$shopify_token" == *$'\n'* ||
          "$shopify_token" == *$'\r'* ]]; then
      echo "MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN is missing or invalid" >&2
      exit 2
    fi
    if [[ "$shopify_version" != "2026-07" ]]; then
      echo "MOYUAN_SHOPIFY_API_VERSION must equal 2026-07" >&2
      exit 2
    fi
  fi
  if [[ "$fallback_enabled" != "true" && "$fallback_enabled" != "false" ]]; then
    echo "MOYUAN_RETAIL_DATA_FALLBACK_ENABLED must be true or false" >&2
    exit 2
  fi
  if [[ "$allow_insecure_http" != "true" && "$allow_insecure_http" != "false" ]]; then
    echo "MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP must be true or false" >&2
    exit 2
  fi
  if [[ ! "$timeout_seconds" =~ ^[0-9]+([.][0-9]+)?$ ]] ||
     ! awk -v value="$timeout_seconds" 'BEGIN { exit !(value >= 0.05 && value <= 10) }'; then
    echo "MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS must be between 0.05 and 10" >&2
    exit 2
  fi
  if [[ ! "$max_response_bytes" =~ ^[0-9]+$ ]] ||
     ! awk -v value="$max_response_bytes" 'BEGIN { exit !(value >= 1024 && value <= 4194304) }'; then
    echo "MOYUAN_RETAIL_DATA_MAX_RESPONSE_BYTES must be between 1024 and 4194304" >&2
    exit 2
  fi
}

usage() {
  cat <<'EOF'
usage: bash scripts/run-sar-agent.sh [--offline|--http-data|--local-qwen] [--check]

  --offline      Pi Replay + deterministic static retail snapshots; no upstream HTTP
  --http-data    Pi Replay + configured retail catalog/price/review HTTP provider
  --local-qwen   Pi Agent + ModelPort + local Qwen; retail provider follows configuration
  --check        Check prerequisites and retail configuration without starting anything
EOF
}

while (( $# > 0 )); do
  case "$1" in
    --offline) MODE="offline" ;;
    --http-data) MODE="http-data" ;;
    --local-qwen) MODE="local-qwen" ;;
    --check) CHECK_ONLY=true ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

for command_name in awk bash curl node npm python3; do
  require_command "$command_name"
done

if [[ "$MODE" == "offline" ]]; then
  for retail_key in "${RETAIL_ENV_KEYS[@]}"; do
    unset "$retail_key"
  done
  MOYUAN_RETAIL_DATA_MODE=static
  MOYUAN_RETAIL_DATA_PROVIDER=generic
  MOYUAN_RETAIL_DATA_FALLBACK_ENABLED=false
else
  load_retail_env
  if [[ "$MODE" == "http-data" ]]; then
    MOYUAN_RETAIL_DATA_MODE=http
  fi
fi
validate_retail_env

RETAIL_ENV_UNSET=()
for retail_key in "${RETAIL_ENV_KEYS[@]}"; do
  RETAIL_ENV_UNSET+=(-u "$retail_key")
done

python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else "Python 3.10 or newer is required")'
node -e 'const [major, minor] = process.versions.node.split(".").map(Number); if (major < 22 || (major === 22 && minor < 19)) { console.error("Node.js 22.19 or newer is required"); process.exit(1); }'

PYTHON="$CODE_ROOT/.venv/bin/python"
if [[ ! -x "$PYTHON" ]]; then
  PYTHON="$(command -v python3)"
fi

EXPECTED_RETAIL_PROVIDER_ID=""
if [[ "${MOYUAN_RETAIL_DATA_MODE:-static}" == "http" ]]; then
  if [[ "${MOYUAN_RETAIL_DATA_PROVIDER:-generic}" == "shopify" ]]; then
    if ! EXPECTED_RETAIL_PROVIDER_ID="$(
      PYTHONPATH="$CODE_ROOT/src:$CODE_ROOT" "$PYTHON" -c '
import sys
from shoprec.shopify_retail_provider import shopify_provider_id

try:
    print(shopify_provider_id(sys.argv[1], sys.argv[2]))
except (TypeError, ValueError) as error:
    raise SystemExit(str(error)) from None
' "${MOYUAN_SHOPIFY_STORE_DOMAIN}" "${MOYUAN_SHOPIFY_API_VERSION:-2026-07}"
    )"; then
      echo "Invalid Shopify provider configuration; check the store domain and API version." >&2
      exit 2
    fi
  elif ! EXPECTED_RETAIL_PROVIDER_ID="$(
      PYTHONPATH="$CODE_ROOT/src:$CODE_ROOT" "$PYTHON" -c '
import sys
from shoprec.retail_data_ports import JsonHttpRetailClient, retail_provider_id

base_url, timeout, max_bytes, allow_insecure = sys.argv[1:]
try:
    JsonHttpRetailClient(
        base_url,
        timeout_seconds=float(timeout),
        max_response_bytes=int(max_bytes),
        allow_insecure_http=allow_insecure == "true",
    )
    print(retail_provider_id(base_url))
except (TypeError, ValueError) as error:
    raise SystemExit(str(error)) from None
' \
      "${MOYUAN_RETAIL_DATA_BASE_URL}" \
      "${MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS:-2}" \
      "${MOYUAN_RETAIL_DATA_MAX_RESPONSE_BYTES:-1048576}" \
      "${MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP:-false}"
    )"; then
    echo "Invalid retail HTTP provider configuration; check the base URL and bounded client settings." >&2
    exit 2
  fi
fi

DATA_HOST="${MOYUAN_DATA_HOST:-127.0.0.1}"
DATA_PORT="${MOYUAN_DATA_PORT:-18083}"
CONTROL_HOST="${MOYUAN_CONTROL_HOST:-127.0.0.1}"
CONTROL_PORT="${MOYUAN_CONTROL_PORT:-19090}"
DATA_URL="http://$DATA_HOST:$DATA_PORT"
CONTROL_URL="http://$CONTROL_HOST:$CONTROL_PORT"
DATA_PID=""

check_retail_health_mode() {
  local endpoint="$1"
  local expected_mode="${MOYUAN_RETAIL_DATA_MODE:-static}"
  local payload result
  if ! payload="$(curl --noproxy '*' -fsS --max-time 2 "$endpoint")"; then
    echo "Could not read retail source health from $endpoint" >&2
    return 1
  fi
  if ! result="$(printf '%s' "$payload" | "$PYTHON" -c '
import json
import re
import sys

expected = sys.argv[1]
expected_provider = sys.argv[2] or None
try:
    health = json.load(sys.stdin)
except (json.JSONDecodeError, UnicodeDecodeError):
    print("invalid health response")
    raise SystemExit(1)

sources = health.get("retailSources")
if not isinstance(sources, dict):
    data_plane = health.get("dataPlane")
    if isinstance(data_plane, dict):
        sources = data_plane.get("retailSources")
components = ("catalog", "reviews", "pricing")
if not isinstance(sources, dict):
    print("retailSources missing")
    raise SystemExit(1)

summary = []
matches = True
safe_identifier = re.compile(r"^[a-z][a-z0-9._-]{0,63}$")
for component in components:
    source = sources.get(component)
    if not isinstance(source, dict):
        summary.append(f"{component}=missing")
        matches = False
        continue
    configured = source.get("configuredMode")
    effective = source.get("effectiveSource", "unknown")
    raw_provider = source.get("providerId")
    raw_effective_provider = source.get("effectiveProviderId")
    provider = (
        raw_provider
        if isinstance(raw_provider, str) and safe_identifier.fullmatch(raw_provider)
        else "none" if raw_provider is None else "invalid"
    )
    effective_provider = (
        raw_effective_provider
        if isinstance(raw_effective_provider, str)
        and safe_identifier.fullmatch(raw_effective_provider)
        else "none" if raw_effective_provider is None else "invalid"
    )
    summary.append(
        f"{component}={configured}/{effective} "
        f"(provider={provider}, effective-provider={effective_provider})"
    )
    matches = matches and configured == expected
    matches = matches and effective in {
        "local_snapshot", "remote_provider", "mixed", "unavailable"
    }
    matches = matches and provider != "invalid" and effective_provider != "invalid"
    if expected_provider is not None:
        matches = matches and provider == expected_provider

print(", ".join(summary))
raise SystemExit(0 if matches else 1)
' "$expected_mode" "$EXPECTED_RETAIL_PROVIDER_ID")"; then
    echo "Existing service retail configuration does not match the requested '$expected_mode' configuration: $result" >&2
    return 1
  fi
  printf '  retail sources: %s\n' "$result"
}

if [[ "$MODE" == "local-qwen" ]]; then
  bash "$SCRIPT_DIR/with-modelport-env.sh" bash -c \
    'curl --noproxy "*" -fsS --max-time 3 "${MOYUAN_MODELPORT_HEALTH_URL:-http://127.0.0.1:38082/livez}" >/dev/null' || {
    echo "ModelPort is not healthy at the configured health URL." >&2
    echo "Run with --offline first, or see ../docs/TROUBLESHOOTING.md." >&2
    exit 1
  }
fi

printf 'Moyuan SAR Agent preflight passed\n'
printf '  mode: %s\n' "$MODE"
printf '  retail data: %s/%s (fallback=%s)\n' \
  "${MOYUAN_RETAIL_DATA_MODE:-static}" \
  "${MOYUAN_RETAIL_DATA_PROVIDER:-generic}" \
  "${MOYUAN_RETAIL_DATA_FALLBACK_ENABLED:-false}"
printf '  Python: %s\n' "$($PYTHON --version 2>&1)"
printf '  Node: %s\n' "$(node --version)"
printf '  control dependencies: %s\n' "$([[ -d "$CONTROL_ROOT/node_modules" ]] && echo present || echo will-install)"
printf '  frontend dependencies: %s\n' "$([[ -d "$FRONTEND_ROOT/node_modules" ]] && echo present || echo will-install)"

if [[ "$CHECK_ONLY" == true ]]; then
  exit 0
fi

if curl --noproxy '*' -fsS --max-time 2 "$CONTROL_URL/health/ready" >/dev/null 2>&1; then
  if [[ "${MOYUAN_RETAIL_DATA_MODE:-static}" == "http" ]]; then
    echo "Refusing to reuse an existing stack in HTTP retail-data mode." >&2
    echo "Provider credentials and fallback policy are intentionally absent from health; stop the existing stack and restart it with the requested configuration." >&2
    exit 1
  fi
  check_retail_health_mode "$CONTROL_URL/health" || {
    echo "Stop the existing stack or rerun with its configured retail data mode; no process was changed." >&2
    exit 1
  }
  echo "Moyuan SAR Agent is already running: $CONTROL_URL/"
  exit 0
fi
if curl --noproxy '*' -fsS --max-time 2 "$CONTROL_URL/health/live" >/dev/null 2>&1; then
  echo "Moyuan SAR Agent is running but not ready at $CONTROL_URL" >&2
  exit 1
fi

ensure_node_workspace() {
  local workspace="$1"
  local label="$2"
  if [[ ! -d "$workspace/node_modules" ]]; then
    echo "[setup] Installing $label dependencies from the committed lockfile"
    npm --prefix "$workspace" ci --ignore-scripts --no-audit --no-fund
  fi
}

ensure_node_workspace "$CONTROL_ROOT" "Pi control-plane"
ensure_node_workspace "$FRONTEND_ROOT" "React console"
npm --prefix "$FRONTEND_ROOT" run build

cleanup() {
  if [[ -n "$DATA_PID" ]]; then
    kill "$DATA_PID" 2>/dev/null || true
    wait "$DATA_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

if curl --noproxy '*' -fsS --max-time 2 "$DATA_URL/health/ready" >/dev/null 2>&1; then
  if [[ "${MOYUAN_RETAIL_DATA_MODE:-static}" == "http" ]]; then
    echo "Refusing to reuse an existing Python data plane in HTTP retail-data mode." >&2
    echo "Stop that process and restart it so provider credentials and fallback policy cannot remain stale." >&2
    exit 1
  fi
else
  if curl --noproxy '*' -fsS --max-time 2 "$DATA_URL/health/live" >/dev/null 2>&1; then
    echo "Python data plane is running but not ready at $DATA_URL" >&2
    exit 1
  fi
  echo "[start] Starting Python search/recommendation/ads data plane at $DATA_URL"
  RETAIL_SERVICE_ENV_KEYS=("${RETAIL_SERVICE_COMMON_ENV_KEYS[@]}")
  if [[ "${MOYUAN_RETAIL_DATA_MODE:-static}" == "http" ]]; then
    if [[ "${MOYUAN_RETAIL_DATA_PROVIDER:-generic}" == "shopify" ]]; then
      RETAIL_SERVICE_ENV_KEYS+=("${SHOPIFY_RETAIL_SERVICE_ENV_KEYS[@]}")
    else
      RETAIL_SERVICE_ENV_KEYS+=("${GENERIC_RETAIL_SERVICE_ENV_KEYS[@]}")
    fi
  fi
  (
    for retail_key in "${RETAIL_ENV_KEYS[@]}"; do
      keep_retail_key=false
      for service_key in "${RETAIL_SERVICE_ENV_KEYS[@]}"; do
        if [[ "$retail_key" == "$service_key" ]]; then
          keep_retail_key=true
          break
        fi
      done
      if [[ "$keep_retail_key" == false ]]; then
        unset "$retail_key"
      fi
    done
    for retail_key in "${RETAIL_SERVICE_ENV_KEYS[@]}"; do
      if [[ -n "${!retail_key+x}" ]]; then
        export "$retail_key"
      else
        unset "$retail_key"
      fi
    done
    unset MOYUAN_MODELPORT_API_KEY MODELPORT_CLIENT_KEY MODELPORT_AUTH_TOKEN
    export PYTHONPATH="$CODE_ROOT/src:$CODE_ROOT"
    exec "$PYTHON" -m shoprec.server --host "$DATA_HOST" --port "$DATA_PORT" \
      --agent-model-mode replay
  ) &
  DATA_PID="$!"
  for _ in $(seq 1 40); do
    if curl --noproxy '*' -fsS --max-time 1 "$DATA_URL/health/ready" >/dev/null 2>&1; then
      break
    fi
    sleep 0.25
  done
fi

curl --noproxy '*' -fsS --max-time 2 "$DATA_URL/health/ready" >/dev/null || {
  echo "Python data plane did not become healthy at $DATA_URL" >&2
  exit 1
}
check_retail_health_mode "$DATA_URL/health/ready" || {
  echo "Refusing to reuse a Python data plane with a different retail configuration; no existing process was changed." >&2
  exit 1
}

mkdir -p "$CODE_ROOT/.runtime"
echo "[start] Opening the decision console at $CONTROL_URL/"
echo "[start] Press Ctrl+C to stop"
cd "$CONTROL_ROOT"

COMMON_ENV=(
  MOYUAN_DISCOVERY_MODE=python
  MOYUAN_DISCOVERY_BASE_URL="$DATA_URL"
  MOYUAN_CONTROL_HOST="$CONTROL_HOST"
  MOYUAN_CONTROL_PORT="$CONTROL_PORT"
  MOYUAN_DATABASE_PATH="$CODE_ROOT/.runtime/commerce-agent-$MODE.sqlite"
)

if [[ "$MODE" == "local-qwen" ]]; then
  bash "$SCRIPT_DIR/with-modelport-env.sh" env "${RETAIL_ENV_UNSET[@]}" \
    MOYUAN_AGENT_MODEL_MODE=modelport \
    "${COMMON_ENV[@]}" \
    npm run server
else
  env -u MOYUAN_MODELPORT_API_KEY -u MODELPORT_CLIENT_KEY -u MODELPORT_AUTH_TOKEN \
    "${RETAIL_ENV_UNSET[@]}" MOYUAN_AGENT_MODEL_MODE=replay \
    "${COMMON_ENV[@]}" \
    npm run server
fi
