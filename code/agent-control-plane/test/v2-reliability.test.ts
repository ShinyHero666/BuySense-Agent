import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { SearchAdsRecsBuyerAgent } from "../src/buyer-agent.js";
import type {
  BuyerTurnReply,
  DecisionEvidenceGateway,
} from "../src/contracts.js";
import { loadCatalogForDomainPack, NORMAL_3C_DOMAIN } from "../src/domain-pack.js";
import { InMemoryDecisionEvidenceGateway } from "../src/evidence.js";
import {
  CommerceRepository,
  GlobalRunCapacityError,
  SqliteCartDraftStore,
  SqlitePendingDecisionStore,
  StaleRunLeaseError,
} from "../src/v2-repository.js";
import { PersistentRunManager } from "../src/v2-runs.js";

const sleep = (milliseconds: number) =>
  new Promise<void>((resolve) => setTimeout(resolve, milliseconds));

async function awaitTerminal(
  repository: CommerceRepository,
  runId: string,
): Promise<ReturnType<CommerceRepository["getRun"]>> {
  for (let attempt = 0; attempt < 500; attempt += 1) {
    const run = repository.getRun(runId);
    if (run && ["completed", "failed", "cancelled"].includes(run.status)) return run;
    await sleep(4);
  }
  throw new Error(`Run did not become terminal: ${runId}`);
}

function syntheticReply(message = "done"): BuyerTurnReply {
  return {
    phase: "no_pending_decision",
    message,
    decision: null,
    cartDraft: null,
    confirmationTrace: [],
  };
}

