import type { AgentRole } from "./contracts.js";
import type { WorkflowId } from "./generated/contracts-v2.js";

export type CapabilityId = string;
export type DelegatorRole = AgentRole | "system";

export interface CapabilityDefinition {
  readonly id: CapabilityId;
  readonly version: string;
  readonly role: AgentRole;
}

export interface CollaborationGraphDefinition {
  readonly allowedDelegations: Readonly<
    Partial<Record<DelegatorRole, readonly AgentRole[]>>
  >;
  readonly roleCapabilities: Readonly<
    Partial<Record<AgentRole, readonly CapabilityId[]>>
  >;
}

export interface WorkflowDefinition {
  readonly id: WorkflowId;
  readonly version: string;
  readonly capabilityProfileId: string;
  readonly capabilityIds: readonly CapabilityId[];
  readonly allowedDelegations: CollaborationGraphDefinition["allowedDelegations"];
}

export interface RegisteredWorkflow {
  readonly id: WorkflowId;
  readonly version: string;
  readonly capabilityProfileId: string;
  readonly capabilityIds: readonly CapabilityId[];
  readonly graph: CollaborationGraphDefinition;
}

const IDENTIFIER_PATTERN = /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/;
const WORKFLOW_IDENTIFIER_PATTERN = /^[a-z][a-z0-9-]*-v[0-9]+$/;

function identifier(value: string, field: string): string {
  if (!IDENTIFIER_PATTERN.test(value) || value.length > 120) {
    throw new Error(`${field} must be a lowercase identifier of at most 120 characters`);
  }
  return value;
}

function workflowIdentifier(value: string): WorkflowId {
  if (!WORKFLOW_IDENTIFIER_PATTERN.test(value) || value.length > 64) {
    throw new Error("workflow id must be a lowercase, version-suffixed identifier");
  }
  return value;
}

function version(value: string, field: string): string {
  const normalized = value.trim();
  if (!normalized || normalized.length > 64) {
    throw new Error(`${field} must be a non-empty version of at most 64 characters`);
  }
  return normalized;
}

function unique<T extends string>(values: readonly T[], field: string): readonly T[] {
  if (new Set(values).size !== values.length) throw new Error(`${field} must be unique`);
  return Object.freeze([...values]);
}

/** Registry for versioned capabilities and the role allowed to execute each one. */
export class CapabilityRegistry {
  readonly #items = new Map<CapabilityId, CapabilityDefinition>();

  constructor(definitions: readonly CapabilityDefinition[] = []) {
    for (const definition of definitions) this.register(definition);
  }

  register(input: CapabilityDefinition): this {
    const id = identifier(input.id, "capability id");
    if (this.#items.has(id)) throw new Error(`duplicate capability: ${id}`);
    const definition: CapabilityDefinition = Object.freeze({
      id,
      version: version(input.version, `capability ${id} version`),
      role: input.role,
    });
    this.#items.set(id, definition);
    return this;
  }

  get(id: CapabilityId): CapabilityDefinition | undefined {
    return this.#items.get(id);
  }

  require(id: CapabilityId): CapabilityDefinition {
    const definition = this.get(id);
    if (!definition) throw new Error(`unknown capability: ${id}`);
    return definition;
  }

  list(): readonly CapabilityDefinition[] {
    return Object.freeze(
      [...this.#items.values()].sort((left, right) => left.id.localeCompare(right.id)),
    );
  }
}

/**
 * Registry for bounded workflows.
 *
 * Registration resolves capability references once and produces an immutable
 * collaboration graph that can be injected into the coordinator.
 */
export class WorkflowRegistry {
  readonly #items = new Map<WorkflowId, RegisteredWorkflow>();

  constructor(
    private readonly capabilities: CapabilityRegistry,
    definitions: readonly WorkflowDefinition[] = [],
  ) {
    for (const definition of definitions) this.register(definition);
  }

  register(input: WorkflowDefinition): this {
    const id = workflowIdentifier(input.id);
    if (this.#items.has(id)) throw new Error(`duplicate workflow: ${id}`);
    const capabilityIds = unique(input.capabilityIds, `workflow ${id} capabilityIds`);
    if (capabilityIds.length === 0) {
      throw new Error(`workflow ${id} capabilityIds must not be empty`);
    }
    const definitions = capabilityIds.map((capabilityId) =>
      this.capabilities.require(capabilityId)
    );
    const roles = new Set(definitions.map((definition) => definition.role));

    const roleCapabilities: Partial<Record<AgentRole, readonly CapabilityId[]>> = {};
    for (const definition of definitions) {
      const current = roleCapabilities[definition.role] ?? [];
      roleCapabilities[definition.role] = Object.freeze([...current, definition.id]);
    }

    const allowedDelegations: Partial<Record<DelegatorRole, readonly AgentRole[]>> = {};
    for (const [rawSource, rawTargets] of Object.entries(input.allowedDelegations)) {
      const source = rawSource as DelegatorRole;
      if (source !== "system" && !roles.has(source)) {
        throw new Error(`workflow ${id} delegation source has no capability: ${source}`);
      }
      const targets = unique(rawTargets, `workflow ${id} delegations from ${source}`);
      for (const target of targets) {
        if (!roles.has(target)) {
          throw new Error(`workflow ${id} delegation target has no capability: ${target}`);
        }
      }
      allowedDelegations[source] = targets;
    }
    if (!(allowedDelegations.system?.length)) {
      throw new Error(`workflow ${id} must declare a system entry delegation`);
    }

    const graph: CollaborationGraphDefinition = Object.freeze({
      allowedDelegations: Object.freeze(allowedDelegations),
      roleCapabilities: Object.freeze(roleCapabilities),
    });
    this.#items.set(id, Object.freeze({
      id,
      version: version(input.version, `workflow ${id} version`),
      capabilityProfileId: identifier(
        input.capabilityProfileId,
        `workflow ${id} capability profile id`,
      ),
      capabilityIds,
      graph,
    }));
    return this;
  }

