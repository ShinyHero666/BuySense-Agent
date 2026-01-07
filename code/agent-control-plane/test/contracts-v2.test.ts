import assert from "node:assert/strict";
import test from "node:test";
import {
  assertContract,
  isContract,
  validateContract,
  type CreateRunResponse,
} from "../src/generated/contracts-v2.js";
import type { RetrievalPlan } from "../src/contracts.js";
import { discoveryWireQuery, PythonDiscoveryAdapter } from "../src/python-adapter.js";
import { buildRetrievalPlan } from "../src/router.js";

test("generated V2 validators enforce create-run request and response contracts", () => {
  assert.deepEqual(validateContract("CreateRunRequest", {
    message: "预算 5000 元买手机",
    confirmed: false,
    domainPackId: "outdoor-camping-v1",
  }), []);
  assert.match(
    validateContract("CreateRunRequest", {
      message: "test",
      domainPackId: "Outdoor Camping",
    })[0]?.message ?? "",
    /must match/,
  );

  const unknownField = validateContract("CreateRunRequest", {
    message: "买手机",
    userId: "caller-controlled",
  });
  assert.deepEqual(unknownField, [{ path: "$.userId", message: "is not allowed" }]);
  assert.match(
    validateContract("CreateRunRequest", { message: "" })[0]?.message ?? "",
    /at least 1 characters/,
  );
  assert.notDeepEqual(validateContract("CreateRunRequest", {
    message: "确认",
    confirmed: true,
  }), []);
  assert.notDeepEqual(validateContract("CreateRunRequest", {
    message: "普通请求",
    proposalRunId: "run-proposal",
  }), []);
  assert.deepEqual(validateContract("CreateRunRequest", {
    message: "确认",
    confirmed: true,
    proposalRunId: "run-proposal",
  }), []);

  const response: CreateRunResponse = {
    runId: "run-001",
    domainPackId: "normal-3c-v1",
    workflowId: "commerce-decision-v1",
    status: "queued",
    eventsUrl: "/api/v2/runs/run-001/events",
    runUrl: "/api/v2/runs/run-001",
    idempotentReplay: false,
  };
  assert.ok(isContract("CreateRunResponse", response));
  assert.deepEqual(validateContract("CreateRunResponse", {
    ...response,
    idempotentReplay: "false",
  }), [{ path: "$.idempotentReplay", message: "must be boolean; got string" }]);
  assert.deepEqual(validateContract("CreateRunResponse", {
    runId: response.runId,
    domainPackId: response.domainPackId,
    workflowId: response.workflowId,
    status: response.status,
    eventsUrl: response.eventsUrl,
    runUrl: response.runUrl,
  }), [{ path: "$.idempotentReplay", message: "is required" }]);
});

test("generated V2 validators resolve references and nested inline objects", () => {
  const event: unknown = {
    eventId: "event-001",
    runId: "run-001",
    sequence: 1,
    eventType: "run_created",
    timestamp: "2026-08-09T00:00:00.000Z",
    schemaVersion: "2.0",
    payload: { status: "queued" },
  };
  assertContract("AgentRunEvent", event);
  assert.equal(event.eventType, "run_created");
  assert.match(
    validateContract("AgentRunEvent", { ...event, timestamp: "2026-08-09" })[0]?.message ?? "",
    /valid date-time/,
  );
  for (const timestamp of ["2026-02-30T00:00:00Z", "2026-04-31T12:00:00Z", "2026-01-01T24:00:00Z"]) {
    assert.match(
      validateContract("AgentRunEvent", { ...event, timestamp })[0]?.message ?? "",
      /valid date-time/,
    );
  }

  const invalidFusion = validateContract("FusionWireRequest", {
    channels: [{ channel: "search", items: [], unexpected: true }],
    limit: 8,
  });
  assert.deepEqual(invalidFusion, [{
    path: "$.channels[0].unexpected",
    message: "is not allowed",
  }]);
  assert.deepEqual(validateContract("DiscoveryWireRequest", {}), []);
  assert.deepEqual(validateContract("CreateRunRequest", {
    message: "😀".repeat(2_000),
  }), []);
  // A public Run accepts 2,000 characters. In replay mode the adapter keeps
  // both the original and rewritten query, so the shared boundary must admit
  // the resulting 4,001-character string.
  assert.deepEqual(validateContract("DiscoveryWireRequest", {
    query: `${"需".repeat(2_000)} ${"需".repeat(2_000)}`,
  }), []);
  const expanded = "\uFDFA".repeat(2_000).normalize("NFKC");
  const bounded = discoveryWireQuery(expanded, expanded);
  assert.equal(bounded.length, 4_096);
  assert.deepEqual(validateContract("DiscoveryWireRequest", { query: bounded }), []);
  assert.deepEqual(validateContract("FusionWireRequest", { channels: [] }), []);
  assert.deepEqual(validateContract("BundleOptimizationWireRequest", {
    items: [],
    requested_categories: ["camp_stove"],
    intent: "precise",
  }), []);
  assert.match(
    validateContract("BundleOptimizationWireRequest", {
      items: [],
      requested_categories: [],
      intent: "precise",
    })[0]?.message ?? "",
    /at least 1 items/,
  );
  assert.match(
    validateContract("FusionWireRequest", {
      channels: [{ channel: "search", items: Array(101).fill({}) }],
    })[0]?.message ?? "",
    /at most 100 items/,
  );
  assert.match(
    validateContract("DataSourceMetadataWireRecord", {
      source: "remote_provider",
      source_version: "https://provider.example/catalog?token=secret",
      provider_id: "retail-1234567890abcdef",
    })[0]?.message ?? "",
    /must match/,
  );
});

test("Python adapter validates every shared outbound wire request before fetch", async () => {
  const adapter = new PythonDiscoveryAdapter("http://127.0.0.1:1");
  const plan = buildRetrievalPlan("预算 5000 元买手机");
  const invalidDiscoveryPlan: RetrievalPlan = {
    ...plan,
    candidateBudget: { ...plan.candidateBudget, search: 101 },
  };
  await assert.rejects(
    adapter.search(invalidDiscoveryPlan),
    /Invalid DiscoveryWireRequest: \$\.limit must be at most 100/,
  );
  await assert.rejects(
    adapter.fuse([], 51),
    /Invalid FusionWireRequest: \$\.limit must be at most 50/,
  );

  const invalidBundlePlan = {
    ...plan,
    intent: "unsupported",
  } as unknown as RetrievalPlan;
  await assert.rejects(
    adapter.optimizeBundle([], invalidBundlePlan),
    /Invalid BundleOptimizationWireRequest: \$\.intent must be one of/,
  );
});