test("two managers fence a shared queued Run to one execution and one terminal event", async () => {
  const directory = mkdtempSync(join(tmpdir(), "moyuan-run-lease-"));
  const path = join(directory, "agent.sqlite");
  const seed = new CommerceRepository(path);
  const identity = seed.createIdentity();
  const queued = seed.createRunWithEvent({
    identity,
    message: "execute once",
    confirmed: false,
    idempotencyKey: "lease-once",
  }).run;
  seed.close();

  const firstRepository = new CommerceRepository(path);
  const secondRepository = new CommerceRepository(path);
  const observerRepository = new CommerceRepository(path);
  let handles = 0;
  const agent = {
    domain: NORMAL_3C_DOMAIN,
    handle: async () => {
      handles += 1;
      await sleep(35);
      return syntheticReply();
    },
  } as unknown as SearchAdsRecsBuyerAgent;
  const first = new PersistentRunManager(firstRepository, agent, 1, 4, 1_000);
  const second = new PersistentRunManager(secondRepository, agent, 1, 4, 1_000);
  const observer = new PersistentRunManager(observerRepository, agent, 1, 4, 1_000);
  const observed: string[] = [];
  const unsubscribe = observer.subscribe(
    queued.runId,
    (event) => observed.push(event.eventType),
  );
  const unhandled: unknown[] = [];
  const captureUnhandled = (reason: unknown) => unhandled.push(reason);
  process.on("unhandledRejection", captureUnhandled);
  try {
    assert.equal(first.resumeIncomplete(), 1);
    assert.equal(second.resumeIncomplete(), 1);
    const terminal = await awaitTerminal(firstRepository, queued.runId);
    for (let attempt = 0; attempt < 100 && !observed.includes("result"); attempt += 1) {
      await sleep(5);
    }
    assert.equal(terminal?.status, "completed");
    assert.equal(handles, 1);
    const events = firstRepository.listEvents(queued.runId, identity.identityId);
    assert.equal(events.filter((event) => event.eventType === "run_started").length, 1);
    assert.equal(events.filter((event) => event.eventType === "result").length, 1);
    assert.equal(events.at(-1)?.eventType, "result");
    assert.ok(observed.includes("run_started"));
    assert.ok(observed.includes("result"));
    assert.deepEqual(unhandled, []);
  } finally {
    unsubscribe();
    process.off("unhandledRejection", captureUnhandled);
    first.close();
    second.close();
    observer.close();
    firstRepository.close();
    secondRepository.close();
    observerRepository.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("durable cancellation wins over a late leased worker", () => {
  let now = Date.parse("2026-08-09T10:00:00Z");
  const repository = new CommerceRepository(":memory:", () => now);
  try {
    const identity = repository.createIdentity();
    const run = repository.createRunWithEvent({
      identity,
      message: "cancel me",
      confirmed: false,
      idempotencyKey: null,
    }).run;
    repository.startRun(run.runId, false, "worker-a", 1_000);
    const cancelled = repository.cancelRun(run.runId, identity.identityId);
    assert.equal(cancelled?.run.status, "cancelled");
    assert.equal(cancelled?.event?.eventType, "run_cancelled");
    assert.throws(() => repository.finalizeRun(
      run.runId,
      { status: "completed", result: syntheticReply("late") },
      "result",
      { phase: "no_pending_decision" },
      "worker-a",
    ), StaleRunLeaseError);
    assert.equal(repository.getRun(run.runId)?.status, "cancelled");

    const requested = repository.createRunWithEvent({
      identity,
      message: "cancel flag race",
      confirmed: false,
      idempotencyKey: null,
    }).run;
    repository.startRun(requested.runId, false, "worker-b", 1_000);
    repository.requestCancellation(requested.runId, identity.identityId);
    assert.throws(() => repository.finalizeRun(
      requested.runId,
      { status: "completed", result: syntheticReply("must not win") },
      "result",
      { phase: "no_pending_decision" },
      "worker-b",
    ), StaleRunLeaseError);
    assert.equal(repository.cancelRun(requested.runId, identity.identityId)?.run.status, "cancelled");
    now += 1;
  } finally {
    repository.close();
  }
});

test("a stale proposal worker cannot publish pending state after lease takeover", async () => {
  let now = Date.parse("2026-08-09T11:00:00Z");
  const repository = new CommerceRepository(":memory:", () => now);
  try {
    const identity = repository.createIdentity();
    const run = repository.createRunWithEvent({
      identity,
      message: "总预算7000元，帮我选手机并搭配耳机和充电器",
      confirmed: false,
      idempotencyKey: null,
    }).run;
    const result = await new SearchAdsRecsBuyerAgent({ now: () => now }).handle({
      sessionId: identity.sessionId,
      userId: identity.identityId,
      message: run.message,
    }, {
      discoveryContext: { executionRunId: run.runId },
    });
    assert.equal(result.phase, "proposal");
    repository.startRun(run.runId, false, "worker-old", 100);
    now += 101;
    repository.startRun(run.runId, true, "worker-new", 100);
    assert.throws(
      () => repository.finalizeDecisionRun(run.runId, result, "worker-old"),
      StaleRunLeaseError,
    );
    assert.equal(repository.getPending(identity.sessionId, identity.identityId), null);
    repository.finalizeDecisionRun(run.runId, result, "worker-new");
    assert.equal(
      repository.getPending(identity.sessionId, identity.identityId)?.decision.runId,
      run.runId,
    );
    assert.deepEqual(
      repository.listEvents(run.runId, identity.identityId)
        .filter((event) => ["run_started", "result"].includes(event.eventType))
        .map((event) => event.eventType),
      ["run_started", "run_started", "result"],
    );
  } finally {
    repository.close();
  }
});

test("cancelled attempts keep consuming capacity until an abort-ignoring worker settles", async () => {
  const repository = new CommerceRepository();
  const identity = repository.createIdentity();
  const proposalRun = repository.createRunWithEvent({
    identity,
    message: "总预算7000元，帮我选手机并搭配耳机和充电器",
    confirmed: false,
    idempotencyKey: null,
  }).run;
  const proposal = await new SearchAdsRecsBuyerAgent().handle({
    sessionId: identity.sessionId,
    userId: identity.identityId,
    message: proposalRun.message,
  }, {
    discoveryContext: { executionRunId: proposalRun.runId },
  });
  assert.equal(proposal.phase, "proposal");
  repository.finalizeDecisionRun(proposalRun.runId, proposal);

  let handles = 0;
  const releases: Array<() => void> = [];
  const ignoringAgent = {
    domain: NORMAL_3C_DOMAIN,
    handle: async () => new Promise<BuyerTurnReply>((resolve) => {
      handles += 1;
      releases.push(() => resolve(syntheticReply("late worker")));
    }),
  } as unknown as SearchAdsRecsBuyerAgent;
  const manager = new PersistentRunManager(repository, ignoringAgent, 1, 0);
  try {
    const first = manager.create({
      identity,
      message: "确认",
      confirmed: true,
      proposalRunId: proposalRun.runId,
      idempotencyKey: "zombie-a",
    });
    for (let attempt = 0; attempt < 100 && handles < 1; attempt += 1) await sleep(2);
    assert.equal(handles, 1);
    assert.equal(manager.cancel(first.run.runId, identity.identityId)?.status, "cancelled");

    const retryInput = {
      identity,
      message: "确认",
      confirmed: true,
      proposalRunId: proposalRun.runId,
      idempotencyKey: "zombie-b",
    } as const;
    assert.throws(() => manager.create(retryInput), GlobalRunCapacityError);
    assert.equal(handles, 1);
    assert.equal(manager.canAccept(), false);
    assert.equal(repository.getRun(first.run.runId)?.status, "cancelled");

    releases.shift()?.();
    for (let attempt = 0; attempt < 100 && !manager.canAccept(); attempt += 1) await sleep(2);
    const retry = manager.create(retryInput);
    assert.equal(retry.created, true);
    assert.equal(retry.run.runId, first.run.runId);
    for (let attempt = 0; attempt < 100 && handles < 2; attempt += 1) await sleep(2);
    assert.equal(handles, 2);
    assert.equal(manager.cancel(retry.run.runId, identity.identityId)?.status, "cancelled");
    releases.shift()?.();
    await sleep(10);
    assert.equal(repository.getRun(retry.run.runId)?.status, "cancelled");
  } finally {
    manager.close();
    for (const release of releases) release();
    repository.close();
  }
});

test("lease recovery retries after a transient repository scan failure", async () => {
  class TransientScanRepository extends CommerceRepository {
    listCalls = 0;

    override listClaimableRuns() {
      this.listCalls += 1;
      if (this.listCalls === 2) throw new Error("simulated transient scan failure");
      return super.listClaimableRuns();
    }
  }

  const repository = new TransientScanRepository();
  const identity = repository.createIdentity();
  const run = repository.createRunWithEvent({
    identity,
    message: "recover after one failed sweep",
    confirmed: false,
    idempotencyKey: null,
  }).run;
  repository.startRun(run.runId, false, "dead-worker", 30);
  let handles = 0;
  const agent = {
    domain: NORMAL_3C_DOMAIN,
    handle: async () => {
      handles += 1;
      return syntheticReply("recovered");
    },
  } as unknown as SearchAdsRecsBuyerAgent;
  const manager = new PersistentRunManager(repository, agent, 1, 4, 1_000);
  try {
    assert.equal(manager.resumeIncomplete(), 0);
    const terminal = await awaitTerminal(repository, run.runId);
    assert.equal(terminal?.status, "completed");
    assert.equal(handles, 1);
    assert.ok(repository.listCalls >= 3);
  } finally {
    manager.close();
    repository.close();
  }
});

test("an idle manager discovers a queued Run abandoned by another process", async () => {
  const directory = mkdtempSync(join(tmpdir(), "moyuan-distributed-queue-"));
  const path = join(directory, "agent.sqlite");
  const observerRepository = new CommerceRepository(path);
  const creatorRepository = new CommerceRepository(path);
  let handles = 0;
  const agent = {
    domain: NORMAL_3C_DOMAIN,
    handle: async () => {
      handles += 1;
      return syntheticReply("claimed from shared queue");
    },
  } as unknown as SearchAdsRecsBuyerAgent;
  const observer = new PersistentRunManager(observerRepository, agent, 1, 4, 1_000);
  const creator = new PersistentRunManager(creatorRepository, agent, 1, 4, 1_000);
  try {
    assert.equal(observer.resumeIncomplete(), 0);
    const identity = creatorRepository.createIdentity();
    const abandoned = creator.create({
      identity,
      message: "creator exits before local drain",
      confirmed: false,
      idempotencyKey: "distributed-queued-run",
    }).run;
    // close() runs synchronously before create()'s queueMicrotask can drain.
    creator.close();
    const terminal = await awaitTerminal(observerRepository, abandoned.runId);
    assert.equal(terminal?.status, "completed");
    assert.equal(handles, 1);
  } finally {
    observer.close();
    creator.close();
    observerRepository.close();
    creatorRepository.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("legacy confirmation Runs without a proposal target fail closed on recovery", async () => {
  const directory = mkdtempSync(join(tmpdir(), "moyuan-legacy-confirmation-"));
  const path = join(directory, "agent.sqlite");
  const repository = new CommerceRepository(path);
  const { DatabaseSync } = await import("node:sqlite");
  const admin = new DatabaseSync(path);
  const identity = repository.createIdentity();
  const legacy = repository.createRunWithEvent({
    identity,
    message: "legacy confirm",
    confirmed: false,
    idempotencyKey: null,
  }).run;
  admin.prepare(`
    UPDATE runs SET confirmed = 1, proposal_run_id = NULL WHERE run_id = ?
  `).run(legacy.runId);
  let handled = false;
  const agent = {
    domain: NORMAL_3C_DOMAIN,
    handle: async () => {
      handled = true;
      return syntheticReply();
    },
  } as unknown as SearchAdsRecsBuyerAgent;
  const manager = new PersistentRunManager(repository, agent);
  try {
    assert.equal(manager.resumeIncomplete(), 1);
    const terminal = await awaitTerminal(repository, legacy.runId);
    assert.equal(terminal?.status, "failed");
    assert.equal(handled, false);
    assert.equal(
      repository.listEvents(legacy.runId, identity.identityId).at(-1)?.eventType,
      "run_failed",
    );
  } finally {
    manager.close();
    admin.close();
    repository.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("proposal publication, constraints and result event share one fenced transaction", async () => {
  const directory = mkdtempSync(join(tmpdir(), "moyuan-proposal-atomic-"));
  const path = join(directory, "agent.sqlite");
  const repository = new CommerceRepository(path);
  const { DatabaseSync } = await import("node:sqlite");
  const admin = new DatabaseSync(path);
  try {
    const identity = repository.createIdentity();
    const sourceRun = repository.createRunWithEvent({
      identity,
      message: "总预算7000元，帮我选手机并搭配耳机和充电器",
      confirmed: false,
      idempotencyKey: null,
    }).run;
    const agent = new SearchAdsRecsBuyerAgent();
    const sourceReply = await agent.handle({
      sessionId: identity.sessionId,
      userId: identity.identityId,
      message: sourceRun.message,
    }, {
      discoveryContext: { executionRunId: sourceRun.runId },
    });
    assert.equal(sourceReply.phase, "proposal");
    assert.ok(sourceReply.decision);

    admin.exec(`
      CREATE TRIGGER reject_proposal_result
      BEFORE INSERT ON run_events
      WHEN NEW.event_type = 'result'
      BEGIN SELECT RAISE(ABORT, 'simulated proposal event failure'); END
    `);
    assert.throws(
      () => repository.finalizeDecisionRun(sourceRun.runId, sourceReply),
      /simulated proposal event failure/,
    );
    assert.equal(repository.getRun(sourceRun.runId)?.status, "queued");
    assert.equal(repository.getPending(identity.sessionId, identity.identityId), null);
    assert.equal(repository.runDiagnostics(sourceRun.runId, identity.identityId)?.constraints, 0);
    assert.deepEqual(
      repository.listEvents(sourceRun.runId, identity.identityId).map((event) => event.eventType),
      ["run_created"],
    );
    admin.exec("DROP TRIGGER reject_proposal_result");
    repository.finalizeDecisionRun(sourceRun.runId, sourceReply);
    assert.equal(
      repository.getPending(identity.sessionId, identity.identityId)?.decision.runId,
      sourceRun.runId,
    );

    const replyFor = (runId: string, phase: "proposal" | "needs_replan" = "proposal") => ({
      ...sourceReply,
      phase,
      decision: { ...sourceReply.decision!, runId },
    }) satisfies BuyerTurnReply;

    const older = repository.createRunWithEvent({
      identity,
      message: "older proposal",
      confirmed: false,
      idempotencyKey: null,
    }).run;
    const newer = repository.createRunWithEvent({
      identity,
      message: "newer proposal",
      confirmed: false,
      idempotencyKey: null,
    }).run;
    const invalid = repository.createRunWithEvent({
      identity,
      message: "invalid extension result",
      confirmed: false,
      idempotencyKey: null,
    }).run;
    assert.throws(() => repository.finalizeDecisionRun(invalid.runId, {
      ...replyFor(invalid.runId),
      decision: {
        ...replyFor(invalid.runId).decision!,
        domainPackId: "outdoor-camping-v1",
      },
    }), /decision_result_extension_mismatch/);
    assert.equal(repository.getRun(invalid.runId)?.status, "queued");
    repository.finalizeDecisionRun(newer.runId, replyFor(newer.runId));
    repository.finalizeDecisionRun(older.runId, replyFor(older.runId));
    assert.equal(
      repository.getPending(identity.sessionId, identity.identityId)?.decision.runId,
      newer.runId,
    );

    const slowOlder = repository.createRunWithEvent({
      identity,
      message: "slow older proposal",
      confirmed: false,
      idempotencyKey: null,
    }).run;
    const newerReplan = repository.createRunWithEvent({
      identity,
      message: "newer replan",
      confirmed: false,
      idempotencyKey: null,
    }).run;
    repository.finalizeDecisionRun(
      newerReplan.runId,
      replyFor(newerReplan.runId, "needs_replan"),
    );
    repository.finalizeDecisionRun(slowOlder.runId, replyFor(slowOlder.runId));
    assert.equal(repository.getPending(identity.sessionId, identity.identityId), null);
  } finally {
    admin.close();
    repository.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("a failed confirmation can retry the same proposal without duplicating its draft", async () => {
  const repository = new CommerceRepository();
  const identity = repository.createIdentity();
  const baseEvidence = new InMemoryDecisionEvidenceGateway(
    loadCatalogForDomainPack(NORMAL_3C_DOMAIN),
  );
  let failNextQuote = false;
  const evidence: DecisionEvidenceGateway = {
    checkCompatibility: (primary, accessories) =>
      baseEvidence.checkCompatibility(primary, accessories),
    reviewAspects: (productIds) => baseEvidence.reviewAspects(productIds),
    quote: async (items, signal) => {
      if (failNextQuote) {
        failNextQuote = false;
        throw new Error("transient quote outage");
      }
      if (signal?.aborted) throw signal.reason;
      return baseEvidence.quote(items);
    },
  };
  const agent = new SearchAdsRecsBuyerAgent({
    evidence,
    pending: new SqlitePendingDecisionStore(repository),
    drafts: new SqliteCartDraftStore(repository),
  });
  const manager = new PersistentRunManager(repository, agent);
  try {
    const proposalCreation = manager.create({
      identity,
      message: "总预算7000元，帮我选手机并搭配耳机和充电器",
      confirmed: false,
      idempotencyKey: "retry-proposal",
    });
    const proposal = await awaitTerminal(repository, proposalCreation.run.runId);
    assert.equal(proposal?.status, "completed");
    assert.equal((proposal?.result as BuyerTurnReply).phase, "proposal");
    assert.equal(
      repository.getPending(identity.sessionId, identity.identityId)?.decision.runId,
      proposalCreation.run.runId,
    );

    failNextQuote = true;
    const firstAttempt = manager.create({
      identity,
      message: "确认",
      confirmed: true,
      proposalRunId: proposalCreation.run.runId,
      idempotencyKey: "confirm-attempt-a",
    });
    const failed = await awaitTerminal(repository, firstAttempt.run.runId);
    assert.equal(failed?.status, "failed");
    assert.ok(repository.getPending(identity.sessionId, identity.identityId));
    assert.throws(() => repository.createRunWithEvent({
      identity,
      message: "确认",
      confirmed: true,
      proposalRunId: proposalCreation.run.runId,
      idempotencyKey: "confirm-capacity-denied",
      retryExecutionCapacityGranted: false,
    }), GlobalRunCapacityError);
    assert.equal(repository.getRun(firstAttempt.run.runId)?.status, "failed");

    const retry = manager.create({
      identity,
      message: "确认",
      confirmed: true,
      proposalRunId: proposalCreation.run.runId,
      idempotencyKey: "confirm-attempt-b",
    });
    assert.equal(retry.created, true);
    assert.equal(retry.run.runId, firstAttempt.run.runId);
    const completed = await awaitTerminal(repository, retry.run.runId);
    assert.equal(completed?.status, "completed");
    const completedReply = completed?.result as BuyerTurnReply;
    assert.equal(completedReply.phase, "cart_draft");
    assert.ok(completedReply.cartDraft);
    assert.equal(repository.getPending(identity.sessionId, identity.identityId), null);

    const replay = manager.create({
      identity,
      message: "确认",
      confirmed: true,
      proposalRunId: proposalCreation.run.runId,
      idempotencyKey: "confirm-attempt-c",
    });
    assert.equal(replay.created, false);
    assert.equal(replay.run.runId, retry.run.runId);
    assert.equal(
      (replay.run.result as BuyerTurnReply).cartDraft?.draftId,
      completedReply.cartDraft.draftId,
    );
    const eventTypes = repository.listEvents(retry.run.runId, identity.identityId)
      .map((event) => event.eventType);
    assert.equal(eventTypes.filter((event) => event === "run_created").length, 2);
    assert.equal(eventTypes.filter((event) => event === "result").length, 1);
    const replayTypes = manager.events(retry.run.runId, identity.identityId)
      .map((event) => event.eventType);
    assert.equal(replayTypes[0], "run_created");
    assert.equal(replayTypes.includes("run_failed"), false);
    assert.equal(replayTypes.at(-1), "result");
  } finally {
    manager.close();
    repository.close();
  }
});
