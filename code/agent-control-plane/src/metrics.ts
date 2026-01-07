import type { CartDraftOutcome, SearchAdsRecsReply } from "./contracts.js";

function rate(numerator: number, denominator: number): number {
  return denominator === 0 ? 0 : Number((numerator / denominator).toFixed(6));
}

export interface AgentMetricSnapshot {
  generatedAt: string;
  northStar: {
    name: "qualified_decision_success_rate";
    value: number;
    numerator: number;
    denominator: number;
  };
  layers: {
    intentUnderstanding: { requirementParseRate: number };
    retrieval: { channelExecutionRate: number; naturalCandidateSuccessRate: number };
    rankingFusion: { policyComplianceRate: number; completeProposalRate: number };
    evidenceDecision: { groundedEvidenceRate: number; liveQuoteCoverageRate: number };
    agentReliability: {
      approvalRate: number;
      confirmationConversionRate: number;
      confirmationSuccessRate: number;
      modelProposalUseRate: number;
      modelFallbackRate: number;
    };
    businessOutcome: {
      qualifiedDecisionSuccessRate: number;
      validCartDraftCompletionRate: number;
      endToEndQualifiedDraftRate: number;
    };
  };
  counters: AgentMetricCounters;
}

export interface AgentMetricCounters {
  purchase_intent_sessions: number;
  requirement_parse_successes: number;
  requested_channels: number;
  executed_channels: number;
  natural_retrieval_runs: number;
  natural_retrieval_successes: number;
  fusion_policy_checks: number;
  fusion_policy_passes: number;
  complete_proposals: number;
  evidence_checks: number;
  evidence_passes: number;
  live_quote_checks: number;
  live_quote_passes: number;
  approved_proposals: number;
  confirmation_attempts: number;
  valid_confirmation_attempts: number;
  confirmation_successes: number;
  valid_cart_drafts: number;
  no_pending_confirmations: number;
  real_model_role_runs: number;
  model_proposals_used: number;
  model_fallbacks: number;
}

