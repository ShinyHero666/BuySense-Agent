#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
RUN_SCRIPT="$SCRIPT_DIR/run-sar-agent.sh"
MODELPORT_ENV_SCRIPT="$SCRIPT_DIR/with-modelport-env.sh"
COMPOSE_FILE="$CODE_ROOT/compose.v2.yaml"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/moyuan-run-config.XXXXXX")"
TEST_CODE="$TEST_ROOT/code"

cleanup() {
  if [[ "$TEST_ROOT" == *moyuan-run-config.* && -d "$TEST_ROOT" ]]; then
    rm -rf -- "$TEST_ROOT"
  fi
}
trap cleanup EXIT

mkdir -p "$TEST_CODE/scripts"
cp "$RUN_SCRIPT" "$TEST_CODE/scripts/run-sar-agent.sh"
cp "$MODELPORT_ENV_SCRIPT" "$TEST_CODE/scripts/with-modelport-env.sh"
ln -s "$CODE_ROOT/src" "$TEST_CODE/src"

# ModelPort dotenv input is data, not shell. A value that an older `source`
# implementation would execute must remain a literal environment value.
MODELPORT_SENTINEL="$TEST_ROOT/modelport-env-command-ran"
printf 'MOYUAN_MODELPORT_API_KEY="$(touch %s)"\n' "$MODELPORT_SENTINEL" \
  > "$TEST_CODE/modelport-safe.env"
env -u MOYUAN_MODELPORT_API_KEY -u MODELPORT_CLIENT_KEY -u MODELPORT_AUTH_TOKEN \
  EXPECTED_MODELPORT_LITERAL="\$(touch $MODELPORT_SENTINEL)" \
  MODELPORT_ENV_FILE="$TEST_CODE/modelport-safe.env" \
  bash "$TEST_CODE/scripts/with-modelport-env.sh" bash -c \
    '[[ "$MOYUAN_MODELPORT_API_KEY" == "$EXPECTED_MODELPORT_LITERAL" ]]'
if [[ -e "$MODELPORT_SENTINEL" ]]; then
  echo "ModelPort dotenv parsing unexpectedly executed shell code" >&2
  exit 1
fi

printf '%s\n' \
  'MOYUAN_MODELPORT_API_KEY=scoped-test-key' \
  'MOYUAN_MODELPORT_UNKNOWN=typo' > "$TEST_CODE/modelport-unknown.env"
if env -u MOYUAN_MODELPORT_API_KEY -u MODELPORT_CLIENT_KEY -u MODELPORT_AUTH_TOKEN \
  MODELPORT_ENV_FILE="$TEST_CODE/modelport-unknown.env" \
  bash "$TEST_CODE/scripts/with-modelport-env.sh" true >/dev/null 2>&1; then
  echo "ModelPort dotenv parsing unexpectedly accepted an unknown setting" >&2
  exit 1
fi

# Offline is a hard deterministic boundary: even malformed or duplicate retail
# settings in a copied fixture must not be parsed or forwarded.
printf '%s\n' \
  'MOYUAN_RETAIL_DATA_MODE=http' \
  'MOYUAN_RETAIL_DATA_MODE=broken' \
  'MOYUAN_RETAIL_DATA_UNKNOWN=value' > "$TEST_CODE/.env"
env \
  MOYUAN_RETAIL_DATA_MODE=invalid \
  MOYUAN_RETAIL_DATA_PROVIDER=shopify \
  MOYUAN_RETAIL_DATA_BASE_URL=https://must-not-be-used.example \
  MOYUAN_RETAIL_DATA_API_KEY=must-not-be-forwarded \
  MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS=invalid \
  MOYUAN_SHOPIFY_STORE_DOMAIN=must-not-be-used.myshopify.com \
  MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN=must-not-be-forwarded \
  MOYUAN_SHOPIFY_API_VERSION=unstable \
  bash "$TEST_CODE/scripts/run-sar-agent.sh" --offline --check >/dev/null

printf '%s\n' \
  'MOYUAN_RETAIL_DATA_PROVIDER=generic' \
  'MOYUAN_RETAIL_DATA_BASE_URL=https://retail.example' \
  'MOYUAN_RETAIL_DATA_FALLBACK_ENABLED=false' \
  'MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS=2' \
  'MOYUAN_RETAIL_DATA_MAX_RESPONSE_BYTES=1048576' \
  'MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP=false' > "$TEST_CODE/.env"
bash "$TEST_CODE/scripts/run-sar-agent.sh" --http-data --check >/dev/null

