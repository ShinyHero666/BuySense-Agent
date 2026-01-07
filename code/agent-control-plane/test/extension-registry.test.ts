import assert from "node:assert/strict";
import test from "node:test";
import { BoundedCollaborationCoordinator } from "../src/collaboration.js";
import {
  CapabilityRegistry,
  DEFAULT_WORKFLOW_ID,
  WorkflowRegistry,
  createDefaultExtensionRegistries,
} from "../src/extension-registry.js";
import { TraceCollector } from "../src/role-agent.js";

test("default registries reproduce the active collaboration workflow", () => {
  const { capabilities, workflows } = createDefaultExtensionRegistries();
  const workflow = workflows.require(DEFAULT_WORKFLOW_ID);

  assert.equal(capabilities.list().length, 10);
  assert.equal(workflows.list().length, 1);
  assert.equal(workflow.capabilityProfileId, "commerce-bounded-v1");
  assert.deepEqual(workflow.graph.allowedDelegations.search, ["recommendation"]);
  assert.deepEqual(workflow.graph.roleCapabilities.lead, [
    "calibrated_candidate_fusion",
    "grounded_response_composition",
  ]);
  assert.ok(Object.isFrozen(workflow.graph));
  assert.ok(Object.isFrozen(workflow.graph.allowedDelegations.search));
});

test("registries reject duplicate and dangling workflow declarations", () => {
  const capabilities = new CapabilityRegistry([
    { id: "fixture_lead", version: "1.0", role: "lead" },
  ]);
  assert.throws(
    () => capabilities.register({ id: "fixture_lead", version: "2.0", role: "lead" }),
    /duplicate capability/,
  );

  const workflows = new WorkflowRegistry(capabilities);
  assert.throws(
    () => workflows.register({
      id: "dangling-v1",
      version: "1.0",
      capabilityProfileId: "fixture-profile-v1",
      capabilityIds: ["missing_capability"],
      allowedDelegations: { system: ["lead"] },
    }),
    /unknown capability/,
  );
  assert.throws(
    () => workflows.register({
      id: "invalid-edge-v1",
      version: "1.0",
      capabilityProfileId: "fixture-profile-v1",
      capabilityIds: ["fixture_lead"],
      allowedDelegations: { system: ["search"] },
    }),
    /delegation target has no capability/,
  );
});

test("coordinator enforces the workflow graph resolved by registries", async () => {
  const capabilities = new CapabilityRegistry([
    { id: "fixture_plan", version: "1.0", role: "lead" },
    { id: "fixture_audit", version: "1.0", role: "critic" },
  ]);
  const workflows = new WorkflowRegistry(capabilities, [{
    id: "fixture-workflow-v1",
    version: "1.0",
    capabilityProfileId: "fixture-profile-v1",
    capabilityIds: ["fixture_plan", "fixture_audit"],
    allowedDelegations: {
      system: ["lead"],
      lead: ["critic"],
      critic: [],
    },
  }]);
  const workflow = workflows.require("fixture-workflow-v1");
  const coordinator = new BoundedCollaborationCoordinator(
    "run-fixture",
    new TraceCollector(),
    {},
    workflow.graph,
  );

  assert.equal(await coordinator.delegate({
    delegatedBy: "lead",
    role: "critic",
    capability: "fixture_audit",
    execute: () => "audited",
  }), "audited");
  await assert.rejects(
    coordinator.delegate({
      delegatedBy: "lead",
      role: "search",
      capability: "search_strategy_and_retrieval",
      execute: () => "not reached",
    }),
    /delegation_denied/,
  );
  await assert.rejects(
    coordinator.delegate({
      delegatedBy: "lead",
      role: "critic",
      capability: "independent_decision_audit",
      execute: () => "not reached",
    }),
    /capability_denied/,
  );
});