  get(id: WorkflowId): RegisteredWorkflow | undefined {
    return this.#items.get(id);
  }

  require(id: WorkflowId): RegisteredWorkflow {
    const workflow = this.get(id);
    if (!workflow) throw new Error(`unknown workflow: ${id}`);
    return workflow;
  }

  list(): readonly RegisteredWorkflow[] {
    return Object.freeze(
      [...this.#items.values()].sort((left, right) => left.id.localeCompare(right.id)),
    );
  }
}

export const DEFAULT_WORKFLOW_ID = "commerce-decision-v1";

export const DEFAULT_CAPABILITY_DEFINITIONS = [
  { id: "calibrated_candidate_fusion", version: "1.0", role: "lead" },
  { id: "grounded_response_composition", version: "1.0", role: "lead" },
  { id: "understand_and_route", version: "1.0", role: "intent_router" },
  { id: "search_strategy_and_retrieval", version: "1.0", role: "search" },
  { id: "recommendation_strategy_and_retrieval", version: "1.0", role: "recommendation" },
  { id: "ads_strategy_and_retrieval", version: "1.0", role: "ads" },
  { id: "constraint_bundle_optimization", version: "1.0", role: "compatibility" },
  { id: "live_quote_tool", version: "1.0", role: "pricing" },
  { id: "review_aspect_tool", version: "1.0", role: "review_evidence" },
  { id: "independent_decision_audit", version: "1.0", role: "critic" },
] as const satisfies readonly CapabilityDefinition[];

export const DEFAULT_WORKFLOW_DEFINITION = {
  id: DEFAULT_WORKFLOW_ID,
  version: "1.0",
  capabilityProfileId: "commerce-bounded-v1",
  capabilityIds: DEFAULT_CAPABILITY_DEFINITIONS.map((definition) => definition.id),
  allowedDelegations: {
    system: ["lead"],
    lead: [
      "intent_router",
      "search",
      "recommendation",
      "ads",
      "compatibility",
      "pricing",
      "review_evidence",
      "critic",
      "lead",
    ],
    intent_router: ["search", "recommendation", "ads"],
    search: ["recommendation"],
    recommendation: ["compatibility"],
    ads: ["critic"],
    compatibility: ["pricing", "review_evidence", "critic"],
    pricing: ["critic"],
    review_evidence: ["critic"],
    critic: ["recommendation", "lead"],
  },
} as const satisfies WorkflowDefinition;

export function createDefaultExtensionRegistries(): {
  capabilities: CapabilityRegistry;
  workflows: WorkflowRegistry;
} {
  const capabilities = new CapabilityRegistry(DEFAULT_CAPABILITY_DEFINITIONS);
  const workflows = new WorkflowRegistry(capabilities, [DEFAULT_WORKFLOW_DEFINITION]);
  return { capabilities, workflows };
}

const DEFAULT_REGISTRIES = createDefaultExtensionRegistries();

export const DEFAULT_COLLABORATION_GRAPH =
  DEFAULT_REGISTRIES.workflows.require(DEFAULT_WORKFLOW_ID).graph;
