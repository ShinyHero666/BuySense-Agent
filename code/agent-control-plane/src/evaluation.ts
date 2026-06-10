import { readFile } from "node:fs/promises";
import { SearchAdsRecsBuyerAgent } from "./buyer-agent.js";
import type { ProductCategory, RetrievalPlan } from "./contracts.js";
import type { AgentMetricSnapshot } from "./metrics.js";
import { isSupportedProductCategory } from "./domain-pack.js";

interface EvaluationCase {
  caseId: string;
  message: string;
  expectedIntent: RetrievalPlan["intent"];
  expectedBudgetMax: number | null;
  expectApproved: boolean;
  expectedCategories: ProductCategory[];
  sponsoredAllowed: boolean;
}

export interface SearchAdsRecsEvaluationReport {
  suite: "search-ads-recs-agent-eval-v2";
  caseCount: number;
  feasibleCaseCount: number;
  failures: Array<{ caseId: string; reasons: string[] }>;
  gates: {
    expectedVerdicts: boolean;
    intentAndConstraintExtraction: boolean;
    hardConstraintsPreserved: boolean;
    sponsoredOptOutRespected: boolean;
    evidenceGrounded: boolean;
    boundedPeerRevision: boolean;
    validCartDraftsForAllFeasibleCases: boolean;
    paymentAuthorizationZero: boolean;
  };
  feasibleCartDraftCompletionRate: number;
  metrics: AgentMetricSnapshot;
  passed: boolean;
}

function isCategory(value: unknown): value is ProductCategory {
  return isSupportedProductCategory(value);
}

async function loadCases(): Promise<EvaluationCase[]> {
  const raw: unknown = JSON.parse(
    await readFile(new URL("../eval/cases.json", import.meta.url), "utf8"),
  );
  if (!Array.isArray(raw)) throw new Error("evaluation cases must be an array");
  return raw.map((value, index) => {
    if (typeof value !== "object" || value === null || Array.isArray(value)) {
      throw new Error(`evaluation case ${index} must be an object`);
    }
    const item = value as Record<string, unknown>;
    if (
      typeof item.caseId !== "string" ||
      typeof item.message !== "string" ||
      !["precise", "catalog", "exploratory", "bundle", "compare"].includes(
        String(item.expectedIntent),
      ) ||
      (item.expectedBudgetMax !== null &&
        (typeof item.expectedBudgetMax !== "number" || !Number.isFinite(item.expectedBudgetMax))) ||
      typeof item.expectApproved !== "boolean" ||
      typeof item.sponsoredAllowed !== "boolean" ||
      !Array.isArray(item.expectedCategories) ||
      !item.expectedCategories.every(isCategory)
    ) {
      throw new Error(`evaluation case ${index} has an invalid contract`);
    }
    return {
      caseId: item.caseId,
      message: item.message,
      expectedIntent: item.expectedIntent as RetrievalPlan["intent"],
      expectedBudgetMax: item.expectedBudgetMax as number | null,
      expectApproved: item.expectApproved,
      sponsoredAllowed: item.sponsoredAllowed,
      expectedCategories: item.expectedCategories,
    };
  });
}

function sameCategories(actual: ProductCategory[], expected: ProductCategory[]): boolean {
  return actual.length === expected.length &&
    expected.every((category) => actual.includes(category));
}

