#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
COMPOSE_FILE="$CODE_ROOT/compose.v2.yaml"
PROJECT_NAME="moyuan-v2-smoke-${GITHUB_RUN_ID:-local}-$$"
ARTIFACT_DIR="${MOYUAN_CONTAINER_ARTIFACT_DIR:-$CODE_ROOT/test-results}"

# This smoke test is a deterministic offline gate. Shell variables override any
# developer code/.env values that Docker Compose would otherwise load implicitly.
# Do not forward dormant provider or model credentials into either container.
export MOYUAN_AGENT_MODEL_MODE=replay
export MOYUAN_MODELPORT_API_KEY=
export MOYUAN_RETAIL_DATA_MODE=static
export MOYUAN_RETAIL_DATA_PROVIDER=generic
export MOYUAN_RETAIL_DATA_BASE_URL=
export MOYUAN_RETAIL_DATA_API_KEY=
export MOYUAN_RETAIL_DATA_FALLBACK_ENABLED=false
export MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP=false
export MOYUAN_SHOPIFY_STORE_DOMAIN=
export MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN=

preserve_logs() {
  if ! mkdir -p -- "$ARTIFACT_DIR"; then
    echo "Could not create container diagnostics directory: $ARTIFACT_DIR" >&2
    return 0
  fi
  {
    echo "# docker compose ps"
    docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" ps
    echo
    echo "# docker compose logs"
    docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" logs --no-color
  } >"$ARTIFACT_DIR/v2-containers.log" 2>&1 || true
  echo "Preserved container diagnostics at $ARTIFACT_DIR/v2-containers.log" >&2
}

cleanup() {
  local status="$?"
  set +e
  if (( status != 0 )); then
    preserve_logs
  fi
  docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" \
    down --volumes --remove-orphans >/dev/null 2>&1 || true
  return "$status"
}
trap cleanup EXIT

docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" config --quiet
docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" build
docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" up --detach

ready=false
for _attempt in $(seq 1 60); do
  if docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" \
    exec --no-TTY control-plane node -e \
    "fetch('http://127.0.0.1:19090/health/ready').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))" \
    >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" ps
  docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" logs --no-color
  echo "V2 container topology did not become ready." >&2
  exit 1
fi

docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" \
  exec --no-TTY control-plane node -e '
    const baseUrl = "http://127.0.0.1:19090";
    const registryResponse = await fetch(`${baseUrl}/api/v2/domain-packs`);
    if (!registryResponse.ok) throw new Error(`registry failed: ${registryResponse.status}`);
    const registry = await registryResponse.json();
    const packIds = new Set(registry.packs?.map((pack) => pack.id));
    for (const expected of ["normal-3c-v1", "outdoor-camping-v1"]) {
      if (!packIds.has(expected)) throw new Error(`container image is missing ${expected}`);
    }

    const healthResponse = await fetch(`${baseUrl}/health`);
    if (!healthResponse.ok) throw new Error(`health failed: ${healthResponse.status}`);
    const health = await healthResponse.json();
    const retailSources = health.dataPlane?.retailSources;
    for (const sourceName of ["catalog", "reviews", "pricing"]) {
      const source = retailSources?.[sourceName];
      if (
        source?.configuredMode !== "static" ||
        source?.effectiveSource !== "local_snapshot" ||
        source?.status !== "up" ||
        source?.fallbackActive !== false
      ) {
        throw new Error(`unexpected ${sourceName} source health: ${JSON.stringify(source)}`);
      }
    }

    const verifyRun = async (pack, expectedCategories) => {
      const domainPackId = pack.id;
      const message = pack.exampleQueries?.[0];
      if (!message) throw new Error(`no example query for ${domainPackId}`);
      const created = await fetch(`${baseUrl}/api/v2/runs`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": `container-smoke-${domainPackId}`,
        },
        body: JSON.stringify({ domainPackId, message }),
      });
      if (created.status !== 202) throw new Error(`create failed for ${domainPackId}: ${created.status}`);
      const cookie = created.headers.get("set-cookie")?.split(";", 1)[0];
      if (!cookie) throw new Error("identity cookie missing");
      const { runId } = await created.json();
      for (let attempt = 0; attempt < 100; attempt += 1) {
        const response = await fetch(`${baseUrl}/api/v2/runs/${runId}`, {
          headers: { cookie },
        });
        const run = await response.json();
        if (run.status === "completed") {
          if (run.result?.phase !== "proposal") throw new Error("proposal result missing");
          if (run.domainPackId !== domainPackId || run.workflowId !== pack.workflowId) {
            throw new Error(`Run extension metadata mismatch for ${domainPackId}`);
          }
          if (run.result?.decision?.critique?.verdict !== "approved") {
            throw new Error(`critic did not approve ${domainPackId}`);
          }
          if (expectedCategories) {
            const actual = run.result.decision.bundle.items
              .map((item) => item.product.category)
              .sort();
            if (JSON.stringify(actual) !== JSON.stringify([...expectedCategories].sort())) {
              throw new Error(`bundle category mismatch for ${domainPackId}: ${actual}`);
            }
          }
          return { runId, cookie };
        }
        if (["failed", "cancelled"].includes(run.status)) throw new Error(`run ended as ${run.status}`);
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      throw new Error(`container smoke run timed out for ${domainPackId}`);
    };

    const verifyConfirmation = async (pack, proposal) => {
      const body = JSON.stringify({
        domainPackId: pack.id,
        message: "确认生成购物车草案",
        confirmed: true,
        proposalRunId: proposal.runId,
      });
      const responses = await Promise.all(["a", "b"].map((suffix) => fetch(
        `${baseUrl}/api/v2/runs`,
        {
          method: "POST",
          headers: {
            "content-type": "application/json",
            cookie: proposal.cookie,
            "idempotency-key": `container-confirm-${pack.id}-${suffix}`,
          },
          body,
        },
      )));
      const statuses = responses.map((response) => response.status).sort();
      if (JSON.stringify(statuses) !== JSON.stringify([200, 202])) {
        throw new Error(`confirmation replay statuses mismatch for ${pack.id}: ${statuses}`);
      }
      const runs = await Promise.all(responses.map((response) => response.json()));
      if (runs[0].runId !== runs[1].runId) {
        throw new Error(`duplicate confirmation Runs for ${pack.id}`);
      }
      for (let attempt = 0; attempt < 100; attempt += 1) {
        const response = await fetch(`${baseUrl}/api/v2/runs/${runs[0].runId}`, {
          headers: { cookie: proposal.cookie },
        });
        const run = await response.json();
        if (run.status === "completed") {
          if (run.result?.phase !== "cart_draft" || !run.result?.cartDraft?.draftId) {
            throw new Error(`cart draft result missing for ${pack.id}`);
          }
          return;
        }
        if (["failed", "cancelled"].includes(run.status)) {
          throw new Error(`confirmation ended as ${run.status} for ${pack.id}`);
        }
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      throw new Error(`confirmation smoke timed out for ${pack.id}`);
    };

    const expectedBundles = {
      "normal-3c-v1": ["phone", "headphones", "charger"],
      "outdoor-camping-v1": ["camp_stove", "fuel_canister", "cookware"],
    };
    for (const pack of registry.packs) {
      const proposal = await verifyRun(pack, expectedBundles[pack.id]);
      await verifyConfirmation(pack, proposal);
    }
  '

echo "V2 discovery, control plane and every registered Domain Pack Run passed."
