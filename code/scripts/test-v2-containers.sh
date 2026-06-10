#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
COMPOSE_FILE="$CODE_ROOT/compose.v2.yaml"
PROJECT_NAME="moyuan-v2-smoke-${GITHUB_RUN_ID:-local}-$$"

cleanup() {
  docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" \
    down --volumes --remove-orphans >/dev/null 2>&1 || true
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
    const created = await fetch("http://127.0.0.1:19090/api/v2/runs", {
      method: "POST",
      headers: { "content-type": "application/json", "idempotency-key": "container-smoke" },
      body: JSON.stringify({ message: "预算7000元，选手机并搭配耳机和充电器" }),
    });
    if (created.status !== 202) throw new Error(`create failed: ${created.status}`);
    const cookie = created.headers.get("set-cookie")?.split(";", 1)[0];
    if (!cookie) throw new Error("identity cookie missing");
    const { runId } = await created.json();
    for (let attempt = 0; attempt < 100; attempt += 1) {
      const response = await fetch(`http://127.0.0.1:19090/api/v2/runs/${runId}`, {
        headers: { cookie },
      });
      const run = await response.json();
      if (run.status === "completed") {
        if (run.result?.phase !== "proposal") throw new Error("proposal result missing");
        process.exit(0);
      }
      if (["failed", "cancelled"].includes(run.status)) throw new Error(`run ended as ${run.status}`);
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    throw new Error("container smoke run timed out");
  '

echo "V2 discovery, control plane and asynchronous Run smoke passed."