# An HTTP stack cannot be reused safely because health deliberately omits its
# credential and configured-fallback identity. Simulate both an existing full
# stack and an independently-running Python data plane without starting either.
FAKE_BIN="$TEST_ROOT/fake-bin"
mkdir -p "$FAKE_BIN"
printf '%s\n' \
  '#!/bin/bash' \
  'url="${!#}"' \
  'if [[ "${FAKE_CURL_MODE:-}" == "local-launch" && "$url" == *:38082/livez ]]; then exit 0; fi' \
  'if [[ "${FAKE_CURL_MODE:-}" == "launch-ready" || "${FAKE_CURL_MODE:-}" == "local-launch" ]]; then' \
  '  case "$url" in' \
  '    *:18083/health/ready)' \
  '      if [[ ! -e "$FAKE_CURL_STATE" ]]; then : > "$FAKE_CURL_STATE"; exit 22; fi' \
  '      printf '\''{"retailSources":{"catalog":{"configuredMode":"%s","effectiveSource":"local_snapshot","providerId":"%s","effectiveProviderId":"%s"},"reviews":{"configuredMode":"%s","effectiveSource":"local_snapshot","providerId":"%s","effectiveProviderId":"%s"},"pricing":{"configuredMode":"%s","effectiveSource":"local_snapshot","providerId":"%s","effectiveProviderId":"%s"}}}\n'\'' "${FAKE_CONFIGURED_MODE:-http}" "$FAKE_PROVIDER_ID" "$FAKE_PROVIDER_ID" "${FAKE_CONFIGURED_MODE:-http}" "$FAKE_PROVIDER_ID" "$FAKE_PROVIDER_ID" "${FAKE_CONFIGURED_MODE:-http}" "$FAKE_PROVIDER_ID" "$FAKE_PROVIDER_ID"' \
  '      exit 0 ;;' \
  '    *) exit 22 ;;' \
  '  esac' \
  'fi' \
  'case "${FAKE_CURL_MODE:-}:$url" in' \
  '  control-ready:*:19090/health/ready) exit 0 ;;' \
  '  data-ready:*:18083/health/ready) exit 0 ;;' \
  '  *) exit 22 ;;' \
  'esac' > "$FAKE_BIN/curl"
printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > "$FAKE_BIN/npm"
chmod +x "$FAKE_BIN/curl" "$FAKE_BIN/npm"

if PATH="$FAKE_BIN:$PATH" FAKE_CURL_MODE=control-ready \
  bash "$TEST_CODE/scripts/run-sar-agent.sh" --http-data \
  > "$TEST_ROOT/control-reuse.log" 2>&1; then
  echo "http-data unexpectedly reused an existing control-plane stack" >&2
  exit 1
fi
if ! grep -Fq "Refusing to reuse an existing stack in HTTP retail-data mode" \
  "$TEST_ROOT/control-reuse.log"; then
  echo "http-data control-plane reuse failure was not explicit" >&2
  exit 1
fi

if PATH="$FAKE_BIN:$PATH" FAKE_CURL_MODE=data-ready \
  bash "$TEST_CODE/scripts/run-sar-agent.sh" --http-data \
  > "$TEST_ROOT/data-reuse.log" 2>&1; then
  echo "http-data unexpectedly reused an existing Python data plane" >&2
  exit 1
fi
if ! grep -Fq "Refusing to reuse an existing Python data plane" \
  "$TEST_ROOT/data-reuse.log"; then
  echo "http-data Python data-plane reuse failure was not explicit" >&2
  exit 1
fi

# Capture the actual Python child environment. Both provider credentials are
# deliberately present in the parent; only the selected provider may survive.
REAL_TEST_PYTHON="$(command -v python3)"
mkdir -p "$TEST_CODE/.venv/bin" "$TEST_CODE/agent-control-plane" \
  "$TEST_CODE/apps/commerce-console"
printf '%s\n' \
  '#!/bin/bash' \
  'if [[ "${1:-}" == "-m" && "${2:-}" == "shoprec.server" ]]; then' \
  '  : > "$FAKE_PYTHON_ENV_LOG"' \
  '  for key in MOYUAN_RETAIL_DATA_MODE MOYUAN_RETAIL_DATA_PROVIDER MOYUAN_RETAIL_DATA_BASE_URL MOYUAN_RETAIL_DATA_API_KEY MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP MOYUAN_SHOPIFY_STORE_DOMAIN MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN MOYUAN_SHOPIFY_API_VERSION MOYUAN_SHOPIFY_CANARY_TIMEOUT_SECONDS MOYUAN_SHOPIFY_CANARY_MAX_RESPONSE_BYTES; do' \
  '    if [[ -v "$key" ]]; then printf "set:%s\\n" "$key"; else printf "unset:%s\\n" "$key"; fi' \
  '  done >> "$FAKE_PYTHON_ENV_LOG"' \
  '  exit 0' \
  'fi' \
  'exec "${REAL_TEST_PYTHON:-python3}" "$@"' > "$TEST_CODE/.venv/bin/python"
