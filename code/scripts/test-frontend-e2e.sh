#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
FRONTEND_ROOT="$CODE_ROOT/apps/commerce-console"

CONTROL_PORT="${MOYUAN_E2E_CONTROL_PORT:-19190}"
DATA_PORT="${MOYUAN_E2E_DATA_PORT:-18183}"
BASE_URL="http://127.0.0.1:$CONTROL_PORT"
SERVER_LOG="$(mktemp "${TMPDIR:-/tmp}/moyuan-e2e-server.XXXXXX.log")"
SERVER_PID=""

cleanup() {
  if [[ -n "$SERVER_PID" ]]; then
    kill -- "-$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -f -- "$SERVER_LOG"
}
trap cleanup EXIT INT TERM

if curl --noproxy '*' -fsS --max-time 2 "$BASE_URL/health" >/dev/null 2>&1; then
  echo "E2E control-plane port is already in use: $BASE_URL" >&2
  exit 1
fi

echo "[e2e] Starting an isolated offline stack at $BASE_URL"
setsid env \
  MOYUAN_CONTROL_PORT="$CONTROL_PORT" \
  MOYUAN_DATA_PORT="$DATA_PORT" \
  MOYUAN_AGENT_MODEL_MODE=modelport \
  MOYUAN_MODELPORT_API_KEY=must-not-be-used-by-offline-e2e \
  bash "$SCRIPT_DIR/run-sar-agent.sh" --offline >"$SERVER_LOG" 2>&1 &
SERVER_PID="$!"

ready=false
for _ in $(seq 1 240); do
  if curl --noproxy '*' -fsS --max-time 1 "$BASE_URL/health/ready" >/dev/null 2>&1; then
    ready=true
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    break
  fi
  sleep 0.25
done

if [[ "$ready" != true ]]; then
  echo "Offline stack did not become healthy. Server log:" >&2
  tail -n 120 "$SERVER_LOG" >&2
  exit 1
fi

echo "[e2e] Running the engineering-workbench browser journeys"
set +e
(
  cd "$FRONTEND_ROOT"
  env \
    NO_PROXY="127.0.0.1,localhost" \
    no_proxy="127.0.0.1,localhost" \
    MOYUAN_E2E_BASE_URL="$BASE_URL" \
    npx playwright test
)
status="$?"
set -e

if (( status != 0 )); then
  echo "Browser journey failed. Server log:" >&2
  tail -n 120 "$SERVER_LOG" >&2
fi
exit "$status"
