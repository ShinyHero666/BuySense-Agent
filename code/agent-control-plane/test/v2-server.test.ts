import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import test from "node:test";
import { buildControlPlaneServer } from "../src/server.js";

function cookieFrom(response: Response): string {
  const value = response.headers.get("set-cookie");
  assert.ok(value);
  return value.split(";", 1)[0] ?? "";
}

async function awaitRun(baseUrl: string, runId: string, cookie: string): Promise<Record<string, unknown>> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const run = await fetch(`${baseUrl}/api/v2/runs/${runId}`, {
      headers: { cookie },
    }).then((response) => response.json()) as Record<string, unknown>;
    if (["completed", "failed", "cancelled"].includes(String(run.status))) return run;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  throw new Error("run did not finish");
}

test("v2 API issues server identity, runs asynchronously and replays SSE events", async () => {
  const server = buildControlPlaneServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const creation = await fetch(`${baseUrl}/api/v2/runs`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "idempotency-key": "turn-001",
      },
      body: JSON.stringify({ message: "总预算7000元，帮我选手机并搭配耳机和充电器" }),
    });
    assert.equal(creation.status, 202);
    const cookie = cookieFrom(creation);
    const accepted = await creation.json() as Record<string, unknown>;
    assert.equal(accepted.status, "queued");
    const runId = String(accepted.runId);

    const completed = await awaitRun(baseUrl, runId, cookie);
    assert.equal(completed.status, "completed");
    assert.equal((completed.result as { phase: string }).phase, "proposal");
    const diagnostics = await fetch(`${baseUrl}/api/v2/runs/${runId}/diagnostics`, {
      headers: { cookie },
    }).then((response) => response.json()) as Record<string, number>;
    assert.ok(Number(diagnostics.tasks) > 0);
    assert.ok(Number(diagnostics.artifacts) > 0);
    assert.equal(diagnostics.modelExecutions, 6);
    assert.ok(Number(diagnostics.constraints) > 0);

    const stream = await fetch(`${baseUrl}/api/v2/runs/${runId}/events`, {
      headers: { cookie },
    });
    assert.equal(stream.status, 200);
    assert.match(stream.headers.get("content-type") ?? "", /text\/event-stream/);
    const body = await stream.text();
    assert.match(body, /event: run_created/);
    assert.match(body, /event: model_execution/);
    assert.match(body, /event: result/);

    const replay = await fetch(`${baseUrl}/api/v2/runs`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        cookie,
        "idempotency-key": "turn-001",
      },
      body: JSON.stringify({ message: "重复提交不应再次执行" }),
    });
    assert.equal(replay.status, 200);
    const replayPayload = await replay.json() as Record<string, unknown>;
    assert.equal(replayPayload.runId, runId);
    assert.equal(replayPayload.idempotentReplay, true);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("v2 API rejects caller supplied identity and prevents cross-identity run reads", async () => {
  const server = buildControlPlaneServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const invalid = await fetch(`${baseUrl}/api/v2/runs`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ message: "买手机", userId: "forged-user" }),
    });
    assert.equal(invalid.status, 400);
    assert.match(await invalid.text(), /userId/);

    const first = await fetch(`${baseUrl}/api/v2/runs`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ message: "预算5000元买手机" }),
    });
    const firstCookie = cookieFrom(first);
    const runId = String(((await first.json()) as Record<string, unknown>).runId);
    await awaitRun(baseUrl, runId, firstCookie);

    const otherSession = await fetch(`${baseUrl}/api/v2/session`);
    const otherCookie = cookieFrom(otherSession);
    const hidden = await fetch(`${baseUrl}/api/v2/runs/${runId}`, {
      headers: { cookie: otherCookie },
    });
    assert.equal(hidden.status, 404);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("v2 personalization preference and interaction history stay identity scoped", async () => {
  const server = buildControlPlaneServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const session = await fetch(`${baseUrl}/api/v2/session`);
    const cookie = cookieFrom(session);
    const preference = await fetch(`${baseUrl}/api/v2/preferences`, {
      method: "PUT",
      headers: { "content-type": "application/json", cookie },
      body: JSON.stringify({ personalizationEnabled: false }),
    });
    assert.equal(preference.status, 200);
    assert.equal((await preference.json() as { personalizationEnabled: boolean }).personalizationEnabled, false);
    const interaction = await fetch(`${baseUrl}/api/v2/interactions`, {
      method: "POST",
      headers: { "content-type": "application/json", cookie },
      body: JSON.stringify({ eventType: "view", productId: "spu-honor-200" }),
    });
    assert.equal(interaction.status, 202);
    const cleared = await fetch(`${baseUrl}/api/v2/interactions`, {
      method: "DELETE",
      headers: { cookie },
    }).then((response) => response.json()) as { deleted: number };
    assert.equal(cleared.deleted, 1);
    const current = await fetch(`${baseUrl}/api/v2/preferences`, {
      headers: { cookie },
    }).then((response) => response.json()) as { personalizationEnabled: boolean };
    assert.equal(current.personalizationEnabled, false);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});
