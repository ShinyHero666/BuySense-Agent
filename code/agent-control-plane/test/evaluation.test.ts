import assert from "node:assert/strict";
import test from "node:test";
import { runEvaluation } from "../src/evaluation.js";

test("search-ads-recs Agent V2 evaluation gates pass", async () => {
  const report = await runEvaluation();
  assert.equal(report.caseCount, 15);
  assert.deepEqual(report.failures, []);
  assert.ok(Object.values(report.gates).every(Boolean));
  assert.equal(report.feasibleCartDraftCompletionRate, 1);
  assert.equal(report.metrics.counters.valid_cart_drafts, report.feasibleCaseCount);
  assert.equal(report.metrics.northStar.denominator, report.caseCount);
});
