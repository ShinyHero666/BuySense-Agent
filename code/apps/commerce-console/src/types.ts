export type ViewName = "decision" | "collaboration" | "quality";

export interface Candidate {
  product: {
    productId: string;
    skuId: string;
    title: string;
    category: string;
    brand: string;
    price: number;
    stock: number;
    tags: string[];
  };
  channel: string;
  sources: string[];
  normalizedScore: number;
  reasons: string[];
  sponsored: boolean;
  disclosure: "赞助" | null;
}

export interface Decision {
  message: string;
  plan: {
    originalQuery: string;
    query: string;
    intent: string;
    channels: string[];
    sponsoredAllowed: boolean;
    requirements: {
      budgetMax: number | null;
      preferredBrands: string[];
      requestedCategories: string[];
      useCases: string[];
      constraints: Array<{
        constraintId: string;
        field: string;
        source: string;
        strength: string;
        confidence: number;
      }>;
    };
  };
  slate: Candidate[];
  bundle: {
    items: Candidate[];
    totalPrice: number;
    withinBudget: boolean;
    score?: number;
    optimization?: string;
    alternatives?: Array<{
      skuIds: string[];
      totalPrice: number;
      score: number;
      sponsoredCount: number;
    }>;
  };
  critique: { verdict: "approved" | "vetoed"; violations: string[]; checks: Record<string, boolean> };
  runtime: {
    mode: string;
    model: string;
    modelCalls: number;
    fallbackCount: number;
    totalTokens: number;
  };
}

export interface BuyerReply {
  phase: "proposal" | "clarification" | "cart_draft" | "needs_replan" | "no_pending_decision";
  message: string;
  decision: Decision | null;
  cartDraft: null | { draftId: string; totalPrice: number; expiresAt: string; paymentAuthorized: false };
}

export interface AgentRun {
  runId: string;
  status: "queued" | "running" | "completed" | "failed" | "cancelled";
  result?: BuyerReply;
  errorCode?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface RunEvent {
  eventId: string;
  sequence: number;
  eventType: string;
  timestamp: string;
  payload: Record<string, unknown>;
}

export interface MetricSnapshot {
  northStar: { name: string; value: number; numerator: number; denominator: number };
  layers: Record<string, Record<string, number>>;
  counters: Record<string, number>;
}

export interface RuntimeStatus {
  status: string;
  model: { mode: string; model: string; status: string; latencyMs: number };
  dataPlane: { mode: string; status: string; latencyMs: number };
  agentFramework: string;
  paymentEnabled: false;
}

export interface QualityReport {
  suite: string;
  evaluation_kind: "human_authored_business_cases";
  label_provenance: string;
  generated_at: string;
  catalog_version: string;
  case_count: number;
  spu_count: number;
  metrics: {
    category_recall_at_10: number;
    route_accuracy: number;
    task_completion_rate: number;
    clarification_precision: number;
    clarification_recall: number;
    clarification_f1: number;
    p95_latency_ms: number;
    hard_constraint_violations: number;
    ad_policy_violations: number;
  };
  passed: boolean;
}
