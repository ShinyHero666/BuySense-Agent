import type { AgentRole } from "./contracts.js";
import {
  DEFAULT_COLLABORATION_GRAPH,
  type CollaborationGraphDefinition,
  type DelegatorRole,
} from "./extension-registry.js";
import type { TraceCollector } from "./role-agent.js";

export type CollaborationTaskStatus = "queued" | "running" | "completed" | "failed";

export interface CollaborationTask {
  taskId: string;
  parentTaskId: string | null;
  delegatedBy: AgentRole | "system";
  role: AgentRole;
  capability: string;
  status: CollaborationTaskStatus;
  depth: number;
  revisionAttempt: number;
  createdAt: string;
  updatedAt: string;
}

export interface CollaborationPolicy {
  maxTasks: number;
  maxDelegationProposals: number;
  maxDepth: number;
  maxConcurrent: number;
  maxModelCalls: number;
  maxRevisionAttempts: number;
  deadlineMs: number;
}

const DEFAULT_POLICY: CollaborationPolicy = {
  maxTasks: 18,
  maxDelegationProposals: 12,
  maxDepth: 4,
  maxConcurrent: 4,
  maxModelCalls: 6,
  maxRevisionAttempts: 1,
  deadlineMs: 90_000,
};

export type DelegationProposalStatus = "approved" | "rejected" | "consumed";

export interface DelegationProposal {
  proposalId: string;
  proposedBy: AgentRole;
  role: AgentRole;
  capability: string;
  parentTaskId: string | null;
  reason: string;
  status: DelegationProposalStatus;
  rejectionReason: string | null;
  createdAt: string;
}

/**
 * A bounded task board for free Agent collaboration.
 *
 * Roles may delegate only through the capability graph. The coordinator owns
 * depth, concurrency, revision and model-call budgets, so dynamic peer work can
 * never turn into an unbounded conversation loop.
 */
export class BoundedCollaborationCoordinator {
  readonly tasks: CollaborationTask[] = [];
  readonly proposals: DelegationProposal[] = [];
  readonly signal: AbortSignal;
  readonly #startedAt = performance.now();
  readonly #policy: CollaborationPolicy;
  readonly #allowedDelegations = new Map<DelegatorRole, ReadonlySet<AgentRole>>();
  readonly #roleCapabilities = new Map<AgentRole, ReadonlySet<string>>();
  #nextTask = 1;
  #running = 0;
  #modelCalls = 0;
  #nextProposal = 1;
  readonly #waiters: Array<() => void> = [];

