import assert from "node:assert/strict";
import test from "node:test";
import type { SearchAdsRecsBuyerAgent } from "../src/buyer-agent.js";
import { CommerceRepository } from "../src/v2-repository.js";
import { PersistentRunManager } from "../src/v2-runs.js";

test("persistent run cancellation aborts work and emits a recoverable terminal event", async () => {
  const repository = new CommerceRepository();
  const identity = repository.createIdentity();
  const slowAgent = {
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
    repository.close();
  }
});

test("persistent run manager bounds active work and drains its queue", async () => {
  const repository = new CommerceRepository();
  const identity = repository.createIdentity();
  const releases: Array<() => void> = [];
  let started = 0;
  const slowAgent = {
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
    repository.close();
  }
});
