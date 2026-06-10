import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import type { CartDraft } from "../src/contracts.js";
import { CommerceRepository } from "../src/v2-repository.js";

test("SQLite repository survives restart and enforces identity-scoped idempotency", () => {
  const directory = mkdtempSync(join(tmpdir(), "moyuan-v2-"));
  const path = join(directory, "agent.sqlite");
  let now = Date.parse("2026-08-02T10:00:00Z");
  try {
    const first = new CommerceRepository(path, () => now);
    const identity = first.createIdentity();
    const created = first.createRun({
      identity,
      message: "预算 5000 元买手机",
      confirmed: false,
      idempotencyKey: "same-turn",
    });
    assert.equal(created.created, true);
    first.appendEvent(created.run.runId, "run_created", { source: "test" });
    first.close();

    const reopened = new CommerceRepository(path, () => now);
    const replay = reopened.createRun({
      identity,
      message: "这个消息不会覆盖原请求",
      confirmed: false,
      idempotencyKey: "same-turn",
    });
    assert.equal(replay.created, false);
    assert.equal(replay.run.runId, created.run.runId);
    assert.equal(replay.run.message, "预算 5000 元买手机");
    assert.equal(reopened.listEvents(created.run.runId, identity.identityId).length, 1);
    reopened.close();
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("SQLite cart drafts expire instead of remaining visible indefinitely", () => {
  let now = Date.parse("2026-08-02T10:00:00Z");
  const repository = new CommerceRepository(":memory:", () => now);
  try {
    const draft: CartDraft = {
      draftId: "draft-expiring",
      sessionId: "session-a",
      status: "ready",
      items: [],
      totalPrice: 0,
      currency: "CNY",
      quoteBatchId: "quote-a",
      createdAt: new Date(now).toISOString(),
      expiresAt: new Date(now + 1_000).toISOString(),
      paymentAuthorized: false,
    };
    repository.putDraft(draft);
    assert.equal(repository.getDraft(draft.draftId)?.draftId, draft.draftId);
    now += 1_001;
    assert.equal(repository.getDraft(draft.draftId), null);
  } finally {
    repository.close();
  }
});

test("repository maintenance removes expired operational data without breaking retained interactions", () => {
  let now = Date.parse("2026-08-02T10:00:00Z");
  const repository = new CommerceRepository(":memory:", () => now);
  try {
    const disposableIdentity = repository.createIdentity(1_000);
    const completed = repository.createRun({
      identity: disposableIdentity,
      message: "completed request",
      confirmed: false,
      idempotencyKey: null,
    }).run;
    repository.updateRun(completed.runId, { status: "completed", result: { phase: "proposal" } });
    repository.putDraft({
      draftId: "draft-for-maintenance",
      sessionId: disposableIdentity.sessionId,
      status: "ready",
      items: [],
      totalPrice: 0,
      currency: "CNY",
      quoteBatchId: "quote-maintenance",
      createdAt: new Date(now).toISOString(),
      expiresAt: new Date(now + 1_000).toISOString(),
      paymentAuthorized: false,
    });

    const retainedIdentity = repository.createIdentity(1_000);
    repository.recordInteraction({
      identityId: retainedIdentity.identityId,
      sessionId: retainedIdentity.sessionId,
      eventType: "view",
      productId: "spu-retained",
      payload: {},
    });

    now += 31 * 24 * 60 * 60 * 1_000;
    const removed = repository.performMaintenance();
    assert.equal(removed.cartDrafts, 1);
    assert.equal(removed.terminalRuns, 1);
    assert.equal(removed.interactions, 0);
    assert.equal(removed.identities, 1);
    assert.equal(repository.getRun(completed.runId), null);
    assert.equal(repository.clearInteractionHistory(retainedIdentity.identityId), 1);
  } finally {
    repository.close();
  }
});
