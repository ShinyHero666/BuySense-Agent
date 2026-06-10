#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
CONTROL_ROOT="$CODE_ROOT/agent-control-plane"
FRONTEND_ROOT="$CODE_ROOT/apps/commerce-console"

MODE="offline"
CHECK_ONLY=false

usage() {
  cat <<'EOF'
usage: bash scripts/run-sar-agent.sh [--offline|--local-qwen] [--check]

  --offline      Pi Replay + Python algorithms; no model, GPU or key required
  --local-qwen   Pi Agent + ModelPort + local Qwen
  --check        Check prerequisites without installing or starting anything
EOF
}

while (( $# > 0 )); do
  case "$1" in
    --offline) MODE="offline" ;;
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

for command_name in bash curl node npm python3; do
  require_command "$command_name"
done

python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else "Python 3.10 or newer is required")'
node -e 'const [major, minor] = process.versions.node.split(".").map(Number); if (major < 22 || (major === 22 && minor < 19)) { console.error("Node.js 22.19 or newer is required"); process.exit(1); }'

PYTHON="$CODE_ROOT/.venv/bin/python"
if [[ ! -x "$PYTHON" ]]; then
  PYTHON="$(command -v python3)"
fi

DATA_HOST="${MOYUAN_DATA_HOST:-127.0.0.1}"
DATA_PORT="${MOYUAN_DATA_PORT:-18083}"
CONTROL_HOST="${MOYUAN_CONTROL_HOST:-127.0.0.1}"
CONTROL_PORT="${MOYUAN_CONTROL_PORT:-19090}"
DATA_URL="http://$DATA_HOST:$DATA_PORT"
CONTROL_URL="http://$CONTROL_HOST:$CONTROL_PORT"
DATA_PID=""

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
printf '  Python: %s\n' "$($PYTHON --version 2>&1)"
printf '  Node: %s\n' "$(node --version)"
printf '  control dependencies: %s\n' "$([[ -d "$CONTROL_ROOT/node_modules" ]] && echo present || echo will-install)"
printf '  frontend dependencies: %s\n' "$([[ -d "$FRONTEND_ROOT/node_modules" ]] && echo present || echo will-install)"

if [[ "$CHECK_ONLY" == true ]]; then
  exit 0
fi

if curl --noproxy '*' -fsS --max-time 2 "$CONTROL_URL/health" >/dev/null 2>&1; then
  echo "Moyuan SAR Agent is already running: $CONTROL_URL/"
  exit 0
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

if ! curl --noproxy '*' -fsS --max-time 2 "$DATA_URL/health" >/dev/null 2>&1; then
  echo "[start] Starting Python search/recommendation/ads data plane at $DATA_URL"
  env PYTHONPATH="$CODE_ROOT/src:$CODE_ROOT" \
    "$PYTHON" -m shoprec.server --host "$DATA_HOST" --port "$DATA_PORT" &
  DATA_PID="$!"
  for _ in $(seq 1 40); do
    if curl --noproxy '*' -fsS --max-time 1 "$DATA_URL/health" >/dev/null 2>&1; then
      break
    fi
    sleep 0.25
  done
fi

curl --noproxy '*' -fsS --max-time 2 "$DATA_URL/health" >/dev/null || {
  echo "Python data plane did not become healthy at $DATA_URL" >&2
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
  bash "$SCRIPT_DIR/with-modelport-env.sh" env \
    MOYUAN_AGENT_MODEL_MODE=modelport \
    "${COMMON_ENV[@]}" \
    npm run server
else
  env MOYUAN_AGENT_MODEL_MODE=replay \
    "${COMMON_ENV[@]}" \
    npm run server
fi
