import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import { SearchAdsRecsBuyerAgent } from "../src/buyer-agent.js";
import type { AgentTraceRecord, BuyerTurnReply, CartDraft } from "../src/contracts.js";
import {
  CommerceRepository,
  ConcurrentRunLimitError,
} from "../src/v2-repository.js";

test("shared settings and identity admission remain stable across SQLite connections", () => {
  const directory = mkdtempSync(join(tmpdir(), "moyuan-v2-shared-admission-"));
  const path = join(directory, "agent.sqlite");
  const first = new CommerceRepository(path);
  const second = new CommerceRepository(path);
  try {
    assert.equal(first.getOrCreateSetting("shared-secret", () => "first"), "first");
    assert.equal(second.getOrCreateSetting("shared-secret", () => "second"), "first");

    const identity = first.createIdentity();
    const create = (repository: CommerceRepository, key: string) =>
      repository.createRunWithEvent({
        identity,
        message: key,
        confirmed: false,
        idempotencyKey: key,
        activeRunLimit: 3,
      });
    const one = create(first, "active-one");
    create(second, "active-two");
    create(first, "active-three");
    assert.throws(() => create(second, "active-four"), ConcurrentRunLimitError);
    assert.equal(first.countActiveRuns(identity.identityId), 3);
    const replay = create(second, "active-one");
    assert.equal(replay.created, false);
    assert.equal(replay.run.runId, one.run.runId);
  } finally {
    first.close();
    second.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("repository health fails quickly when the SQLite writer is unavailable", () => {
  const directory = mkdtempSync(join(tmpdir(), "moyuan-v2-health-"));
  const path = join(directory, "agent.sqlite");
  const repository = new CommerceRepository(path);
  const blocker = new DatabaseSync(path);
  try {
    blocker.exec("BEGIN IMMEDIATE");
    const startedAt = performance.now();
    assert.equal(repository.healthStatus().status, "down");
    assert.ok(performance.now() - startedAt < 1_000);
  } finally {
    blocker.exec("ROLLBACK");
    blocker.close();
    repository.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("Run creation and trace projections roll back when their ledger event fails", () => {
  const directory = mkdtempSync(join(tmpdir(), "moyuan-v2-ledger-"));
  const path = join(directory, "agent.sqlite");
  const repository = new CommerceRepository(path);
  const admin = new DatabaseSync(path);
  try {
    const identity = repository.createIdentity();
    admin.exec(`
      CREATE TRIGGER reject_run_created
      BEFORE INSERT ON run_events
      WHEN NEW.event_type = 'run_created'
      BEGIN SELECT RAISE(ABORT, 'simulated run-created failure'); END
    `);
    assert.throws(() => repository.createRunWithEvent({
      identity,
      message: "creation must be atomic",
      confirmed: false,
      idempotencyKey: "atomic-create",
    }), /simulated run-created failure/);
    assert.equal(repository.findRunByIdempotency(identity.identityId, "atomic-create"), null);
    admin.exec("DROP TRIGGER reject_run_created");

    const created = repository.createRunWithEvent({
      identity,
      message: "creation must be atomic",
      confirmed: false,
      idempotencyKey: "atomic-create",
    });
    assert.equal(created.event?.eventType, "run_created");
    const trace: AgentTraceRecord = {
      sequence: 1,
      role: "lead",
      event: "task_delegated",
      detail: {
        taskId: `${created.run.runId}:task-search`,
        parentTaskId: null,
        to: "search",
        capability: "retrieved_search_candidates",
      },
    };
    admin.exec(`
      CREATE TRIGGER reject_task_event
      BEFORE INSERT ON run_events
      WHEN NEW.event_type = 'task'
      BEGIN SELECT RAISE(ABORT, 'simulated task-event failure'); END
    `);
    assert.throws(() => repository.persistTraceAndEvent(
      created.run.runId,
      trace,
      "task",
      { role: trace.role, event: trace.event, ...trace.detail },
      String(trace.detail.taskId),
      null,
    ), /simulated task-event failure/);
    assert.equal(repository.runDiagnostics(created.run.runId, identity.identityId)?.tasks, 0);
    assert.equal(repository.listEvents(created.run.runId, identity.identityId).length, 1);
    admin.exec("DROP TRIGGER reject_task_event");

    repository.persistTraceAndEvent(
      created.run.runId,
      trace,
      "task",
      { role: trace.role, event: trace.event, ...trace.detail },
      String(trace.detail.taskId),
      null,
    );
    assert.equal(repository.runDiagnostics(created.run.runId, identity.identityId)?.tasks, 1);
    assert.equal(repository.listEvents(created.run.runId, identity.identityId).length, 2);
  } finally {
    admin.close();
    repository.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

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
      domainPackId: "outdoor-camping-v1",
      workflowId: "commerce-decision-v1",
    });
    assert.equal(created.created, true);
    first.appendEvent(created.run.runId, "run_created", { source: "test" });
    first.close();

    const reopened = new CommerceRepository(path, () => now);
    const replay = reopened.createRun({
      identity,
      message: "预算 5000 元买手机",
      confirmed: false,
      idempotencyKey: "same-turn",
      domainPackId: "outdoor-camping-v1",
      workflowId: "commerce-decision-v1",
    });
    assert.equal(replay.created, false);
    assert.equal(replay.run.runId, created.run.runId);
    assert.equal(replay.run.message, "预算 5000 元买手机");
    assert.equal(replay.run.domainPackId, "outdoor-camping-v1");
    assert.equal(replay.run.workflowId, "commerce-decision-v1");
    assert.throws(() => reopened.createRun({
      identity,
      message: "同一幂等键不能换请求内容",
      confirmed: false,
      idempotencyKey: "same-turn",
      domainPackId: "normal-3c-v1",
      workflowId: "commerce-decision-v1",
    }), /idempotency_key_reused/);
    assert.equal(reopened.listEvents(created.run.runId, identity.identityId).length, 1);
    reopened.close();
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("terminal Run state and ledger event commit atomically and repair legacy gaps", () => {
  const directory = mkdtempSync(join(tmpdir(), "moyuan-v2-terminal-"));
  const path = join(directory, "agent.sqlite");
  const repository = new CommerceRepository(path);
  const admin = new DatabaseSync(path);
  try {
    const identity = repository.createIdentity();
    const run = repository.createRun({
      identity,
      message: "atomic terminal state",
      confirmed: false,
      idempotencyKey: null,
    }).run;
    admin.exec(`
      CREATE TRIGGER reject_terminal_event
      BEFORE INSERT ON run_events
      WHEN NEW.event_type = 'result'
      BEGIN
        SELECT RAISE(ABORT, 'simulated terminal event failure');
      END
    `);
    assert.throws(() => repository.finalizeRun(
      run.runId,
      { status: "completed", result: { phase: "proposal" } },
      "result",
      { phase: "proposal", result: { phase: "proposal" } },
    ), /simulated terminal event failure/);
    assert.equal(repository.getRun(run.runId)?.status, "queued");
    admin.exec("DROP TRIGGER reject_terminal_event");

    repository.finalizeRun(
      run.runId,
      { status: "completed", result: { phase: "proposal" } },
      "result",
      { phase: "proposal", result: { phase: "proposal" } },
    );
    assert.equal(repository.getRun(run.runId)?.status, "completed");
    assert.deepEqual(
      repository.listEvents(run.runId, identity.identityId).map((event) => event.eventType),
      ["result"],
    );

    const legacyGap = repository.createRun({
      identity,
      message: "legacy terminal gap",
      confirmed: false,
      idempotencyKey: null,
    }).run;
    repository.updateRun(legacyGap.runId, {
      status: "completed",
      result: { phase: "proposal" },
    });
    const recovered = repository.listEvents(legacyGap.runId, identity.identityId);
    assert.equal(recovered.at(-1)?.eventType, "result");
    assert.equal(recovered.at(-1)?.payload.recoveredTerminalEvent, true);
  } finally {
    admin.close();
    repository.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("confirmation consumes its exact proposal, creates one draft and finalizes atomically", async () => {
  const directory = mkdtempSync(join(tmpdir(), "moyuan-v2-confirmation-"));
  const path = join(directory, "agent.sqlite");
  const now = Date.parse("2026-08-02T10:00:00Z");
  const repository = new CommerceRepository(path, () => now);
  const admin = new DatabaseSync(path);
  try {
    const identity = repository.createIdentity();
    const proposalRun = repository.createRun({
      identity,
      message: "预算7000元，帮我选手机并搭配耳机和充电器",
      confirmed: false,
      idempotencyKey: "proposal-atomic",
    }).run;
    const agent = new SearchAdsRecsBuyerAgent({ now: () => now });
    const proposal = await agent.handle({
      sessionId: identity.sessionId,
      userId: identity.identityId,
      message: proposalRun.message,
    }, {
      discoveryContext: { executionRunId: proposalRun.runId },
    });
    assert.equal(proposal.phase, "proposal");
    assert.equal(proposal.decision?.runId, proposalRun.runId);
    repository.finalizeRun(
      proposalRun.runId,
      { status: "completed", result: proposal },
      "result",
      { phase: proposal.phase, result: proposal },
    );
    assert.ok(proposal.decision);
    repository.putPending(
      identity.sessionId,
      identity.identityId,
      proposal.decision,
      15 * 60 * 1_000,
    );
    const pending = repository.getPending(identity.sessionId, identity.identityId);
    assert.ok(pending);
    const prepared = await agent.handle({
      sessionId: identity.sessionId,
      userId: identity.identityId,
      message: "确认",
      confirmed: true,
      proposalRunId: proposalRun.runId,
    });
    assert.equal(prepared.phase, "cart_draft");
    assert.ok(prepared.cartDraft);

    const confirmationRun = repository.createRun({
      identity,
      message: "确认",
      confirmed: true,
      proposalRunId: proposalRun.runId,
      idempotencyKey: "confirm-atomic",
    }).run;
    repository.updateRun(confirmationRun.runId, { status: "running" });

    const newerDecision = { ...pending.decision, runId: "run_newer_proposal" };
    repository.putPending(
      identity.sessionId,
      identity.identityId,
      newerDecision,
      15 * 60 * 1_000,
    );
    assert.equal(
      repository.finalizeConfirmationRun(confirmationRun.runId, pending, prepared).status,
      "stale",
    );
    assert.equal(
      repository.getPending(identity.sessionId, identity.identityId)?.decision.runId,
      newerDecision.runId,
    );
    repository.putPending(
      identity.sessionId,
      identity.identityId,
      pending.decision,
      15 * 60 * 1_000,
    );

    const assertRolledBack = () => {
      assert.equal(repository.getRun(confirmationRun.runId)?.status, "running");
      assert.ok(repository.getPending(identity.sessionId, identity.identityId));
      assert.equal(repository.getDraft(prepared.cartDraft!.draftId), null);
      assert.equal(
        repository.listEvents(confirmationRun.runId, identity.identityId).length,
        0,
      );
    };

    admin.exec(`
      CREATE TRIGGER reject_confirmation_draft
      BEFORE INSERT ON cart_drafts
      BEGIN SELECT RAISE(ABORT, 'simulated draft failure'); END
    `);
    assert.throws(() => repository.finalizeConfirmationRun(
      confirmationRun.runId,
      pending,
      prepared,
    ), /simulated draft failure/);
    assertRolledBack();
    admin.exec("DROP TRIGGER reject_confirmation_draft");

    admin.exec(`
      CREATE TRIGGER reject_pending_consume
      BEFORE DELETE ON pending_decisions
      BEGIN SELECT RAISE(ABORT, 'simulated pending failure'); END
    `);
    assert.throws(() => repository.finalizeConfirmationRun(
      confirmationRun.runId,
      pending,
      prepared,
    ), /simulated pending failure/);
    assertRolledBack();
    admin.exec("DROP TRIGGER reject_pending_consume");

    admin.exec(`
      CREATE TRIGGER reject_confirmation_event
      BEFORE INSERT ON run_events
      WHEN NEW.event_type = 'result'
      BEGIN SELECT RAISE(ABORT, 'simulated confirmation event failure'); END
    `);
    assert.throws(() => repository.finalizeConfirmationRun(
      confirmationRun.runId,
      pending,
      prepared,
    ), /simulated confirmation event failure/);
    assertRolledBack();
    admin.exec("DROP TRIGGER reject_confirmation_event");

    const committed = repository.finalizeConfirmationRun(
      confirmationRun.runId,
      pending,
      prepared,
    );
    assert.equal(committed.status, "committed");
    assert.equal(repository.getRun(confirmationRun.runId)?.status, "completed");
    assert.equal(repository.getPending(identity.sessionId, identity.identityId), null);
    assert.equal(
      repository.getDraft(prepared.cartDraft.draftId)?.draftId,
      prepared.cartDraft.draftId,
    );
    assert.deepEqual(
      repository.listEvents(confirmationRun.runId, identity.identityId)
        .map((event) => event.eventType),
      ["result"],
    );
    const replayed = repository.finalizeConfirmationRun(
      confirmationRun.runId,
      pending,
      { ...prepared, message: "本地重算结果不得覆盖 canonical result" } as BuyerTurnReply,
    );
    assert.equal(replayed.status, "replayed");
    assert.equal(replayed.result?.message, prepared.message);
  } finally {
    admin.close();
    repository.close();
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
