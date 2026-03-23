import assert from "node:assert/strict";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import test from "node:test";
import { SearchAdsRecsBuyerAgent } from "../src/buyer-agent.js";
import {
  ReplayPiRuntimeFactory,
  type PiRuntimeProbe,
} from "../src/pi-runtime.js";
import { buildControlPlaneServer } from "../src/server.js";
import { CommerceRepository } from "../src/v2-repository.js";

class UnavailableOptionalModelRuntime extends ReplayPiRuntimeFactory {
  override async probe(): Promise<PiRuntimeProbe> {
    throw new Error("simulated model dependency outage");
  }
}

class SlowOptionalModelRuntime extends ReplayPiRuntimeFactory {
  probeCalls = 0;

  override async probe(): Promise<PiRuntimeProbe> {
    this.probeCalls += 1;
    await new Promise((resolve) => setTimeout(resolve, 1_500));
    return { ...this.describe(), status: "down", latencyMs: 1_500, error: "slow" };
  }
}

async function post(baseUrl: string, payload: unknown): Promise<Response> {
  return fetch(`${baseUrl}/api/v1/agent`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });
}

function retailSourceHealth(
  configuredMode: "static" | "http" | "mixed",
  effectiveSource: "local_snapshot" | "remote_provider" | "unavailable" | "mixed",
  status: "up" | "degraded" | "down",
  fallbackActive: boolean,
  providerId: string | null,
  effectiveProviderId: string | null,
) {
  return {
    configuredMode,
    effectiveSource,
    status,
    fallbackActive,
    version: status === "down" ? null : "snapshot-v1",
    providerId,
    effectiveProviderId,
    ...(status === "up" ? {} : { lastErrorCode: "provider_timeout" }),
    telemetry: {
      requests: status === "up" ? 1 : 2,
      errors: status === "up" ? 0 : 1,
      fallbacks: fallbackActive ? 1 : 0,
    },
  };
}

function pythonRetailHealthPayload() {
  return {
    status: "UP",
    ready: true,
    upstreamUrl: "https://user:secret@provider.invalid/private",
    apiToken: "must-not-cross-the-control-plane",
    retailSources: {
      catalog: retailSourceHealth(
        "http",
        "local_snapshot",
        "degraded",
        true,
        "retail-a1b2c3",
        "normal-3c-v1",
      ),
      reviews: retailSourceHealth(
        "http",
        "remote_provider",
        "up",
        false,
        "retail-a1b2c3",
        "retail-a1b2c3",
      ),
      pricing: retailSourceHealth(
        "mixed",
        "mixed",
        "up",
        false,
        null,
        null,
      ),
    },
  };
}

