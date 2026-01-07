export type AgentRole =
  | "lead"
  | "intent_router"
  | "search"
  | "recommendation"
  | "ads"
  | "compatibility"
  | "pricing"
  | "review_evidence"
  | "cart"
  | "critic";

export type RetrievalChannel = "search" | "recommendation" | "ads";

/** Domain Packs own the closed category set; the stable core only transports ids. */
export type ProductCategory = string;

/** Sanitized provenance carried across the Python HTTP data-plane boundary. */
export interface DataSourceMetadata {
  source: DataSourceKind;
  sourceVersion: string;
  providerId: string;
}

export interface RequirementState {
  budgetMax: number | null;
  preferredBrands: string[];
  requestedCategories: ProductCategory[];
  useCases: string[];
  hardFields: string[];
  unknownFields: string[];
  constraints: RequirementConstraint[];
}

export interface RetrievalPlan {
  originalQuery: string;
  query: string;
  intent: "precise" | "catalog" | "exploratory" | "bundle" | "compare";
  requirements: RequirementState;
  channels: RetrievalChannel[];
  candidateBudget: Record<RetrievalChannel, number>;
  sponsoredAllowed: boolean;
  reason: string;
}

export interface CatalogProduct {
  spuId: string;
  productId: string;
  skuId: string;
  offerId: string;
  title: string;
  category: ProductCategory;
  brand: string;
  price: number;
  stock: number;
  tags: string[];
  ecosystem: "ios" | "android" | "universal";
  connectors: string[];
  protocols: string[];
  maxPowerWatts?: number;
  sponsored: boolean;
  adBid?: number;
  adQuality?: number;
  catalogVersion: string;
  currency: "CNY";
  quoteVersion: string;
  quoteValidUntil: string;
  dataSource: DataSourceMetadata;
}

export interface CandidateEnvelope {
  product: CatalogProduct;
  channel: RetrievalChannel;
  sources: RetrievalChannel[];
  channelScore: number;
  normalizedScore: number;
  reasons: string[];
  sponsored: boolean;
  disclosure: "赞助" | null;
}

export interface CompatibilityResult {
  productId: string;
  accessoryId: string;
  status: "compatible" | "incompatible" | "unknown";
  reasons: string[];
  ruleVersion: string;
  paths: string[];
}

export interface BundleProposal {
  items: CandidateEnvelope[];
  totalPrice: number;
  budgetMax: number | null;
  withinBudget: boolean;
  compatibility: CompatibilityResult[];
  score?: number;
  optimization?: "global_enumeration" | "python_constraint_optimizer";
  alternatives?: Array<{
    skuIds: string[];
    totalPrice: number;
    score: number;
    sponsoredCount: number;
  }>;
}

export interface Critique {
  verdict: "approved" | "vetoed";
  violations: string[];
  checks: Record<string, boolean>;
  modelVerdict?: "approved" | "vetoed" | "unavailable";
  modelRationale?: string;
}

export interface PriceQuote {
  offerId: string;
  status: "active" | "unavailable";
  amount: number | null;
  currency: "CNY";
  stock: number;
  validUntil: string;
  reason: string;
}

export interface PriceQuoteBatch {
  quoteBatchId: string;
  quoteVersion: string;
  issuedAt: string;
  dataSource: DataSourceMetadata;
  quotes: PriceQuote[];
}

export interface ReviewAspectEvidence {
  aspect: string;
  sentiment: number;
  mentionCount: number;
  confidence: number;
  summary: string;
}

export interface ProductReviewEvidence {
  productId: string;
  sampleSize: number;
  aspects: ReviewAspectEvidence[];
  source: DataSourceKind;
  sourceVersion: string;
  providerId: string;
}

export interface ReviewEvidenceBatch {
  reviewSnapshotVersion: string;
  dataSource: DataSourceMetadata;
  products: ProductReviewEvidence[];
  missingProductIds: string[];
}

export interface PeerHandoff {
  from: "search";
  to: "recommendation";
  purpose: "primary_product_grounding";
  candidates: CandidateEnvelope[];
}

export interface RevisionRequest {
  attempt: 1;
  from: "critic";
  to: "recommendation";
  violations: string[];
  missingCategories: ProductCategory[];
  instruction: "expand_candidates_without_relaxing_hard_constraints";
}

