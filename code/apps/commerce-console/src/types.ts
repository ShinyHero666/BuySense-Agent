import type {
  AgentRun as ContractAgentRun,
  AgentRunEvent as ContractAgentRunEvent,
  DomainPackCategorySummary as ContractDomainPackCategorySummary,
  DomainPackRegistryResponse as ContractDomainPackRegistryResponse,
  DomainPackSummary as ContractDomainPackSummary,
} from "./generated/contracts-v2";

export type ViewName = "decision" | "collaboration" | "quality";

export type DomainPackCategorySummary = ContractDomainPackCategorySummary;
export type DomainPackSummary = ContractDomainPackSummary;
export type DomainPackRegistryResponse = ContractDomainPackRegistryResponse;

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

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function stringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === "string");
}

function finiteNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

function isCandidate(value: unknown): value is Candidate {
  const candidate = record(value);
  const product = record(candidate?.product);
  return candidate !== null && product !== null &&
    [product.productId, product.skuId, product.title, product.category, product.brand]
      .every((item) => typeof item === "string") &&
    finiteNumber(product.price) && finiteNumber(product.stock) && stringArray(product.tags) &&
    typeof candidate.channel === "string" && stringArray(candidate.sources) &&
    finiteNumber(candidate.normalizedScore) && stringArray(candidate.reasons) &&
    typeof candidate.sponsored === "boolean" &&
    (candidate.disclosure === null || candidate.disclosure === "赞助");
}

function isDecision(value: unknown): value is Decision {
  const decision = record(value);
  const plan = record(decision?.plan);
  const requirements = record(plan?.requirements);
  const bundle = record(decision?.bundle);
  const critique = record(decision?.critique);
  const runtime = record(decision?.runtime);
  if (!decision || !plan || !requirements || !bundle || !critique || !runtime) return false;
  const constraints = requirements.constraints;
  const checks = record(critique.checks);
  return typeof decision.message === "string" &&
    typeof plan.originalQuery === "string" && typeof plan.query === "string" &&
    typeof plan.intent === "string" && stringArray(plan.channels) &&
    typeof plan.sponsoredAllowed === "boolean" &&
    (requirements.budgetMax === null || finiteNumber(requirements.budgetMax)) &&
    stringArray(requirements.preferredBrands) && stringArray(requirements.requestedCategories) &&
    stringArray(requirements.useCases) && Array.isArray(constraints) && constraints.every((item) => {
      const constraint = record(item);
      return constraint !== null &&
        [constraint.constraintId, constraint.field, constraint.source, constraint.strength]
          .every((field) => typeof field === "string") &&
        finiteNumber(constraint.confidence);
    }) &&
    Array.isArray(decision.slate) && decision.slate.every(isCandidate) &&
    Array.isArray(bundle.items) && bundle.items.every(isCandidate) &&
    finiteNumber(bundle.totalPrice) && typeof bundle.withinBudget === "boolean" &&
    (bundle.score === undefined || finiteNumber(bundle.score)) &&
    (bundle.optimization === undefined || typeof bundle.optimization === "string") &&
    (bundle.alternatives === undefined || (Array.isArray(bundle.alternatives) &&
      bundle.alternatives.every((item) => {
        const alternative = record(item);
        return alternative !== null && stringArray(alternative.skuIds) &&
          finiteNumber(alternative.totalPrice) && finiteNumber(alternative.score) &&
          finiteNumber(alternative.sponsoredCount);
      }))) &&
    (critique.verdict === "approved" || critique.verdict === "vetoed") &&
    stringArray(critique.violations) && checks !== null &&
    Object.values(checks).every((item) => typeof item === "boolean") &&
    typeof runtime.mode === "string" && typeof runtime.model === "string" &&
    finiteNumber(runtime.modelCalls) && finiteNumber(runtime.fallbackCount) &&
    finiteNumber(runtime.totalTokens);
}

export function isBuyerReply(value: unknown): value is BuyerReply {
  const reply = record(value);
  if (!reply || !["proposal", "cart_draft", "needs_replan", "no_pending_decision"]
    .includes(typeof reply.phase === "string" ? reply.phase : "")) return false;
  if (typeof reply.message !== "string" ||
      !(reply.decision === null || isDecision(reply.decision))) return false;
  if (reply.cartDraft === null) return true;
  const draft = record(reply.cartDraft);
  return draft !== null && typeof draft.draftId === "string" &&
    finiteNumber(draft.totalPrice) && typeof draft.expiresAt === "string" &&
    draft.paymentAuthorized === false;
}

export interface AgentRun extends Pick<
  ContractAgentRun,
  "runId" | "domainPackId" | "workflowId" | "status" | "confirmed" |
  "proposalRunId" | "errorCode" | "createdAt" | "updatedAt"
> {
  result?: BuyerReply;
}

export type RunEvent = ContractAgentRunEvent;

export interface MetricSnapshot {
  northStar: { name: string; value: number; numerator: number; denominator: number };
  layers: Record<string, Record<string, number>>;
  counters: Record<string, number>;
}

export interface RetailSourceStatus {
  configuredMode: "static" | "http" | "mixed";
  effectiveSource: "local_snapshot" | "remote_provider" | "mixed" | "unavailable";
  status: "up" | "degraded" | "down";
  fallbackActive: boolean;
  version: string | null;
  providerId: string | null;
  effectiveProviderId: string | null;
  telemetry: {
    requests: number;
    errors: number;
    fallbacks: number;
  };
  lastErrorCode?: string;
}

export type RetailSourcesStatus = Record<"catalog" | "reviews" | "pricing", RetailSourceStatus>;

export interface RuntimeStatus {
  status: string;
  model: { mode: string; model: string; status: string; latencyMs: number };
  dataPlane: {
    mode: string;
    status: string;
    latencyMs: number;
    retailSources: RetailSourcesStatus | null;
  };
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
