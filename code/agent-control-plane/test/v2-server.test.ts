import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { SearchAdsRecsBuyerAgent } from "../src/buyer-agent.js";
import { NORMAL_3C_DOMAIN } from "../src/domain-pack.js";
import { assertContract } from "../src/generated/contracts-v2.js";
import { buildControlPlaneServer } from "../src/server.js";
import { CommerceRepository } from "../src/v2-repository.js";

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
    const accepted: unknown = await creation.json();
    assertContract("CreateRunResponse", accepted);
    assert.equal(accepted.status, "queued");
    assert.equal(accepted.idempotentReplay, false);
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
      body: JSON.stringify({ message: "总预算7000元，帮我选手机并搭配耳机和充电器" }),
    });
    assert.equal(replay.status, 200);
    const replayPayload: unknown = await replay.json();
    assertContract("CreateRunResponse", replayPayload);
    assert.equal(replayPayload.runId, runId);
    assert.equal(replayPayload.idempotentReplay, true);

    const conflict = await fetch(`${baseUrl}/api/v2/runs`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        cookie,
        "idempotency-key": "turn-001",
      },
      body: JSON.stringify({ message: "同一幂等键不能更换请求" }),
    });
    assert.equal(conflict.status, 409);
    assert.match(await conflict.text(), /idempotency_key_reused/);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("v2 API rejects caller supplied identity and prevents cross-identity run reads", async () => {
  const server = buildControlPlaneServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const unboundConfirmation = await fetch(`${baseUrl}/api/v2/runs`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ message: "确认", confirmed: true }),
    });
    assert.equal(unboundConfirmation.status, 400);
    assert.match(await unboundConfirmation.text(), /proposalRunId/);

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

test("v2 registry selects and audits the outdoor camping pack per Run", async () => {
  const server = buildControlPlaneServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const registry = await fetch(`${baseUrl}/api/v2/domain-packs`)
      .then((response) => response.json()) as {
        defaultPackId: string;
        packs: Array<{ id: string; workflowId: string }>;
      };
    assert.equal(registry.defaultPackId, "normal-3c-v1");
    const packIds = new Set(registry.packs.map((pack) => pack.id));
    assert.ok(packIds.has("normal-3c-v1"));
    assert.ok(packIds.has("outdoor-camping-v1"));

    const unknown = await fetch(`${baseUrl}/api/v2/runs`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ message: "test", domainPackId: "missing-pack-v1" }),
    });
    assert.equal(unknown.status, 400);
    assert.match(await unknown.text(), /domainPackId/);

    const creation = await fetch(`${baseUrl}/api/v2/runs`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "idempotency-key": "camping-pack-run",
      },
      body: JSON.stringify({
        domainPackId: "outdoor-camping-v1",
        message: "预算900元，帮我搭配一套防风炉具、气罐和锅具，不要广告",
      }),
    });
    assert.equal(creation.status, 202);
    const cookie = cookieFrom(creation);
    const created = await creation.json() as { runId: string };
    const completed = await awaitRun(baseUrl, created.runId, cookie);
    assert.equal(completed.status, "completed");
    assert.equal(completed.domainPackId, "outdoor-camping-v1");
    assert.equal(completed.workflowId, "commerce-decision-v1");
    const result = completed.result as {
      decision: { bundle: { items: Array<{ product: { category: string } }> } };
    };
    assert.deepEqual(
      result.decision.bundle.items.map((item) => item.product.category).sort(),
      ["camp_stove", "cookware", "fuel_canister"],
    );

    const replay = await fetch(`${baseUrl}/api/v2/runs`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        cookie,
        "idempotency-key": "camping-pack-run",
      },
      body: JSON.stringify({
        domainPackId: "outdoor-camping-v1",
        message: "预算900元，帮我搭配一套防风炉具、气罐和锅具，不要广告",
      }),
    });
    assert.equal(replay.status, 200);
    const replayed = await replay.json() as Record<string, unknown>;
    assert.equal(replayed.runId, created.runId);
    assert.equal(replayed.domainPackId, "outdoor-camping-v1");
    assert.equal(replayed.workflowId, "commerce-decision-v1");

    const conflictingReplay = await fetch(`${baseUrl}/api/v2/runs`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        cookie,
        "idempotency-key": "camping-pack-run",
      },
      body: JSON.stringify({
        domainPackId: "normal-3c-v1",
        message: "同一幂等键不得改变已经固化的领域包",
      }),
    });
    assert.equal(conflictingReplay.status, 409);

    const wrongPackConfirmation = await fetch(`${baseUrl}/api/v2/runs`, {
      method: "POST",
      headers: { "content-type": "application/json", cookie },
      body: JSON.stringify({
        domainPackId: "normal-3c-v1",
        message: "确认",
        confirmed: true,
        proposalRunId: created.runId,
      }),
    });
    assert.equal(wrongPackConfirmation.status, 409);
    assert.match(await wrongPackConfirmation.text(), /proposal_extension_mismatch/);

    const confirmationBody = JSON.stringify({
      domainPackId: "outdoor-camping-v1",
      message: "确认",
      confirmed: true,
      proposalRunId: created.runId,
    });
    const confirmationResponses = await Promise.all([
      fetch(`${baseUrl}/api/v2/runs`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          cookie,
          "idempotency-key": "confirm-camping-a",
        },
        body: confirmationBody,
      }),
      fetch(`${baseUrl}/api/v2/runs`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          cookie,
          "idempotency-key": "confirm-camping-b",
        },
        body: confirmationBody,
      }),
    ]);
    assert.deepEqual(
      confirmationResponses.map((response) => response.status).sort(),
      [200, 202],
    );
    const confirmationRuns = await Promise.all(
      confirmationResponses.map((response) => response.json() as Promise<{ runId: string }>),
    );
    assert.equal(confirmationRuns[0]?.runId, confirmationRuns[1]?.runId);
    const correctPackConfirmation = confirmationRuns[0]!;
    const acceptedConfirmation = await awaitRun(
      baseUrl,
      correctPackConfirmation.runId,
      cookie,
    );
    assert.equal((acceptedConfirmation.result as { phase: string }).phase, "cart_draft");
    const confirmationAliasConflict = await fetch(`${baseUrl}/api/v2/runs`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        cookie,
        "idempotency-key": "confirm-camping-b",
      },
      body: JSON.stringify({ message: "幂等别名也不能改作其他请求" }),
    });
    assert.equal(confirmationAliasConflict.status, 409);

    const normalCreation = await fetch(`${baseUrl}/api/v2/runs`, {
      method: "POST",
      headers: { "content-type": "application/json", cookie },
      body: JSON.stringify({
        domainPackId: "normal-3c-v1",
        message: "预算7000元，选手机并搭配耳机和充电器",
      }),
    }).then((response) => response.json()) as { runId: string };
    const normalCompleted = await awaitRun(baseUrl, normalCreation.runId, cookie);
    assert.equal(
      ((completed.result as { decision: { runId: string } }).decision.runId),
      created.runId,
    );
    assert.equal(
      ((normalCompleted.result as { decision: { runId: string } }).decision.runId),
      normalCreation.runId,
    );
    for (const runId of [created.runId, normalCreation.runId]) {
      const diagnostics = await fetch(`${baseUrl}/api/v2/runs/${runId}/diagnostics`, {
        headers: { cookie },
      }).then((response) => response.json()) as { tasks: number };
      assert.ok(diagnostics.tasks > 0, `missing persisted tasks for ${runId}`);
    }
    const metrics = await fetch(`${baseUrl}/metrics`).then((response) => response.json()) as {
      northStar: { numerator: number; denominator: number };
    };
    assert.equal(metrics.northStar.denominator, 2);
    assert.equal(metrics.northStar.numerator, 2);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("server shutdown closes an active SSE stream before waiting for connections", async () => {
  const agent = {
    domain: NORMAL_3C_DOMAIN,
    handle: async () => new Promise(() => {}),
  } as unknown as SearchAdsRecsBuyerAgent;
  const server = buildControlPlaneServer({ agent });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  const creation = await fetch(`${baseUrl}/api/v2/runs`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "idempotency-key": "shutdown-active-sse",
    },
    body: JSON.stringify({ message: "keep this Run active" }),
  });
  const cookie = cookieFrom(creation);
  const created = await creation.json() as { runId: string };
  const stream = await fetch(`${baseUrl}/api/v2/runs/${created.runId}/events`, {
    headers: { cookie },
  });
  assert.equal(stream.status, 200);
  try {
    await Promise.race([
      new Promise<void>((resolve, reject) => {
        server.close((error) => error ? reject(error) : resolve());
      }),
      new Promise<never>((_resolve, reject) => {
        setTimeout(() => reject(new Error("server close timed out with active SSE")), 1_000);
      }),
    ]);
    assert.equal(server.listening, false);
    assert.equal((await stream.text()).includes("event: run_created"), true);
  } finally {
    if (server.listening) {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  }
});