  constructor(
    private readonly runId: string,
    private readonly trace: TraceCollector,
    policy: Partial<CollaborationPolicy> = {},
    graph: CollaborationGraphDefinition = DEFAULT_COLLABORATION_GRAPH,
  ) {
    this.#policy = { ...DEFAULT_POLICY, ...policy };
    for (const [source, targets] of Object.entries(graph.allowedDelegations)) {
      this.#allowedDelegations.set(source as DelegatorRole, new Set(targets));
    }
    for (const [role, capabilities] of Object.entries(graph.roleCapabilities)) {
      this.#roleCapabilities.set(role as AgentRole, new Set(capabilities));
    }
    this.signal = AbortSignal.timeout(this.#policy.deadlineMs);
  }

  get modelCalls(): number {
    return this.#modelCalls;
  }

  consumeModelCall(role: AgentRole): void {
    this.#assertDeadline();
    if (this.#modelCalls >= this.#policy.maxModelCalls) {
      throw new Error("collaboration_model_call_budget_exhausted");
    }
    this.#modelCalls += 1;
    this.trace.add(role, "model_budget_consumed", {
      used: this.#modelCalls,
      limit: this.#policy.maxModelCalls,
    });
  }

  proposeDelegation(input: {
    proposedBy: AgentRole;
    role: AgentRole;
    capability: string;
    parentTaskId?: string | null;
    reason: string;
  }): DelegationProposal {
    this.#assertDeadline();
    const duplicate = this.proposals.find((proposal) =>
      proposal.proposedBy === input.proposedBy &&
      proposal.role === input.role &&
      proposal.capability === input.capability &&
      proposal.parentTaskId === (input.parentTaskId ?? null) &&
      proposal.status !== "rejected"
    );
    if (duplicate) return duplicate;

    let rejectionReason: string | null = null;
    if (this.proposals.length >= this.#policy.maxDelegationProposals) {
      rejectionReason = "delegation_proposal_budget_exhausted";
    } else if (!this.#canDelegate(input.proposedBy, input.role)) {
      rejectionReason = `delegation_edge_denied:${input.proposedBy}->${input.role}`;
    } else if (!this.#hasCapability(input.role, input.capability)) {
      rejectionReason = `delegation_capability_denied:${input.role}:${input.capability}`;
    } else if (!input.reason.trim() || input.reason.length > 500) {
      rejectionReason = "delegation_reason_invalid";
    }
    const proposal: DelegationProposal = {
      proposalId: `${this.runId}:proposal-${String(this.#nextProposal++).padStart(3, "0")}`,
      proposedBy: input.proposedBy,
      role: input.role,
      capability: input.capability,
      parentTaskId: input.parentTaskId ?? null,
      reason: input.reason.trim().slice(0, 500),
      status: rejectionReason ? "rejected" : "approved",
      rejectionReason,
      createdAt: new Date().toISOString(),
    };
    this.proposals.push(proposal);
    this.trace.add(input.proposedBy, "delegation_proposal_reviewed", {
      proposalId: proposal.proposalId,
      to: proposal.role,
      capability: proposal.capability,
      status: proposal.status,
      rejectionReason,
    });
    return proposal;
  }

  takeApprovedProposal(input: {
    proposedBy: AgentRole;
    role: AgentRole;
    capability: string;
  }): DelegationProposal | null {
    const proposal = this.proposals.find((candidate) =>
      candidate.status === "approved" &&
      candidate.proposedBy === input.proposedBy &&
      candidate.role === input.role &&
      candidate.capability === input.capability
    );
    if (!proposal) return null;
    proposal.status = "consumed";
    this.trace.add(proposal.proposedBy, "delegation_proposal_consumed", {
      proposalId: proposal.proposalId,
      to: proposal.role,
      capability: proposal.capability,
    });
    return proposal;
  }

  async delegate<T>(input: {
    delegatedBy: AgentRole | "system";
    role: AgentRole;
    capability: string;
    parentTaskId?: string | null;
    depth?: number;
    revisionAttempt?: number;
    execute: (task: CollaborationTask) => Promise<T> | T;
  }): Promise<T> {
    this.#assertDeadline();
    if (!this.#canDelegate(input.delegatedBy, input.role)) {
      throw new Error(`collaboration_delegation_denied:${input.delegatedBy}->${input.role}`);
    }
    if (!this.#hasCapability(input.role, input.capability)) {
      throw new Error(`collaboration_capability_denied:${input.role}:${input.capability}`);
    }
    if (this.tasks.length >= this.#policy.maxTasks) {
      throw new Error("collaboration_task_budget_exhausted");
    }
    const depth = input.depth ?? 1;
    const revisionAttempt = input.revisionAttempt ?? 0;
    if (depth > this.#policy.maxDepth) throw new Error("collaboration_depth_exhausted");
    if (revisionAttempt > this.#policy.maxRevisionAttempts) {
      throw new Error("collaboration_revision_budget_exhausted");
    }
    const now = new Date().toISOString();
    const task: CollaborationTask = {
      taskId: `${this.runId}:task-${String(this.#nextTask++).padStart(3, "0")}`,
      parentTaskId: input.parentTaskId ?? null,
      delegatedBy: input.delegatedBy,
      role: input.role,
      capability: input.capability,
      status: "queued",
      depth,
      revisionAttempt,
      createdAt: now,
      updatedAt: now,
    };
    this.tasks.push(task);
    this.trace.add(input.delegatedBy === "system" ? "lead" : input.delegatedBy, "task_delegated", {
      taskId: task.taskId,
      parentTaskId: task.parentTaskId,
      to: task.role,
      capability: task.capability,
      revisionAttempt,
    });
    await this.#acquire();
    task.status = "running";
    task.updatedAt = new Date().toISOString();
    this.trace.add(task.role, "task_started", {
      taskId: task.taskId,
      capability: task.capability,
      parentTaskId: task.parentTaskId,
    });
    try {
      const result = await input.execute(task);
      task.status = "completed";
      task.updatedAt = new Date().toISOString();
      this.trace.add(task.role, "task_completed", {
        taskId: task.taskId,
        capability: task.capability,
      });
      return result;
    } catch (error) {
      task.status = "failed";
      task.updatedAt = new Date().toISOString();
      this.trace.add(task.role, "task_failed", {
        taskId: task.taskId,
        capability: task.capability,
        errorType: error instanceof Error ? error.name : "unknown",
      });
      throw error;
    } finally {
      this.#release();
    }
  }

  #canDelegate(delegatedBy: DelegatorRole, role: AgentRole): boolean {
    return this.#allowedDelegations.get(delegatedBy)?.has(role) ?? false;
  }

  #hasCapability(role: AgentRole, capability: string): boolean {
    return this.#roleCapabilities.get(role)?.has(capability) ?? false;
  }

  #assertDeadline(): void {
    if (this.signal.aborted || performance.now() - this.#startedAt > this.#policy.deadlineMs) {
      throw new Error("collaboration_deadline_exceeded");
    }
  }

  async #acquire(): Promise<void> {
    this.#assertDeadline();
    if (this.#running < this.#policy.maxConcurrent) {
      this.#running += 1;
      return;
    }
    await new Promise<void>((resolve, reject) => {
      const waiter = () => {
        this.signal.removeEventListener("abort", onAbort);
        resolve();
      };
      const onAbort = () => {
        const index = this.#waiters.indexOf(waiter);
        if (index >= 0) this.#waiters.splice(index, 1);
        reject(new Error("collaboration_deadline_exceeded"));
      };
      this.#waiters.push(waiter);
      this.signal.addEventListener("abort", onAbort, { once: true });
    });
    this.#running += 1;
  }

  #release(): void {
    this.#running -= 1;
    this.#waiters.shift()?.();
  }
}