export async function runEvaluation(): Promise<SearchAdsRecsEvaluationReport> {
  const cases = await loadCases();
  const fixedNow = () => Date.parse("2026-08-01T10:00:00Z");
  const agent = new SearchAdsRecsBuyerAgent({ now: fixedNow });
  const failures: SearchAdsRecsEvaluationReport["failures"] = [];
  let feasibleDrafts = 0;
  let paymentAuthorizations = 0;
  let hardConstraintViolations = 0;
  let adOptOutViolations = 0;
  let evidenceViolations = 0;
  let excessiveRevisions = 0;
  let intentOrConstraintViolations = 0;

  for (const item of cases) {
    const reasons: string[] = [];
    const sessionId = `eval-${item.caseId}`;
    const proposal = await agent.handle({
      sessionId,
      userId: "eval-user",
      message: item.message,
    });
    const decision = proposal.decision;
    if (!decision) {
      reasons.push("decision_missing");
      failures.push({ caseId: item.caseId, reasons });
      continue;
    }
    const approved = decision.critique.verdict === "approved";
    if (approved !== item.expectApproved) reasons.push("unexpected_verdict");
    if (
      decision.plan.intent !== item.expectedIntent ||
      decision.plan.requirements.budgetMax !== item.expectedBudgetMax
    ) {
      reasons.push("unexpected_intent_or_budget");
      intentOrConstraintViolations += 1;
    }
    if (!sameCategories(decision.plan.requirements.requestedCategories, item.expectedCategories)) {
      reasons.push("unexpected_requested_categories");
    }
    if (decision.plan.sponsoredAllowed !== item.sponsoredAllowed) {
      reasons.push("unexpected_sponsored_policy");
    }
    if (!item.sponsoredAllowed && decision.slate.some((candidate) => candidate.sponsored)) {
      reasons.push("sponsored_opt_out_violation");
      adOptOutViolations += 1;
    }
    if (
      decision.plan.requirements.budgetMax !== null &&
      decision.bundle.totalPrice > decision.plan.requirements.budgetMax
    ) {
      reasons.push("hard_budget_violation");
      hardConstraintViolations += 1;
    }
    if (
      approved &&
      (
        decision.critique.checks.compatibility_graph_grounded !== true ||
        decision.critique.checks.review_evidence_coverage !== true ||
        decision.critique.checks.live_quote_coverage !== true
      )
    ) {
      reasons.push("evidence_gate_bypassed");
      evidenceViolations += 1;
    }
    const revisionCount = decision.trace.filter(
      (record) => record.role === "critic" && record.event === "peer_delegated",
    ).length;
    if (revisionCount > 1) {
      reasons.push("peer_revision_unbounded");
      excessiveRevisions += 1;
    }

    if (item.expectApproved && approved) {
      const confirmation = await agent.handle({
        sessionId,
        userId: "eval-user",
        message: "确认生成购物车草案",
        confirmed: true,
      });
      if (confirmation.phase !== "cart_draft" || !confirmation.cartDraft) {
        reasons.push("valid_cart_draft_missing");
      } else {
        feasibleDrafts += 1;
        if (confirmation.cartDraft.paymentAuthorized) paymentAuthorizations += 1;
      }
    } else if (proposal.cartDraft) {
      reasons.push("unauthorized_cart_draft");
    }
    if (reasons.length > 0) failures.push({ caseId: item.caseId, reasons });
  }

  const feasibleCaseCount = cases.filter((item) => item.expectApproved).length;
  const gates = {
    expectedVerdicts: failures.every((failure) => !failure.reasons.includes("unexpected_verdict")),
    intentAndConstraintExtraction: intentOrConstraintViolations === 0,
    hardConstraintsPreserved: hardConstraintViolations === 0,
    sponsoredOptOutRespected: adOptOutViolations === 0,
    evidenceGrounded: evidenceViolations === 0,
    boundedPeerRevision: excessiveRevisions === 0,
    validCartDraftsForAllFeasibleCases: feasibleDrafts === feasibleCaseCount,
    paymentAuthorizationZero: paymentAuthorizations === 0,
  };
  const passed = failures.length === 0 && Object.values(gates).every(Boolean);
  return {
    suite: "search-ads-recs-agent-eval-v2",
    caseCount: cases.length,
    feasibleCaseCount,
    failures,
    gates,
    feasibleCartDraftCompletionRate:
      feasibleCaseCount === 0 ? 0 : Number((feasibleDrafts / feasibleCaseCount).toFixed(6)),
    metrics: agent.metrics.snapshot(),
    passed,
  };
}