test("identity concurrency admission is atomic across two control-plane servers", async () => {
  const directory = mkdtempSync(join(tmpdir(), "moyuan-v2-admission-http-"));
  const path = join(directory, "agent.sqlite");
  const firstRepository = new CommerceRepository(path);
  const secondRepository = new CommerceRepository(path);
  const agent = {
    domain: NORMAL_3C_DOMAIN,
    handle: async () => new Promise(() => {}),
  } as unknown as SearchAdsRecsBuyerAgent;
  const first = buildControlPlaneServer({ agent, repository: firstRepository });
  const second = buildControlPlaneServer({ agent, repository: secondRepository });
  await Promise.all([
    new Promise<void>((resolve) => first.listen(0, "127.0.0.1", resolve)),
    new Promise<void>((resolve) => second.listen(0, "127.0.0.1", resolve)),
  ]);
  const firstBase = `http://127.0.0.1:${(first.address() as AddressInfo).port}`;
  const secondBase = `http://127.0.0.1:${(second.address() as AddressInfo).port}`;
  const create = (baseUrl: string, key: string, cookie = "") => fetch(`${baseUrl}/api/v2/runs`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "idempotency-key": key,
      ...(cookie ? { cookie } : {}),
    },
    body: JSON.stringify({ message: key }),
  });
  try {
    const initial = await create(firstBase, "identity-limit-one");
    assert.equal(initial.status, 202);
    const cookie = cookieFrom(initial);
    assert.equal((await create(firstBase, "identity-limit-two", cookie)).status, 202);
    const raced = await Promise.all([
      create(firstBase, "identity-limit-three", cookie),
      create(secondBase, "identity-limit-four", cookie),
    ]);
    assert.deepEqual(raced.map((response) => response.status).sort(), [202, 429]);
    assert.equal(firstRepository.listIncompleteRuns().length, 3);
  } finally {
    await Promise.all([
      new Promise<void>((resolve) => first.close(() => resolve())),
      new Promise<void>((resolve) => second.close(() => resolve())),
    ]);
    firstRepository.close();
    secondRepository.close();
    rmSync(directory, { recursive: true, force: true });
  }
});
