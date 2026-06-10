import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import test from "node:test";
import { SearchAdsRecsBuyerAgent } from "../src/buyer-agent.js";
import { buildControlPlaneServer } from "../src/server.js";

async function post(baseUrl: string, payload: unknown): Promise<Response> {
  return fetch(`${baseUrl}/api/v1/agent`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });
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
    assert.match(await demo.text(), /(?:墨圆智选 · 搜广推决策智能体|本地千问驱动的搜广推 Agent)/);

    const health = await fetch(`${baseUrl}/health`).then((response) => response.json());
    assert.equal(health.status, "UP");
    assert.equal(health.paymentEnabled, false);
    assert.equal(health.model.mode, "replay");
    assert.equal(health.dataPlane.status, "embedded");

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
