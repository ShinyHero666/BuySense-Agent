import assert from "node:assert/strict";
import test from "node:test";
import type { SearchAdsRecsBuyerAgent } from "../src/buyer-agent.js";
import { CommerceRepository } from "../src/v2-repository.js";
import { PersistentRunManager } from "../src/v2-runs.js";
import { NORMAL_3C_DOMAIN } from "../src/domain-pack.js";

test("persistent run cancellation aborts work and emits a recoverable terminal event", async () => {
  const repository = new CommerceRepository();
  const identity = repository.createIdentity();
  const slowAgent = {
    domain: NORMAL_3C_DOMAIN,
    handle: async (
      _request: unknown,
      options: { signal?: AbortSignal },
    ) => new Promise((_resolve, reject) => {
      const abort = () => reject(new DOMException("cancelled", "AbortError"));
      if (options.signal?.aborted) abort();
      else options.signal?.addEventListener("abort", abort, { once: true });
    }),
  } as unknown as SearchAdsRecsBuyerAgent;
  const manager = new PersistentRunManager(repository, slowAgent);
  try {
    const creation = manager.create({
      identity,
      message: "slow request",
      confirmed: false,
      idempotencyKey: null,
    });
    await new Promise((resolve) => setTimeout(resolve, 5));
    manager.cancel(creation.run.runId, identity.identityId);
    for (let attempt = 0; attempt < 100; attempt += 1) {
      if (manager.get(creation.run.runId, identity.identityId)?.status === "cancelled") break;
      await new Promise((resolve) => setTimeout(resolve, 2));
    }
    assert.equal(manager.get(creation.run.runId, identity.identityId)?.status, "cancelled");
    assert.equal(
      manager.events(creation.run.runId, identity.identityId).at(-1)?.eventType,
      "run_cancelled",
    );
  } finally {
    manager.close();
    repository.close();
  }
});

test("persistent run manager bounds active work and drains its queue", async () => {
  const repository = new CommerceRepository();
  const identity = repository.createIdentity();
  const releases: Array<() => void> = [];
  let started = 0;
  const slowAgent = {
    domain: NORMAL_3C_DOMAIN,
    handle: async () => new Promise((_resolve, reject) => {
      started += 1;
      releases.push(() => reject(new Error("test release")));
    }),
  } as unknown as SearchAdsRecsBuyerAgent;
  const manager = new PersistentRunManager(repository, slowAgent, 1, 1);
  try {
    const first = manager.create({
      identity,
      message: "first",
      confirmed: false,
      idempotencyKey: null,
    });
    const second = manager.create({
      identity,
      message: "second",
      confirmed: false,
      idempotencyKey: null,
    });
    await new Promise((resolve) => setTimeout(resolve, 5));
    assert.equal(started, 1);
    assert.equal(manager.canAccept(), false);
    assert.throws(() => manager.create({
      identity,
      message: "overflow",
      confirmed: false,
      idempotencyKey: null,
    }), /global_run_capacity_exhausted/);

    releases.shift()?.();
    for (let attempt = 0; attempt < 50 && started < 2; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 2));
    }
    assert.equal(started, 2);
    releases.shift()?.();
    for (let attempt = 0; attempt < 50; attempt += 1) {
      if (manager.get(second.run.runId, identity.identityId)?.status === "failed") break;
      await new Promise((resolve) => setTimeout(resolve, 2));
    }
    assert.equal(manager.get(first.run.runId, identity.identityId)?.status, "failed");
    assert.equal(manager.get(second.run.runId, identity.identityId)?.status, "failed");
  } finally {
    manager.close();
    repository.close();
  }
});

test("a throwing SSE subscriber cannot fail a durably committed Run", async () => {
  const repository = new CommerceRepository();
  const identity = repository.createIdentity();
  const agent = {
    domain: NORMAL_3C_DOMAIN,
    handle: async () => ({
      phase: "no_pending_decision",
      message: "test result",
      decision: null,
      cartDraft: null,
      confirmationTrace: [],
    }),
  } as unknown as SearchAdsRecsBuyerAgent;
  const manager = new PersistentRunManager(repository, agent);
  try {
    const creation = manager.create({
      identity,
      message: "subscriber isolation",
      confirmed: false,
      idempotencyKey: null,
    });
    const received: string[] = [];
    manager.subscribe(creation.run.runId, () => {
      throw new Error("simulated subscriber failure");
    });
    manager.subscribe(creation.run.runId, (event) => received.push(event.eventType));
    for (let attempt = 0; attempt < 100; attempt += 1) {
      if (manager.get(creation.run.runId, identity.identityId)?.status === "completed") break;
      await new Promise((resolve) => setTimeout(resolve, 2));
    }
    assert.equal(manager.get(creation.run.runId, identity.identityId)?.status, "completed");
    assert.ok(received.includes("run_started"));
    assert.ok(received.includes("result"));
    assert.equal(
      manager.events(creation.run.runId, identity.identityId).at(-1)?.eventType,
      "result",
    );
  } finally {
    manager.close();
    repository.close();
  }
});

test("resumed Runs fail closed when persisted workflow metadata no longer matches", async () => {
  const repository = new CommerceRepository();
  const identity = repository.createIdentity();
  let handled = false;
  const agent = {
    domain: NORMAL_3C_DOMAIN,
    handle: async () => {
      handled = true;
      throw new Error("must not execute");
    },
  } as unknown as SearchAdsRecsBuyerAgent;
  const queued = repository.createRun({
    identity,
    message: "persisted old workflow",
    confirmed: false,
    idempotencyKey: null,
    domainPackId: NORMAL_3C_DOMAIN.packId,
    workflowId: "legacy-commerce-v1",
  }).run;
  const manager = new PersistentRunManager(repository, agent);
  try {
    assert.equal(manager.resumeIncomplete(), 1);
    for (let attempt = 0; attempt < 100; attempt += 1) {
      if (manager.get(queued.runId, identity.identityId)?.status === "failed") break;
      await new Promise((resolve) => setTimeout(resolve, 2));
    }
    assert.equal(manager.get(queued.runId, identity.identityId)?.status, "failed");
    assert.equal(handled, false);
  } finally {
    manager.close();
    repository.close();
  }
});
