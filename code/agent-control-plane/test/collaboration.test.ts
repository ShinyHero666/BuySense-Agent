import assert from "node:assert/strict";
import test from "node:test";
import { BoundedCollaborationCoordinator } from "../src/collaboration.js";
import { TraceCollector } from "../src/role-agent.js";

test("bounded collaboration enforces capability and model-call budgets", async () => {
  const trace = new TraceCollector();
  const coordinator = new BoundedCollaborationCoordinator("run-test", trace, {
    maxTasks: 2,
    maxModelCalls: 1,
  });
  const result = await coordinator.delegate({
    delegatedBy: "lead",
    role: "search",
    capability: "search_strategy_and_retrieval",
    execute: () => "ok",
  });
  assert.equal(result, "ok");
  coordinator.consumeModelCall("search");
  assert.throws(() => coordinator.consumeModelCall("search"), /model_call_budget/);
  await assert.rejects(
    coordinator.delegate({
      delegatedBy: "ads",
      role: "pricing",
      capability: "forbidden",
      execute: () => "not reached",
    }),
    /delegation_denied/,
  );
  assert.ok(trace.records.some((record) => record.event === "task_completed"));
});

test("Agent delegation proposals require an allowed edge and exact capability", () => {
  const trace = new TraceCollector();
  const coordinator = new BoundedCollaborationCoordinator("run-proposals", trace);
  const approved = coordinator.proposeDelegation({
    proposedBy: "search",
    role: "recommendation",
    capability: "recommendation_strategy_and_retrieval",
    parentTaskId: "search-task",
    reason: "search candidates provide primary product grounding",
  });
  assert.equal(approved.status, "approved");
  assert.equal(coordinator.takeApprovedProposal({
    proposedBy: "search",
    role: "recommendation",
    capability: "recommendation_strategy_and_retrieval",
  })?.status, "consumed");

  const rejected = coordinator.proposeDelegation({
    proposedBy: "ads",
    role: "pricing",
    capability: "live_quote_tool",
    reason: "attempt to cross a denied edge",
  });
  assert.equal(rejected.status, "rejected");
  assert.match(rejected.rejectionReason ?? "", /delegation_edge_denied/);
});

test("collaboration deadline exposes an abort signal for in-flight Agent work", async () => {
  const coordinator = new BoundedCollaborationCoordinator(
    "run-deadline",
    new TraceCollector(),
    { deadlineMs: 5 },
  );
  await new Promise<void>((resolve, reject) => {
    const watchdog = setTimeout(() => reject(new Error("deadline signal did not abort")), 100);
    coordinator.signal.addEventListener("abort", () => {
      clearTimeout(watchdog);
      resolve();
    }, { once: true });
  });
  assert.equal(coordinator.signal.aborted, true);
  assert.throws(() => coordinator.consumeModelCall("search"), /deadline_exceeded/);
});