export interface DiscoveryContext {
  /** Stable outer Run identifier used to namespace audit tasks and artifacts. */
  executionRunId?: string;
  peerCandidates?: CandidateEnvelope[];
  revision?: RevisionRequest;
  identityId?: string;
  sessionId?: string;
  personalizationEnabled?: boolean;
  recentProductIds?: string[];
  excludedProductIds?: string[];
  adExposureProductIds?: string[];
  signal?: AbortSignal;
}

export interface AgentTraceRecord {
  sequence: number;
  role: AgentRole;
  event: string;
  detail: Record<string, unknown>;
}

export type AgentRuntimeMode = "replay" | "modelport";

export type ModelProposalOutcome =
  | "accepted"
  | "corrected"
  | "fallback"
  | "replay";

export interface RoleModelExecution {
  role: AgentRole;
  mode: AgentRuntimeMode;
  provider: string;
  model: string;
  outcome: ModelProposalOutcome;
  proposalUsed: boolean;
  corrections: string[];
  latencyMs: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  error: string | null;
}

export interface AgentRuntimeSummary {
  mode: AgentRuntimeMode;
  provider: string;
  model: string;
  localOnly: boolean;
  modelCalls: number;
  proposalAccepted: number;
  proposalCorrected: number;
  fallbackCount: number;
  totalTokens: number;
  roleExecutions: RoleModelExecution[];
}

export interface SearchAdsRecsReply {
  runId: string;
  domainPackId: string;
  workflowId: string;
  message: string;
  plan: RetrievalPlan;
  slate: CandidateEnvelope[];
  bundle: BundleProposal;
  priceQuote: PriceQuoteBatch;
  reviewEvidence: ReviewEvidenceBatch;
  critique: Critique;
  artifactIds: string[];
  trace: AgentTraceRecord[];
  runtime: AgentRuntimeSummary;
}

export interface ChannelResult {
  channel: RetrievalChannel;
  /** Batch-level catalog provenance, retained even when retrieval returns no items. */
  dataSource?: DataSourceMetadata;
  candidates: CandidateEnvelope[];
}

export interface DiscoveryChannels {
  search(plan: RetrievalPlan, context?: DiscoveryContext): Promise<ChannelResult>;
  recommend(plan: RetrievalPlan, context?: DiscoveryContext): Promise<ChannelResult>;
  ads(plan: RetrievalPlan, context?: DiscoveryContext): Promise<ChannelResult>;
}

export interface DecisionOptimizationGateway {
  fuse(
    results: ChannelResult[],
    limit?: number,
    signal?: AbortSignal,
  ): Promise<CandidateEnvelope[]>;
  optimizeBundle(
    slate: CandidateEnvelope[],
    plan: RetrievalPlan,
    signal?: AbortSignal,
  ): Promise<BundleProposal>;
}

export interface DecisionEvidenceGateway {
  checkCompatibility(
    primary: CandidateEnvelope,
    accessories: CandidateEnvelope[],
    signal?: AbortSignal,
  ): Promise<CompatibilityResult[]>;
  quote(items: CandidateEnvelope[], signal?: AbortSignal): Promise<PriceQuoteBatch>;
  reviewAspects(productIds: string[], signal?: AbortSignal): Promise<ReviewEvidenceBatch>;
}

export interface CartDraftItem {
  spuId: string;
  skuId: string;
  offerId: string;
  title: string;
  quantity: 1;
  unitPrice: number;
  currency: "CNY";
  quoteVersion: string;
}

export interface CartDraft {
  draftId: string;
  sessionId: string;
  status: "ready";
  items: CartDraftItem[];
  totalPrice: number;
  currency: "CNY";
  quoteBatchId: string;
  createdAt: string;
  expiresAt: string;
  paymentAuthorized: false;
}

export interface CartDraftOutcome {
  status: "created" | "rejected";
  violations: string[];
  draft: CartDraft | null;
  refreshedBundle: BundleProposal;
  priceQuote: PriceQuoteBatch;
  artifactIds: string[];
  trace: AgentTraceRecord[];
}

export interface BuyerTurnRequest {
  sessionId: string;
  userId: string;
  message: string;
  confirmed?: boolean;
  /** Bind a confirmation to the proposal Run that produced the pending decision. */
  proposalRunId?: string;
}

export interface BuyerTurnReply {
  phase: "proposal" | "cart_draft" | "needs_replan" | "no_pending_decision";
  message: string;
  decision: SearchAdsRecsReply | null;
  cartDraft: CartDraft | null;
  confirmationTrace: AgentTraceRecord[];
}
import type { RequirementConstraint } from "./generated/contracts-v2.js";
import type { DataSourceKind } from "./generated/contracts-v2.js";