export class SearchAdsRecsMetrics {
  readonly #now: () => number;
  readonly #counters: AgentMetricCounters = {
    purchase_intent_sessions: 0,
    requirement_parse_successes: 0,
    requested_channels: 0,
    executed_channels: 0,
    natural_retrieval_runs: 0,
    natural_retrieval_successes: 0,
    fusion_policy_checks: 0,
    fusion_policy_passes: 0,
    complete_proposals: 0,
    evidence_checks: 0,
    evidence_passes: 0,
    live_quote_checks: 0,
    live_quote_passes: 0,
    approved_proposals: 0,
    confirmation_attempts: 0,
    valid_confirmation_attempts: 0,
    confirmation_successes: 0,
    valid_cart_drafts: 0,
    no_pending_confirmations: 0,
    real_model_role_runs: 0,
    model_proposals_used: 0,
    model_fallbacks: 0,
  };

  constructor(now: () => number = Date.now) {
    this.#now = now;
  }

  /** Combine independently scoped Domain Pack metrics into one service view. */
  static aggregate(
    metrics: Iterable<SearchAdsRecsMetrics>,
    now: () => number = Date.now,
  ): AgentMetricSnapshot {
    const combined = new SearchAdsRecsMetrics(now);
    const keys = Object.keys(combined.#counters) as Array<keyof AgentMetricCounters>;
    for (const metric of metrics) {
      const counters = metric.snapshot().counters;
      for (const key of keys) combined.#counters[key] += counters[key];
    }
    return combined.snapshot();
  }

  recordProposal(reply: SearchAdsRecsReply): void {
    const counters = this.#counters;
    counters.purchase_intent_sessions += 1;
    if (reply.plan.requirements.requestedCategories.length > 0) {
      counters.requirement_parse_successes += 1;
    }
    const executedRoles = new Set(reply.trace.map((record) => record.role));
    counters.requested_channels += reply.plan.channels.length;
    counters.executed_channels += reply.plan.channels.filter((channel) => {
      const role = channel === "recommendation" ? "recommendation" : channel;
      return executedRoles.has(role);
    }).length;
    for (const channel of ["search", "recommendation"] as const) {
      if (!reply.plan.channels.includes(channel)) continue;
      counters.natural_retrieval_runs += 1;
      if (reply.slate.some((candidate) => candidate.sources.includes(channel))) {
        counters.natural_retrieval_successes += 1;
      }
    }
    counters.fusion_policy_checks += 1;
    if (
      reply.critique.checks.sponsored_disclosed === true &&
      reply.critique.checks.sponsored_top3_cap === true
    ) {
      counters.fusion_policy_passes += 1;
    }
    if (reply.critique.checks.requested_category_coverage === true) {
      counters.complete_proposals += 1;
    }
    counters.evidence_checks += 1;
    if (
      reply.critique.checks.compatibility_graph_grounded === true &&
      reply.critique.checks.review_evidence_coverage === true
    ) {
      counters.evidence_passes += 1;
    }
    counters.live_quote_checks += 1;
    if (
      reply.critique.checks.live_quote_coverage === true &&
      reply.critique.checks.live_quote_version_applied === true
    ) {
      counters.live_quote_passes += 1;
    }
    if (reply.critique.verdict === "approved") counters.approved_proposals += 1;
    counters.real_model_role_runs += reply.runtime.modelCalls;
    counters.model_proposals_used +=
      reply.runtime.proposalAccepted + reply.runtime.proposalCorrected;
    counters.model_fallbacks += reply.runtime.fallbackCount;
  }

  recordConfirmation(outcome: CartDraftOutcome): void {
    this.#counters.confirmation_attempts += 1;
    this.#counters.valid_confirmation_attempts += 1;
    if (outcome.status === "created" && outcome.draft) {
      this.#counters.confirmation_successes += 1;
      this.#counters.valid_cart_drafts += 1;
    }
    const modelExecutions = outcome.trace.filter(
      (record) => record.event === "model_execution" && record.detail.mode === "modelport",
    );
    this.#counters.real_model_role_runs += modelExecutions.length;
    this.#counters.model_proposals_used += modelExecutions.filter(
      (record) => record.detail.proposalUsed === true,
    ).length;
    this.#counters.model_fallbacks += modelExecutions.filter(
      (record) => record.detail.outcome === "fallback",
    ).length;
  }

  recordNoPendingConfirmation(): void {
    this.#counters.confirmation_attempts += 1;
    this.#counters.no_pending_confirmations += 1;
  }

  snapshot(): AgentMetricSnapshot {
    const counters = { ...this.#counters };
    const northStar = rate(counters.approved_proposals, counters.purchase_intent_sessions);
    const validCartDraftCompletionRate = rate(
      counters.valid_cart_drafts,
      counters.valid_confirmation_attempts,
    );
    return {
      generatedAt: new Date(this.#now()).toISOString(),
      northStar: {
        name: "qualified_decision_success_rate",
        value: northStar,
        numerator: counters.approved_proposals,
        denominator: counters.purchase_intent_sessions,
      },
      layers: {
        intentUnderstanding: {
          requirementParseRate: rate(
            counters.requirement_parse_successes,
            counters.purchase_intent_sessions,
          ),
        },
        retrieval: {
          channelExecutionRate: rate(counters.executed_channels, counters.requested_channels),
          naturalCandidateSuccessRate: rate(
            counters.natural_retrieval_successes,
            counters.natural_retrieval_runs,
          ),
        },
        rankingFusion: {
          policyComplianceRate: rate(
            counters.fusion_policy_passes,
            counters.fusion_policy_checks,
          ),
          completeProposalRate: rate(
            counters.complete_proposals,
            counters.purchase_intent_sessions,
          ),
        },
        evidenceDecision: {
          groundedEvidenceRate: rate(counters.evidence_passes, counters.evidence_checks),
          liveQuoteCoverageRate: rate(
            counters.live_quote_passes,
            counters.live_quote_checks,
          ),
        },
        agentReliability: {
          approvalRate: rate(counters.approved_proposals, counters.purchase_intent_sessions),
          confirmationConversionRate: rate(
            counters.valid_confirmation_attempts,
            counters.approved_proposals,
          ),
          confirmationSuccessRate: rate(
            counters.confirmation_successes,
            counters.valid_confirmation_attempts,
          ),
          modelProposalUseRate: rate(
            counters.model_proposals_used,
            counters.real_model_role_runs,
          ),
          modelFallbackRate: rate(
            counters.model_fallbacks,
            counters.real_model_role_runs,
          ),
        },
        businessOutcome: {
          qualifiedDecisionSuccessRate: northStar,
          validCartDraftCompletionRate,
          endToEndQualifiedDraftRate: rate(
            counters.valid_cart_drafts,
            counters.purchase_intent_sessions,
          ),
        },
      },
      counters,
    };
  }
}