chmod +x "$TEST_CODE/.venv/bin/python"

GENERIC_PROVIDER_ID="$(PYTHONPATH="$CODE_ROOT/src:$CODE_ROOT" "$REAL_TEST_PYTHON" -c \
  'from shoprec.retail_data_ports import retail_provider_id; print(retail_provider_id("https://retail.example"))')"
PATH="$FAKE_BIN:$PATH" \
  REAL_TEST_PYTHON="$REAL_TEST_PYTHON" \
  FAKE_CURL_MODE=launch-ready \
  FAKE_CURL_STATE="$TEST_ROOT/generic-curl-state" \
  FAKE_PROVIDER_ID="$GENERIC_PROVIDER_ID" \
  FAKE_PYTHON_ENV_LOG="$TEST_ROOT/generic-python-env.log" \
  MOYUAN_RETAIL_DATA_API_KEY=generic-active-token \
  MOYUAN_SHOPIFY_STORE_DOMAIN=inactive.myshopify.com \
  MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN=inactive-shopify-token \
  MOYUAN_SHOPIFY_API_VERSION=2026-07 \
  MOYUAN_SHOPIFY_CANARY_TIMEOUT_SECONDS=9 \
  MOYUAN_SHOPIFY_CANARY_MAX_RESPONSE_BYTES=65536 \
  bash "$TEST_CODE/scripts/run-sar-agent.sh" --http-data >/dev/null
grep -Fqx 'set:MOYUAN_RETAIL_DATA_API_KEY' "$TEST_ROOT/generic-python-env.log"
grep -Fqx 'set:MOYUAN_RETAIL_DATA_BASE_URL' "$TEST_ROOT/generic-python-env.log"
for inactive_key in \
  MOYUAN_SHOPIFY_STORE_DOMAIN \
  MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN \
  MOYUAN_SHOPIFY_API_VERSION \
  MOYUAN_SHOPIFY_CANARY_TIMEOUT_SECONDS \
  MOYUAN_SHOPIFY_CANARY_MAX_RESPONSE_BYTES; do
  grep -Fqx "unset:$inactive_key" "$TEST_ROOT/generic-python-env.log"
done

# Static mode never needs either provider credential, even when a stale provider
# selector and both secret families remain in the parent environment.
PATH="$FAKE_BIN:$PATH" \
  REAL_TEST_PYTHON="$REAL_TEST_PYTHON" \
  FAKE_CURL_MODE=local-launch \
  FAKE_CURL_STATE="$TEST_ROOT/static-curl-state" \
  FAKE_CONFIGURED_MODE=static \
  FAKE_PROVIDER_ID=normal-3c-v1 \
  FAKE_PYTHON_ENV_LOG="$TEST_ROOT/static-python-env.log" \
  MOYUAN_MODELPORT_API_KEY=scoped-modelport-test-key \
  MOYUAN_RETAIL_DATA_MODE=static \
  MOYUAN_RETAIL_DATA_PROVIDER=shopify \
  MOYUAN_RETAIL_DATA_BASE_URL=https://inactive-retail.example \
  MOYUAN_RETAIL_DATA_API_KEY=inactive-generic-token \
  MOYUAN_SHOPIFY_STORE_DOMAIN=inactive.myshopify.com \
  MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN=inactive-shopify-token \
  MOYUAN_SHOPIFY_API_VERSION=2026-07 \
  bash "$TEST_CODE/scripts/run-sar-agent.sh" --local-qwen >/dev/null
for inactive_key in \
  MOYUAN_RETAIL_DATA_BASE_URL \
  MOYUAN_RETAIL_DATA_API_KEY \
  MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP \
  MOYUAN_SHOPIFY_STORE_DOMAIN \
  MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN \
  MOYUAN_SHOPIFY_API_VERSION \
  MOYUAN_SHOPIFY_CANARY_TIMEOUT_SECONDS \
  MOYUAN_SHOPIFY_CANARY_MAX_RESPONSE_BYTES; do
  grep -Fqx "unset:$inactive_key" "$TEST_ROOT/static-python-env.log"
done

