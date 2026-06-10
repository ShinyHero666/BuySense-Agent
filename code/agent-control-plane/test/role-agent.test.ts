import assert from "node:assert/strict";
import test from "node:test";
import { createFauxCore, fauxAssistantMessage, fauxToolCall } from "@earendil-works/pi-ai";
import { InMemoryArtifactStore } from "../src/artifacts.js";
import { BoundedCollaborationCoordinator } from "../src/collaboration.js";
import type { AgentRole } from "../src/contracts.js";
import type {
  PiRuntimeBinding,
  PiRuntimeDescription,
  PiRuntimeFactory,
  PiRuntimeProbe,
  ReplayDirective,
} from "../src/pi-runtime.js";
import { PiRoleAgent, TraceCollector } from "../src/role-agent.js";

class HandoffProposalRuntime implements PiRuntimeFactory {
  readonly mode = "modelport" as const;

  create(role: AgentRole, directive: ReplayDirective): PiRuntimeBinding {
    const faux = createFauxCore({
      api: `handoff-test-${role}`,
      provider: "handoff-test",
      models: [{ id: "handoff-model", name: "Handoff Model" }],
    });
    faux.setResponses([
      fauxAssistantMessage(
        fauxToolCall("request_handoff", {
          to: "recommendation",
          capability: "recommendation_strategy_and_retrieval",
          reason: "search candidates can ground accessory recommendation",
        }, { id: "handoff-call" }),
        { stopReason: "toolUse", timestamp: 0 },
      ),
      fauxAssistantMessage(
        fauxToolCall(directive.toolName, directive.arguments, { id: "publish-call" }),
        { stopReason: "toolUse", timestamp: 0 },
      ),
    ]);
    return { model: faux.getModel(), streamFn: faux.streamSimple };
  }

  describe(): PiRuntimeDescription {
    return {
      mode: this.mode,
      provider: "handoff-test",
      model: "handoff-model",
      baseUrl: null,
      localOnly: true,
    };
  }

  async probe(): Promise<PiRuntimeProbe> {
    return { ...this.describe(), status: "up", latencyMs: 0, error: null };
  }
}

test("Pi role can propose a handoff but the coordinator remains authoritative", async () => {
  const trace = new TraceCollector();
  const artifacts = new InMemoryArtifactStore();
  const coordinator = new BoundedCollaborationCoordinator("role-run", trace);
  const role = new PiRoleAgent(
    new HandoffProposalRuntime(),
    artifacts,
    trace,
    undefined,
    (proposal) => coordinator.proposeDelegation(proposal),
  );

  const artifact = await role.run<{ rankedSkuIds: string[] }, { rankedSkuIds: string[] }>({
    role: "search",
    runId: "role-run",
    parentTaskId: "search-task",
    artifactType: "candidate_set",
    status: "verified",
    input: { rankedSkuIds: ["sku-1"] },
    proposalContract: '{"rankedSkuIds":["sku-1"]}',
    execute: (_input, proposal) => ({
      payload: proposal as { rankedSkuIds: string[] },
      outcome: "accepted",
    }),
    fallback: (input) => input,
  });

  assert.deepEqual(artifact.payload.rankedSkuIds, ["sku-1"]);
  const proposal = coordinator.takeApprovedProposal({
    proposedBy: "search",
    role: "recommendation",
    capability: "recommendation_strategy_and_retrieval",
  });
  assert.equal(proposal?.status, "consumed");
  assert.ok(trace.records.some((record) => record.event === "delegation_proposal_reviewed"));
});
