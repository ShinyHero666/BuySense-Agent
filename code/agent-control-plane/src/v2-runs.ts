import { EventEmitter } from "node:events";
import type { SearchAdsRecsBuyerAgent } from "./buyer-agent.js";
import type { AgentTraceRecord } from "./contracts.js";
import type {
  AgentRun,
  AgentRunEvent,
  IdentitySession,
  RunEventType,
} from "./generated/contracts-v2.js";
import { CommerceRepository } from "./v2-repository.js";

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
  readonly #active = new Map<string, AbortController>();
  readonly #queued: string[] = [];

  constructor(
    private readonly repository: CommerceRepository,
    private readonly agent: SearchAdsRecsBuyerAgent,
    private readonly maxActiveRuns = 16,
    private readonly maxQueuedRuns = 256,
  ) {
    this.#events.setMaxListeners(250);
  }

  create(input: {
    identity: IdentitySession;
    message: string;
    confirmed: boolean;
    idempotencyKey: string | null;
  }): RunCreation {
    if (input.idempotencyKey) {
      const replay = this.repository.findRunByIdempotency(
        input.identity.identityId,
        input.idempotencyKey,
      );
      if (replay) return { run: replay, created: false };
    }
    if (!this.canAccept()) throw new Error("global_run_capacity_exhausted");
    const creation = this.repository.createRun(input);
    if (creation.created) {
      this.#emit(this.repository.appendEvent(creation.run.runId, "run_created", {
        status: creation.run.status,
        confirmed: creation.run.confirmed,
      }));
      this.#enqueue(creation.run.runId);
    }
    return creation;
  }

  resumeIncomplete(): number {
    const resumed = this.#fillQueueFromRepository();
    if (resumed > 0) queueMicrotask(() => this.#drain());
    return resumed;
  }

  canAccept(): boolean {
    return this.#active.size + this.#queued.length < this.maxActiveRuns + this.maxQueuedRuns;
  }

  findByIdempotency(identityId: string, idempotencyKey: string): AgentRun | null {
    return this.repository.findRunByIdempotency(identityId, idempotencyKey);
  }

  get(runId: string, identityId: string): AgentRun | null {
    return this.repository.getRun(runId, identityId);
  }

  events(runId: string, identityId: string, afterSequence = 0): AgentRunEvent[] {
    return this.repository.listEvents(runId, identityId, afterSequence);
  }

  cancel(runId: string, identityId: string): AgentRun | null {
    const run = this.repository.requestCancellation(runId, identityId);
    if (run?.cancelRequested) this.#active.get(runId)?.abort();
    return run;
  }

  subscribe(runId: string, listener: (event: AgentRunEvent) => void): () => void {
    const name = `run:${runId}`;
    this.#events.on(name, listener);
    return () => this.#events.off(name, listener);
  }

  #emit(event: AgentRunEvent): void {
    this.#events.emit(`run:${event.runId}`, event);
  }

  #enqueue(runId: string): boolean {
    if (this.#active.has(runId) || this.#queued.includes(runId)) return false;
    if (!this.canAccept()) return false;
    this.#queued.push(runId);
    queueMicrotask(() => this.#drain());
    return true;
  }

  #fillQueueFromRepository(): number {
    let added = 0;
    for (const run of this.repository.listIncompleteRuns()) {
      if (!this.canAccept()) break;
      if (this.#active.has(run.runId) || this.#queued.includes(run.runId)) continue;
      this.#queued.push(run.runId);
      added += 1;
    }
    return added;
  }

  #drain(): void {
    while (this.#active.size < this.maxActiveRuns) {
      const runId = this.#queued.shift();
      if (!runId) return;
      void this.#execute(runId);
    }
  }

  #cancelled(runId: string, signal: AbortSignal): boolean {
    return signal.aborted || this.repository.getRun(runId)?.cancelRequested === true;
  }

  #appendTrace(runId: string, trace: AgentTraceRecord, signal: AbortSignal): void {
    if (this.#cancelled(runId, signal)) throw new RunCancelledError();
    this.repository.persistTrace(runId, trace);
    this.#emit(this.repository.appendEvent(
      runId,
      traceEventType(trace),
      { role: trace.role, event: trace.event, traceSequence: trace.sequence, ...trace.detail },
      typeof trace.detail.taskId === "string" ? trace.detail.taskId : null,
      typeof trace.detail.parentTaskId === "string" ? trace.detail.parentTaskId : null,
    ));
  }

  async #execute(runId: string): Promise<void> {
    if (this.#active.has(runId)) return;
    const controller = new AbortController();
    this.#active.set(runId, controller);
    try {
      const initial = this.repository.getRun(runId);
      if (!initial || isTerminal(initial)) return;
      if (initial.cancelRequested) throw new RunCancelledError();
      this.repository.updateRun(runId, { status: "running" });
      this.#emit(this.repository.appendEvent(runId, "run_started", { resumed: initial.status === "running" }));
      const result = await this.agent.handle(
        {
          sessionId: initial.sessionId,
          userId: initial.identityId,
          message: initial.message,
          ...(initial.confirmed ? { confirmed: true } : {}),
        },
        {
          onTrace: (trace) => this.#appendTrace(runId, trace, controller.signal),
          signal: controller.signal,
          discoveryContext: this.repository.discoveryContext(initial.identityId),
        },
      );
      if (this.#cancelled(runId, controller.signal)) throw new RunCancelledError();
      this.repository.persistResultConstraints(initial, result);
      this.repository.updateRun(runId, { status: "completed", result });
      this.#emit(this.repository.appendEvent(runId, "result", {
        phase: result.phase,
        result,
      }));
    } catch (error) {
      if (error instanceof RunCancelledError || controller.signal.aborted) {
        this.repository.updateRun(runId, { status: "cancelled", errorCode: "run_cancelled" });
        this.#emit(this.repository.appendEvent(runId, "run_cancelled", { reason: "client_requested" }));
      } else {
        this.repository.updateRun(runId, { status: "failed", errorCode: "agent_execution_failed" });
        this.#emit(this.repository.appendEvent(runId, "run_failed", {
          errorType: error instanceof Error ? error.name : "unknown",
        }));
      }
    } finally {
      this.#active.delete(runId);
      this.#fillQueueFromRepository();
      this.#drain();
    }
  }
}
