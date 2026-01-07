import { EventEmitter } from "node:events";
import { randomUUID } from "node:crypto";
import type { SearchAdsRecsBuyerAgent } from "./buyer-agent.js";
import type { AgentTraceRecord } from "./contracts.js";
import type {
  AgentRun,
  AgentRunEvent,
  IdentitySession,
  RunEventType,
} from "./generated/contracts-v2.js";
import {
  CommerceRepository,
  GlobalRunCapacityError,
  RunLeaseUnavailableError,
  StaleRunLeaseError,
} from "./v2-repository.js";

class RunCancelledError extends Error {
  constructor() {
    super("run cancelled");
    this.name = "RunCancelledError";
  }
}

function isTerminal(run: AgentRun): boolean {
  return ["completed", "failed", "cancelled"].includes(run.status);
}

function traceEventType(trace: AgentTraceRecord): RunEventType {
  if (trace.event === "model_execution") return "model_execution";
  if (trace.event.includes("artifact")) return "artifact";
  if (trace.role === "critic" || trace.role === "cart") return "policy_gate";
  return "task";
}

export interface RunCreation {
  run: AgentRun;
  created: boolean;
}

export class PersistentRunManager {
  readonly #events = new EventEmitter();
  readonly #active = new Map<string, {
    controller: AbortController;
    executionOwner: string;
  }>();
  readonly #inFlightAttempts = new Set<string>();
  readonly #queued: string[] = [];
  readonly #managerId = `manager_${randomUUID()}`;
  readonly #leaseTimers = new Map<string, {
    executionOwner: string;
    timer: NodeJS.Timeout;
  }>();
  readonly #subscriptionPollers = new Set<NodeJS.Timeout>();
  #recoveryTimer: NodeJS.Timeout | null = null;
  #recoveryDueAt = Number.POSITIVE_INFINITY;
  #recoveryRetryMs = 100;
  #closed = false;

  constructor(
    private readonly repository: CommerceRepository,
    private readonly agent: SearchAdsRecsBuyerAgent | ((domainPackId: string) => SearchAdsRecsBuyerAgent),
    private readonly maxActiveRuns = 16,
    private readonly maxQueuedRuns = 256,
    private readonly leaseMs = 30_000,
  ) {
    this.#events.setMaxListeners(250);
  }

  create(input: {
    identity: IdentitySession;
    message: string;
    confirmed: boolean;
    idempotencyKey: string | null;
    domainPackId?: string;
    workflowId?: string;
    proposalRunId?: string | null;
    activeRunLimit?: number;
  }): RunCreation {
    if (this.#closed) throw new Error("run_manager_closed");
    const idempotencyReplay = input.idempotencyKey
      ? this.repository.findRunByIdempotency(input.identity.identityId, input.idempotencyKey)
      : null;
    const proposalReplay = input.proposalRunId
      ? this.repository.findConfirmationByProposal(input.identity.identityId, input.proposalRunId)
      : null;
    const retryNeedsExecution = Boolean(
      !idempotencyReplay &&
      input.proposalRunId &&
      this.repository.confirmationRetryNeedsExecution(
        input.identity.identityId,
        input.proposalRunId,
      ),
    );
    const requiresCapacity = !idempotencyReplay && (!proposalReplay || retryNeedsExecution);
    const capacityGranted = this.canAccept();
    if (requiresCapacity && !capacityGranted) throw new GlobalRunCapacityError();
    const creation = this.repository.createRunWithEvent({
      ...input,
      retryExecutionCapacityGranted: capacityGranted,
    });
    if (creation.created) {
      if (!creation.event) throw new Error("created_run_missing_ledger_event");
      this.#emit(creation.event);
      if (!this.#enqueue(creation.run.runId)) this.#scheduleRecoveryWake(1);
    }
    return creation;
  }

  resumeIncomplete(): number {
    try {
      const resumed = this.#fillQueueFromRepository();
      if (resumed > 0) queueMicrotask(() => this.#drain());
      return resumed;
    } catch (error) {
      this.#retryRecovery(error, "Run recovery scan failed");
      return 0;
    }
  }

  close(): void {
    this.#closed = true;
    if (this.#recoveryTimer) clearTimeout(this.#recoveryTimer);
    this.#recoveryTimer = null;
    for (const active of this.#active.values()) {
      active.controller.abort(new StaleRunLeaseError());
    }
    for (const lease of this.#leaseTimers.values()) clearTimeout(lease.timer);
    this.#leaseTimers.clear();
    for (const poller of this.#subscriptionPollers) clearInterval(poller);
    this.#subscriptionPollers.clear();
  }

  canAccept(): boolean {
    return this.#inFlightAttempts.size + this.#queued.length <
      this.maxActiveRuns + this.maxQueuedRuns;
  }

  findByIdempotency(identityId: string, idempotencyKey: string): AgentRun | null {
    return this.repository.findRunByIdempotency(identityId, idempotencyKey);
  }

  findConfirmation(identityId: string, proposalRunId: string): AgentRun | null {
    return this.repository.findConfirmationByProposal(identityId, proposalRunId);
  }

  get(runId: string, identityId: string): AgentRun | null {
    return this.repository.getRun(runId, identityId);
  }

  events(runId: string, identityId: string, afterSequence = 0): AgentRunEvent[] {
    return this.repository.listCurrentAttemptEvents(runId, identityId, afterSequence);
  }

  cancel(runId: string, identityId: string): AgentRun | null {
    const cancellation = this.repository.cancelRun(runId, identityId);
    if (!cancellation) return null;
    if (cancellation.event) this.#emit(cancellation.event);
    if (cancellation.run.status === "cancelled") {
      for (let index = this.#queued.length - 1; index >= 0; index -= 1) {
        if (this.#queued[index] === runId) this.#queued.splice(index, 1);
      }
      const active = this.#active.get(runId);
      active?.controller.abort();
      if (active && this.#active.get(runId)?.executionOwner === active.executionOwner) {
        this.#active.delete(runId);
      }
      const lease = this.#leaseTimers.get(runId);
      if (lease && (!active || lease.executionOwner === active.executionOwner)) {
        clearTimeout(lease.timer);
        this.#leaseTimers.delete(runId);
      }
      try {
        this.#fillQueueFromRepository();
        this.#drain();
      } catch (error) {
        this.#retryRecovery(error, "Run cancellation refill failed");
      }
    }
    return cancellation.run;
  }

  subscribe(runId: string, listener: (event: AgentRunEvent) => void): () => void {
    const name = `run:${runId}`;
    let cursor = 0;
    let closed = false;
    const wrapped = (event: AgentRunEvent) => {
      if (closed || event.sequence <= cursor) return;
      cursor = event.sequence;
      listener(event);
    };
    this.#events.on(name, wrapped);
    try {
      const run = this.repository.getRun(runId);
      if (run) {
        cursor = this.repository.listCurrentAttemptEvents(runId, run.identityId).at(-1)?.sequence ?? 0;
      }
    } catch {
      // The durable poll below will retry while the manager remains open.
    }
    const poller = setInterval(() => {
      if (this.#closed || closed) return;
      try {
        const run = this.repository.getRun(runId);
        if (!run) return;
        for (const event of this.repository.listCurrentAttemptEvents(
          runId,
          run.identityId,
          cursor,
        )) wrapped(event);
      } catch (error) {
        if (!this.#closed) {
          console.error("Run event ledger poll failed", {
            runId,
            errorType: error instanceof Error ? error.name : "unknown",
          });
        }
      }
    }, 250);
    poller.unref();
    this.#subscriptionPollers.add(poller);
    return () => {
      closed = true;
      this.#events.off(name, wrapped);
      clearInterval(poller);
      this.#subscriptionPollers.delete(poller);
    };
  }

  #emit(event: AgentRunEvent): void {
    // SSE notification is best effort after the durable ledger commit. A faulty
    // subscriber must not turn a successful business Run into a failed Run.
    for (const listener of this.#events.listeners(`run:${event.runId}`)) {
      try {
        (listener as (value: AgentRunEvent) => void)(event);
      } catch (error) {
        console.error("Run event subscriber failed", {
          runId: event.runId,
          eventType: event.eventType,
          errorType: error instanceof Error ? error.name : "unknown",
        });
      }
    }
  }

  #enqueue(runId: string): boolean {
    if (this.#closed) return false;
    if (this.#active.has(runId) || this.#queued.includes(runId)) return false;
    if (!this.canAccept()) return false;
    this.#queued.push(runId);
    queueMicrotask(() => this.#drain());
    return true;
  }

  #fillQueueFromRepository(): number {
    if (this.#closed) return 0;
    let added = 0;
    for (const run of this.repository.listClaimableRuns()) {
      if (!this.canAccept()) break;
      if (this.#active.has(run.runId) || this.#queued.includes(run.runId)) continue;
      this.#queued.push(run.runId);
      added += 1;
    }
    this.#scheduleRecoveryWake();
    this.#recoveryRetryMs = 100;
    return added;
  }

  #retryRecovery(error: unknown, message: string): void {
    if (this.#closed) return;
    console.error(message, {
      errorType: error instanceof Error ? error.name : "unknown",
    });
    const delay = this.#recoveryRetryMs;
    this.#recoveryRetryMs = Math.min(5_000, delay * 2);
    this.#scheduleRecoveryWake(delay);
  }

  #scheduleRecoveryWake(delay = this.repository.nextRunClaimDelay()): void {
    if (this.#closed) return;
    // A lease expiry gives us an exact wake-up. With no active lease, retain a
    // low-frequency distributed poll so this process can recover a queued Run
    // committed by another process that exits before its local drain begins.
    const wakeDelay = delay ?? 1_000;
    const dueAt = Date.now() + wakeDelay;
    if (this.#recoveryTimer && this.#recoveryDueAt <= dueAt) return;
    if (this.#recoveryTimer) clearTimeout(this.#recoveryTimer);
    this.#recoveryDueAt = dueAt;
    this.#recoveryTimer = setTimeout(() => {
      this.#recoveryTimer = null;
      this.#recoveryDueAt = Number.POSITIVE_INFINITY;
      try {
        this.#fillQueueFromRepository();
        this.#drain();
      } catch (error) {
        this.#retryRecovery(error, "Run lease recovery sweep failed");
      }
    }, Math.max(1, wakeDelay + 1));
    this.#recoveryTimer.unref();
  }

  #renewLease(
    runId: string,
    executionOwner: string,
    controller: AbortController,
  ): void {
    const existing = this.#leaseTimers.get(runId);
    if (existing) clearTimeout(existing.timer);
    const timer = setTimeout(() => {
      const currentLease = this.#leaseTimers.get(runId);
      if (currentLease?.executionOwner === executionOwner) this.#leaseTimers.delete(runId);
      const active = this.#active.get(runId);
      if (
        this.#closed ||
        !active ||
        active.executionOwner !== executionOwner
      ) return;
      try {
        if (!this.repository.renewRunLease(runId, executionOwner, this.leaseMs)) {
          controller.abort(new StaleRunLeaseError());
          return;
        }
        this.#renewLease(runId, executionOwner, controller);
      } catch {
        controller.abort(new StaleRunLeaseError());
      }
    }, Math.max(10, Math.floor(this.leaseMs / 3)));
    timer.unref();
    this.#leaseTimers.set(runId, { executionOwner, timer });
  }

  #drain(): void {
    if (this.#closed) return;
    while (this.#inFlightAttempts.size < this.maxActiveRuns) {
      const runId = this.#queued.shift();
      if (!runId) return;
      void this.#execute(runId).catch((error) => {
        console.error("Unhandled Run execution failure", {
          runId,
          errorType: error instanceof Error ? error.name : "unknown",
        });
      });
    }
  }

  #cancelled(runId: string, signal: AbortSignal): boolean {
    return signal.aborted || this.repository.getRun(runId)?.cancelRequested === true;
  }

  #appendTrace(
    runId: string,
    trace: AgentTraceRecord,
    signal: AbortSignal,
    executionOwner: string,
  ): void {
    if (this.#cancelled(runId, signal)) throw new RunCancelledError();
    this.#emit(this.repository.persistTraceAndEvent(
      runId,
      trace,
      traceEventType(trace),
      { role: trace.role, event: trace.event, traceSequence: trace.sequence, ...trace.detail },
      typeof trace.detail.taskId === "string" ? trace.detail.taskId : null,
      typeof trace.detail.parentTaskId === "string" ? trace.detail.parentTaskId : null,
      executionOwner,
      this.leaseMs,
    ));
  }

  #finalizeSafely(
    runId: string,
    update: { status: "completed" | "failed" | "cancelled"; result?: unknown; errorCode?: string },
    eventType: "result" | "run_failed" | "run_cancelled",
    payload: Record<string, unknown>,
    executionOwner: string,
  ): void {
    try {
      this.#emit(this.repository.finalizeRun(
        runId,
        update,
        eventType,
        payload,
        executionOwner,
      ));
    } catch (error) {
      if (
        error instanceof StaleRunLeaseError ||
        (error instanceof Error && error.message === "run_already_terminal")
      ) return;
      console.error("Run terminalization failed", {
        runId,
        eventType,
        errorType: error instanceof Error ? error.name : "unknown",
      });
    }
  }

  async #execute(runId: string): Promise<void> {
    if (this.#active.has(runId)) return;
    const controller = new AbortController();
    const executionOwner = `${this.#managerId}:attempt_${randomUUID()}`;
    let resultFinalized = false;
    let resultEvent: AgentRunEvent | null = null;
    let claimed = false;
    this.#inFlightAttempts.add(executionOwner);
    this.#active.set(runId, { controller, executionOwner });
    try {
      const initial = this.repository.getRun(runId);
      if (!initial || isTerminal(initial)) return;
      if (initial.cancelRequested) {
        const cancellation = this.repository.cancelRun(runId, initial.identityId);
        if (cancellation?.event) this.#emit(cancellation.event);
        return;
      }
      this.#emit(this.repository.startRun(
        runId,
        initial.status === "running",
        executionOwner,
        this.leaseMs,
      ));
      claimed = true;
      this.#renewLease(runId, executionOwner, controller);
      if (initial.confirmed && !initial.proposalRunId) {
        throw new Error("persisted_confirmation_missing_proposal_run_id");
      }
      const agent = typeof this.agent === "function"
        ? this.agent(initial.domainPackId)
        : this.agent;
      if (
        agent.domain.packId !== initial.domainPackId ||
        agent.domain.workflowId !== initial.workflowId
      ) {
        throw new Error("persisted_run_extension_mismatch");
      }
      const result = await agent.handle(
        {
          sessionId: initial.sessionId,
          userId: initial.identityId,
          message: initial.message,
          ...(initial.confirmed ? { confirmed: true } : {}),
          ...(initial.proposalRunId ? { proposalRunId: initial.proposalRunId } : {}),
        },
        {
          onTrace: (trace) => this.#appendTrace(
            runId,
            trace,
            controller.signal,
            executionOwner,
          ),
          signal: controller.signal,
          discoveryContext: {
            ...this.repository.discoveryContext(initial.identityId),
            executionRunId: initial.runId,
          },
          commitDecision: ({ result }) => {
            if (this.#cancelled(runId, controller.signal)) throw new RunCancelledError();
            const finalized = this.repository.finalizeDecisionRun(
              runId,
              result,
              executionOwner,
            );
            resultFinalized = true;
            resultEvent = finalized.event;
            return finalized;
          },
          commitConfirmation: ({ pending, result }) => {
            if (this.#cancelled(runId, controller.signal)) throw new RunCancelledError();
            const finalized = this.repository.finalizeConfirmationRun(
              runId,
              pending,
              result,
              executionOwner,
            );
            if (finalized.status !== "stale") {
              resultFinalized = true;
              resultEvent = finalized.event;
            }
            return finalized;
          },
        },
      );
      if (this.#cancelled(runId, controller.signal)) throw new RunCancelledError();
      if (resultFinalized) {
        if (resultEvent) this.#emit(resultEvent);
        return;
      }
      this.repository.persistResultConstraints(initial, result);
      this.#finalizeSafely(runId, { status: "completed", result }, "result", {
        phase: result.phase,
        result,
      }, executionOwner);
    } catch (error) {
      if (resultFinalized) {
        if (resultEvent) this.#emit(resultEvent);
        return;
      }
      if (this.#closed) return;
      if (!claimed) {
        if (error instanceof RunLeaseUnavailableError) {
          this.#scheduleRecoveryWake(error.retryAfterMs);
        } else {
          console.error("Run claim failed", {
            runId,
            errorType: error instanceof Error ? error.name : "unknown",
          });
          this.#scheduleRecoveryWake(100);
        }
        return;
      }
      if (
        error instanceof StaleRunLeaseError ||
        controller.signal.reason instanceof StaleRunLeaseError
      ) {
        return;
      }
      if (error instanceof RunCancelledError || controller.signal.aborted) {
        this.#finalizeSafely(
          runId,
          { status: "cancelled", errorCode: "run_cancelled" },
          "run_cancelled",
          { reason: "client_requested" },
          executionOwner,
        );
      } else {
        this.#finalizeSafely(
          runId,
          { status: "failed", errorCode: "agent_execution_failed" },
          "run_failed",
          { errorType: error instanceof Error ? error.name : "unknown" },
          executionOwner,
        );
      }
    } finally {
      const lease = this.#leaseTimers.get(runId);
      if (lease?.executionOwner === executionOwner) {
        clearTimeout(lease.timer);
        this.#leaseTimers.delete(runId);
      }
      if (this.#active.get(runId)?.executionOwner === executionOwner) {
        this.#active.delete(runId);
      }
      this.#inFlightAttempts.delete(executionOwner);
      try {
        this.#fillQueueFromRepository();
        this.#drain();
      } catch (error) {
        this.#retryRecovery(error, "Run completion refill failed");
      }
    }
  }
}