test("HTTP service exposes proposal, confirmation, cart draft and six-layer metrics", async () => {
  const now = () => Date.parse("2026-08-01T10:00:00Z");
  const server = buildControlPlaneServer({
    agent: new SearchAdsRecsBuyerAgent({ now }),
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const demo = await fetch(`${baseUrl}/demo`);
    assert.equal(demo.status, 200);
    assert.match(demo.headers.get("content-type") ?? "", /text\/html/);
    assert.match(await demo.text(), /(?:BuySense 智购引擎|墨圆智选 · 搜广推决策智能体|本地千问驱动的搜广推 Agent)/);

    const health = await fetch(`${baseUrl}/health`).then((response) => response.json());
    assert.equal(health.status, "UP");
    assert.equal(health.ready, true);
    assert.equal(health.paymentEnabled, false);
    assert.equal(health.model.mode, "replay");
    assert.equal(health.dataPlane.status, "embedded");
    assert.equal(health.dataPlane.retailSources.catalog.effectiveSource, "local_snapshot");
    assert.equal(health.dataPlane.retailSources.catalog.effectiveProviderId, "embedded");
    assert.deepEqual(health.dataPlane.retailSources.catalog.telemetry, {
      requests: 0,
      errors: 0,
      fallbacks: 0,
    });

    const runtime = await fetch(`${baseUrl}/api/v1/runtime`).then(
      (response) => response.json(),
    );
    assert.equal(runtime.agentFramework, "@earendil-works/pi-agent-core");
    assert.equal(runtime.model.provider, "moyuan-replay");

    const proposalResponse = await post(baseUrl, {
      sessionId: "http-session-1",
      userId: "http-user-1",
      message: "总预算7000元，帮我选手机并搭配耳机和充电器",
    });
    assert.equal(proposalResponse.status, 200);
    const proposal = await proposalResponse.json();
    assert.equal(proposal.phase, "proposal");

    const confirmation = await post(baseUrl, {
      sessionId: "http-session-1",
      userId: "http-user-1",
      message: "确认",
      confirmed: true,
    }).then((response) => response.json());
    assert.equal(confirmation.phase, "cart_draft");
    assert.equal(confirmation.cartDraft.paymentAuthorized, false);
    assert.ok(confirmation.confirmationTrace.some(
      (record: { event: string; detail: Record<string, unknown> }) =>
        record.event === "data_plane_result" &&
        record.detail.resource === "pricing" &&
        record.detail.purpose === "confirmation_refresh" &&
        record.detail.source === "local_snapshot",
    ));

    const draft = await fetch(
      `${baseUrl}/api/v1/cart-drafts/${confirmation.cartDraft.draftId}`,
      { headers: { "x-session-id": "http-session-1" } },
    ).then((response) => response.json());
    assert.equal(draft.draftId, confirmation.cartDraft.draftId);
    const unscopedDraft = await fetch(
      `${baseUrl}/api/v1/cart-drafts/${confirmation.cartDraft.draftId}`,
    );
    assert.equal(unscopedDraft.status, 400);

    const metrics = await fetch(`${baseUrl}/metrics`).then((response) => response.json());
    assert.equal(metrics.northStar.name, "qualified_decision_success_rate");
    assert.equal(metrics.northStar.value, 1);
    assert.equal(Object.keys(metrics.layers).length, 6);
  } finally {
    await new Promise<void>((resolve, reject) =>
      server.close((error) => error ? reject(error) : resolve()),
    );
  }
});

test("readiness stays up when the optional model dependency uses deterministic fallback", async () => {
  const agent = new SearchAdsRecsBuyerAgent({
    runtime: new UnavailableOptionalModelRuntime(),
  });
  const server = buildControlPlaneServer({ agent });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const summaryResponse = await fetch(`${baseUrl}/health`);
    assert.equal(summaryResponse.status, 200);
    const summary = await summaryResponse.json();
    assert.equal(summary.status, "DEGRADED");
    assert.equal(summary.ready, true);
    assert.equal(summary.dependencies.model.required, false);
    assert.equal(summary.dependencies.model.fallbackActive, true);
    assert.equal((await fetch(`${baseUrl}/health/ready`)).status, 200);
    assert.equal((await fetch(`${baseUrl}/health/dependencies`)).status, 200);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("readiness never waits for a slow optional model probe", async () => {
  const runtime = new SlowOptionalModelRuntime();
  const agent = new SearchAdsRecsBuyerAgent({ runtime });
  const server = buildControlPlaneServer({ agent });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const response = await fetch(`${baseUrl}/health/ready`);
    assert.equal(response.status, 200);
    assert.equal(runtime.probeCalls, 0);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("readiness fails when the required Python data plane is unavailable", async () => {
  const server = buildControlPlaneServer({
    discoveryMode: "python",
    discoveryBaseUrl: "http://127.0.0.1:1",
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    assert.equal((await fetch(`${baseUrl}/health/live`)).status, 200);
    const summaryResponse = await fetch(`${baseUrl}/health`);
    assert.equal(summaryResponse.status, 200);
    const summary = await summaryResponse.json();
    assert.equal(summary.status, "DOWN");
    assert.equal(summary.ready, false);
    assert.equal(summary.dependencies.dataPlane.required, true);
    assert.equal((await fetch(`${baseUrl}/health/ready`)).status, 503);
    assert.equal((await fetch(`${baseUrl}/health/dependencies`)).status, 503);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("readiness whitelists Python retail source health and reports Python-managed fallback", async () => {
  const python = createServer((_request, response) => {
    const body = Buffer.from(JSON.stringify(pythonRetailHealthPayload()));
    response.writeHead(200, {
      "content-type": "application/json",
      "content-length": body.length,
    });
    response.end(body);
  });
  await new Promise<void>((resolve) => python.listen(0, "127.0.0.1", resolve));
  const pythonBaseUrl = `http://127.0.0.1:${(python.address() as AddressInfo).port}`;
  const server = buildControlPlaneServer({
    discoveryMode: "python",
    discoveryBaseUrl: pythonBaseUrl,
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const response = await fetch(`${baseUrl}/health/ready`);
    assert.equal(response.status, 200);
    const summary = await response.json();
    assert.equal(summary.status, "DEGRADED");
    assert.equal(summary.ready, true);
    assert.equal(summary.dataPlane.status, "degraded");
    assert.equal(summary.dataPlane.retailSources.catalog.fallbackActive, true);
    assert.equal(summary.dataPlane.retailSources.catalog.providerId, "retail-a1b2c3");
    assert.equal(summary.dataPlane.retailSources.catalog.effectiveProviderId, "normal-3c-v1");
    assert.equal(summary.dataPlane.retailSources.catalog.telemetry.fallbacks, 1);
    assert.equal(summary.dependencies.dataPlane.required, true);
    assert.equal(summary.dependencies.dataPlane.fallbackActive, true);
    const serialized = JSON.stringify(summary);
    assert.doesNotMatch(serialized, /provider\.invalid|must-not-cross|apiToken|upstreamUrl/);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await new Promise<void>((resolve) => python.close(() => resolve()));
  }
});

test("readiness preserves sanitized per-source diagnostics from Python 503", async () => {
  const payload = pythonRetailHealthPayload();
  payload.status = "DOWN";
  payload.ready = false;
  payload.retailSources.catalog = retailSourceHealth(
    "http",
    "unavailable",
    "down",
    false,
    "retail-a1b2c3",
    null,
  );
  const python = createServer((_request, response) => {
    const body = Buffer.from(JSON.stringify(payload));
    response.writeHead(503, {
      "content-type": "application/json",
      "content-length": body.length,
    });
    response.end(body);
  });
  await new Promise<void>((resolve) => python.listen(0, "127.0.0.1", resolve));
  const pythonBaseUrl = `http://127.0.0.1:${(python.address() as AddressInfo).port}`;
  const server = buildControlPlaneServer({
    discoveryMode: "python",
    discoveryBaseUrl: pythonBaseUrl,
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const response = await fetch(`${baseUrl}/health/ready`);
    assert.equal(response.status, 503);
    const summary = await response.json();
    assert.equal(summary.ready, false);
    assert.equal(summary.dataPlane.status, "down");
    assert.equal(summary.dataPlane.error, "retail_source_unavailable");
    assert.equal(summary.dataPlane.retailSources.catalog.status, "down");
    assert.equal(summary.dataPlane.retailSources.catalog.lastErrorCode, "provider_timeout");
    assert.equal(summary.dataPlane.retailSources.catalog.providerId, "retail-a1b2c3");
    assert.equal(summary.dataPlane.retailSources.catalog.effectiveProviderId, null);
    assert.equal(summary.dataPlane.retailSources.catalog.telemetry.errors, 1);
    assert.doesNotMatch(JSON.stringify(summary), /provider\.invalid|must-not-cross/);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await new Promise<void>((resolve) => python.close(() => resolve()));
  }
});

test("readiness fails closed without reflecting malformed Python source identifiers", async () => {
  const payload = pythonRetailHealthPayload();
  payload.retailSources.catalog.providerId = "https://provider.invalid/token";
  const python = createServer((_request, response) => {
    const body = Buffer.from(JSON.stringify(payload));
    response.writeHead(200, {
      "content-type": "application/json",
      "content-length": body.length,
    });
    response.end(body);
  });
  await new Promise<void>((resolve) => python.listen(0, "127.0.0.1", resolve));
  const pythonBaseUrl = `http://127.0.0.1:${(python.address() as AddressInfo).port}`;
  const server = buildControlPlaneServer({
    discoveryMode: "python",
    discoveryBaseUrl: pythonBaseUrl,
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const response = await fetch(`${baseUrl}/health/ready`);
    assert.equal(response.status, 503);
    const summary = await response.json();
    assert.equal(summary.ready, false);
    assert.equal(summary.dataPlane.error, "invalid_retail_source_health");
    assert.equal(summary.dataPlane.retailSources, null);
    assert.doesNotMatch(JSON.stringify(summary), /provider\.invalid|token/);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await new Promise<void>((resolve) => python.close(() => resolve()));
  }
});

test("readiness fails when the required Run repository is unavailable", async () => {
  const repository = new CommerceRepository();
  const server = buildControlPlaneServer({ repository });
  repository.close();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    assert.equal((await fetch(`${baseUrl}/health/live`)).status, 200);
    const response = await fetch(`${baseUrl}/health/ready`);
    assert.equal(response.status, 503);
    const summary = await response.json();
    assert.equal(summary.status, "DOWN");
    assert.equal(summary.dependencies.storage.required, true);
    assert.equal(summary.dependencies.storage.status, "down");
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("HTTP service streams runtime, Agent trace and the final result as NDJSON", async () => {
  const server = buildControlPlaneServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const response = await fetch(`${baseUrl}/api/v1/agent/stream`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        sessionId: "stream-session-1",
        userId: "stream-user-1",
        message: "预算5000元，不要广告，帮我推荐一台手机",
      }),
    });
    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-type") ?? "", /application\/x-ndjson/);
    const events = (await response.text()).trim().split("\n").map((line) => JSON.parse(line));
    assert.equal(events[0].type, "runtime");
    assert.ok(events.some((event) => event.type === "trace"));
    assert.ok(
      events.some(
        (event) => event.type === "trace" && event.data.event === "model_execution",
      ),
    );
    const result = events.find((event) => event.type === "result");
    assert.equal(result.data.phase, "proposal");
    assert.equal(result.data.decision.runtime.mode, "replay");
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("HTTP service rejects unknown fields and does not expose payment", async () => {
  const server = buildControlPlaneServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const invalid = await post(baseUrl, {
      sessionId: "s1",
      userId: "u1",
      message: "买手机",
      paymentToken: "must-not-be-accepted",
    });
    assert.equal(invalid.status, 400);
    assert.match(await invalid.text(), /paymentToken/);
    const payment = await fetch(`${baseUrl}/api/v1/payment`, { method: "POST" });
    assert.equal(payment.status, 404);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("production mode keeps shallow liveness while disabling the legacy v1 surface", async () => {
  const server = buildControlPlaneServer({ legacyV1Enabled: false });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const live = await fetch(`${baseUrl}/health/live`);
    assert.equal(live.status, 200);
    const ready = await fetch(`${baseUrl}/health/ready`);
    assert.equal(ready.status, 200);
    assert.equal((await fetch(`${baseUrl}/api/v1/runtime`)).status, 404);
    assert.equal((await post(baseUrl, {
      sessionId: "legacy-disabled",
      userId: "legacy-disabled",
      message: "买手机",
    })).status, 404);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});