if env MOYUAN_RETAIL_DATA_BASE_URL= \
  bash "$TEST_CODE/scripts/run-sar-agent.sh" --http-data --check >/dev/null 2>&1; then
  echo "http-data preflight unexpectedly accepted an empty provider base URL" >&2
  exit 1
fi

if env MOYUAN_RETAIL_DATA_BASE_URL=http://retail.example \
  bash "$TEST_CODE/scripts/run-sar-agent.sh" --http-data --check >/dev/null 2>&1; then
  echo "http-data preflight unexpectedly accepted insecure non-loopback HTTP" >&2
  exit 1
fi

printf '%s\n' \
  'MOYUAN_RETAIL_DATA_PROVIDER=shopify' \
  'MOYUAN_RETAIL_DATA_FALLBACK_ENABLED=false' \
  'MOYUAN_RETAIL_DATA_TIMEOUT_SECONDS=2' \
  'MOYUAN_RETAIL_DATA_MAX_RESPONSE_BYTES=1048576' \
  'MOYUAN_SHOPIFY_STORE_DOMAIN=moyuan-test.myshopify.com' \
  'MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN=shpat_test_read_only_token' \
  'MOYUAN_SHOPIFY_API_VERSION=2026-07' > "$TEST_CODE/.env"
bash "$TEST_CODE/scripts/run-sar-agent.sh" --http-data --check >/dev/null

SHOPIFY_PROVIDER_ID="$(PYTHONPATH="$CODE_ROOT/src:$CODE_ROOT" "$REAL_TEST_PYTHON" -c \
  'from shoprec.shopify_retail_provider import shopify_provider_id; print(shopify_provider_id("moyuan-test.myshopify.com", "2026-07"))')"
PATH="$FAKE_BIN:$PATH" \
  REAL_TEST_PYTHON="$REAL_TEST_PYTHON" \
  FAKE_CURL_MODE=launch-ready \
  FAKE_CURL_STATE="$TEST_ROOT/shopify-curl-state" \
  FAKE_PROVIDER_ID="$SHOPIFY_PROVIDER_ID" \
  FAKE_PYTHON_ENV_LOG="$TEST_ROOT/shopify-python-env.log" \
  MOYUAN_RETAIL_DATA_BASE_URL=https://inactive-retail.example \
  MOYUAN_RETAIL_DATA_API_KEY=inactive-generic-token \
  MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP=true \
  bash "$TEST_CODE/scripts/run-sar-agent.sh" --http-data >/dev/null
grep -Fqx 'set:MOYUAN_SHOPIFY_STORE_DOMAIN' "$TEST_ROOT/shopify-python-env.log"
grep -Fqx 'set:MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN' "$TEST_ROOT/shopify-python-env.log"
grep -Fqx 'set:MOYUAN_SHOPIFY_API_VERSION' "$TEST_ROOT/shopify-python-env.log"
for inactive_key in \
  MOYUAN_RETAIL_DATA_BASE_URL \
  MOYUAN_RETAIL_DATA_API_KEY \
  MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP \
  MOYUAN_SHOPIFY_CANARY_TIMEOUT_SECONDS \
  MOYUAN_SHOPIFY_CANARY_MAX_RESPONSE_BYTES; do
  grep -Fqx "unset:$inactive_key" "$TEST_ROOT/shopify-python-env.log"
done

if env MOYUAN_SHOPIFY_STORE_DOMAIN=https://evil.example/path \
  bash "$TEST_CODE/scripts/run-sar-agent.sh" --http-data --check >/dev/null 2>&1; then
  echo "shopify preflight unexpectedly accepted an invalid store domain" >&2
  exit 1
fi

if env MOYUAN_SHOPIFY_API_VERSION=unstable \
  bash "$TEST_CODE/scripts/run-sar-agent.sh" --http-data --check >/dev/null 2>&1; then
  echo "shopify preflight unexpectedly accepted an unsupported API version" >&2
  exit 1
fi

if grep -Eq \
  'MOYUAN_(MODELPORT_API_KEY|RETAIL_DATA_API_KEY|SHOPIFY_ADMIN_ACCESS_TOKEN|SHOPIFY_STORE_DOMAIN|RETAIL_DATA_BASE_URL)' \
  "$COMPOSE_FILE"; then
  echo "base V2 Compose topology unexpectedly accepts provider credentials" >&2
  exit 1
fi
grep -Fq 'MOYUAN_RETAIL_DATA_MODE: static' "$COMPOSE_FILE"
grep -Fq 'MOYUAN_RETAIL_DATA_PROVIDER: generic' "$COMPOSE_FILE"

echo "run-sar-agent configuration checks passed"
