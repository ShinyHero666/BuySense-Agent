import type {
  AgentRole,
  AgentRuntimeSummary,
  AgentTraceRecord,
  CandidateEnvelope,
  ChannelResult,
  Critique,
  PeerHandoff,
  ProductCategory,
  RetrievalChannel,
  RetrievalPlan,
  RoleModelExecution,
} from "./contracts.js";
import type { PiRuntimeFactory } from "./pi-runtime.js";
import type { RoleExecutionResult } from "./role-agent.js";
import { buildRetrievalPlan } from "./router.js";
import { NORMAL_3C_DOMAIN, SUPPORTED_PRODUCT_CATEGORIES } from "./domain-pack.js";

const PRODUCT_CATEGORIES = SUPPORTED_PRODUCT_CATEGORIES;
const CHANNELS = new Set<RetrievalChannel>(["search", "recommendation", "ads"]);
const INTENTS = new Set<RetrievalPlan["intent"]>([
  "precise",
  "catalog",
  "exploratory",
  "bundle",
  "compare",
]);
const USE_CASES = new Set(NORMAL_3C_DOMAIN.useCases);

const CATEGORY_CONTRACT = NORMAL_3C_DOMAIN.categories.map((item) => item.id).join("|");
const USE_CASE_CONTRACT = NORMAL_3C_DOMAIN.useCases.join("|");

export const ROLE_PROPOSAL_CONTRACTS = {
  retrievalPlan:
    `{"intent":"precise|catalog|exploratory|bundle|compare","query":"grounded rewrite","requestedCategories":["${CATEGORY_CONTRACT}"],"preferredBrands":["brand"],"useCases":["${USE_CASE_CONTRACT}"],"channels":["search|recommendation|ads"],"sponsoredAllowed":boolean,"candidateBudget":{"search":1-20,"recommendation":1-20,"ads":0-8},"reason":"short grounded reason"}`,
  channelRanking:
    '{"rankedSkuIds":["only SKU ids present in candidates"],"rationaleBySku":{"sku-id":"short reason grounded in candidate fields"}}',
  handoff:
    '{"candidateSkuIds":["only phone SKU ids useful as recommendation context"]}',
  bundle:
    '{"rankedSkuIds":["one primary SKU followed by requested accessory SKU ids"],"reason":"short selection strategy; no invented facts"}',
  offerIds: '{"offerIds":["exact offer ids from items"]}',
  productIds: '{"productIds":["exact product ids from context"]}',
  critique:
    '{"verdict":"approved|vetoed","additionalViolations":["insufficient_review_confidence|weak_use_case_match|ambiguous_compatibility"],"rationale":"grounded audit summary"}',
  revision:
    '{"approveRevision":boolean,"missingCategories":["only categories from context"],"reason":"short reason"}',
  finalDecision:
    '{"message":"concise Chinese buyer-facing answer using only supplied items, prices and evidence","approved":boolean}',
  cartDecision:
    '{"decision":"create|reject","reason":"short reason based only on supplied deterministic violations"}',
} as const;

export const ROLE_SYSTEM_PROMPTS: Record<AgentRole, string> = {
  lead:
    "你是搜广推 Lead Agent，负责把已核验的计划、商品、价格、兼容和评论证据组织成简洁中文结论。只能引用上下文事实，不得声称已下单或已支付。",
  intent_router:
    "你是电商意图与检索路由 Agent。理解自然语言需求并选择必要的 Search、Recommendation、Ads 通道；Search 是保底通道，套装必须保留 Recommendation，Ads 仅在用户未退出且确有必要时选择。预算和广告退出属于硬约束，不得放宽。",
  search:
    "你是 Search Agent。只在给定搜索候选中排序，兼顾 Query、类目、用途、品牌和预算；不得新增 SKU。当计划包含 Recommendation 且搜索候选能提供主商品上下文时，应先用 request_handoff 请求 recommendation_strategy_and_retrieval，再发布排序产物。",
  recommendation:
    "你是 Recommendation Agent。结合 Search handoff 与套装需求，只在给定候选中排序；不得新增 SKU 或修改价格。",
  ads:
    "你是 Ads Agent。只在已通过相关性和库存过滤的赞助候选中排序；必须保留赞助披露，不得填充无关广告。",
  compatibility:
    "你是 Compatibility Agent。提出主商品与配件的优先顺序，最终兼容结论必须服从版本化兼容图谱。",
  pricing:
    "你是 Pricing Agent。选择上下文中的精确 Offer ID 请求 Quote，不得自行生成、修改或推测价格与库存。",
  review_evidence:
    "你是 Review Evidence Agent。选择上下文中的精确商品 ID 获取评论 Aspect，不得把评论摘要当成规格事实。",
  critic:
    "你是独立 Critic Agent。检查预算、库存、Quote、兼容、证据、广告披露与类目覆盖。你可以更严格，但绝不能覆盖确定性失败。",
  cart:
    "你是 Cart Agent。用户已明确确认时，只根据刷新后的确定性校验决定是否生成购物车草案；绝不授权支付。",
};

function object(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function stringValue(value: unknown, maxLength = 240): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.replace(/\s+/g, " ").trim();
  return normalized && normalized.length <= maxLength ? normalized : null;
}

function stringArray(value: unknown, maxItems = 20): string[] | null {
  if (!Array.isArray(value) || value.length > maxItems) return null;
  if (!value.every((item) => typeof item === "string")) return null;
  return [...new Set(value.map((item) => item.trim()).filter(Boolean))];
}

function resolution<T>(payload: T, corrections: string[]): RoleExecutionResult<T> {
  return {
    payload,
    outcome: corrections.length === 0 ? "accepted" : "corrected",
    ...(corrections.length > 0 ? { corrections } : {}),
  };
}

export function resolveRetrievalPlan(
  message: string,
  proposal: unknown,
): RoleExecutionResult<RetrievalPlan> {
  const baseline = buildRetrievalPlan(message);
  const raw = object(proposal);
  if (!raw) return resolution(baseline, ["proposal_not_object"]);
  const corrections: string[] = [];

  const proposedIntent = typeof raw.intent === "string" && INTENTS.has(raw.intent as RetrievalPlan["intent"])
    ? raw.intent as RetrievalPlan["intent"]
    : baseline.intent;
  const intent = baseline.intent === "catalog" ? proposedIntent : baseline.intent;
  if (raw.intent !== undefined && proposedIntent === baseline.intent && raw.intent !== baseline.intent) {
    corrections.push("invalid_intent");
  } else if (proposedIntent !== baseline.intent && baseline.intent !== "catalog") {
    corrections.push("explicit_intent_preserved");
  }

  const proposedQuery = stringValue(raw.query);
  const query = proposedQuery ?? baseline.query;
  if (raw.query !== undefined && !proposedQuery) corrections.push("invalid_query");

  const proposedCategories = stringArray(raw.requestedCategories);
  const validCategories = (proposedCategories ?? []).filter(
    (item): item is ProductCategory => PRODUCT_CATEGORIES.has(item as ProductCategory),
  );
  if (proposedCategories && validCategories.length !== proposedCategories.length) {
    corrections.push("unsupported_category_removed");
  }
  const requestedCategories = baseline.requirements.requestedCategories;
  if (
    validCategories.length > 0 &&
    (validCategories.length !== requestedCategories.length ||
      validCategories.some((category) => !requestedCategories.includes(category)))
  ) {
    corrections.push("explicit_category_scope_preserved");
  }

  const proposedUseCases = stringArray(raw.useCases);
  const validUseCases = (proposedUseCases ?? []).filter((item) => USE_CASES.has(item));
  const useCases = [...new Set([...baseline.requirements.useCases, ...validUseCases])];
  if (proposedUseCases && validUseCases.length !== proposedUseCases.length) {
    corrections.push("unsupported_use_case_removed");
  }
  if (baseline.requirements.useCases.some((item) => !validUseCases.includes(item)) && proposedUseCases) {
    corrections.push("explicit_use_case_preserved");
  }

  const proposedBrands = stringArray(raw.preferredBrands, 8);
  const preferredBrands = baseline.requirements.preferredBrands;
  if (raw.preferredBrands !== undefined && !proposedBrands) {
    corrections.push("invalid_preferred_brands");
  } else if (proposedBrands && (
    proposedBrands.length !== preferredBrands.length ||
    proposedBrands.some((brand) => !preferredBrands.includes(brand))
  )) {
    corrections.push("ungrounded_brand_preference_removed");
  }

  const proposedSponsored = typeof raw.sponsoredAllowed === "boolean"
    ? raw.sponsoredAllowed
    : baseline.sponsoredAllowed;
  const sponsoredAllowed = baseline.sponsoredAllowed;
  if (proposedSponsored !== baseline.sponsoredAllowed) {
    corrections.push(
      baseline.sponsoredAllowed ? "sponsored_policy_preserved" : "ad_opt_out_enforced",
    );
  }

  const proposedChannels = stringArray(raw.channels, 3);
  const validChannels = (proposedChannels ?? []).filter(
    (item): item is RetrievalChannel => CHANNELS.has(item as RetrievalChannel),
  );
  const channelSet = new Set<RetrievalChannel>(
    proposedChannels ? validChannels : baseline.channels,
  );
  channelSet.add("search");
  if (baseline.intent === "bundle" && !channelSet.has("recommendation")) {
    channelSet.add("recommendation");
    corrections.push("bundle_recommendation_channel_restored");
  }
  if (!sponsoredAllowed) channelSet.delete("ads");
  if (proposedChannels && validChannels.length !== proposedChannels.length) {
    corrections.push("unsupported_channel_removed");
  }
  if (!sponsoredAllowed && validChannels.includes("ads")) corrections.push("ads_channel_removed");
  const channels = (["search", "recommendation", "ads"] as RetrievalChannel[])
    .filter((channel) => channelSet.has(channel));

  const rawBudget = object(raw.candidateBudget);
  const candidateBudget = { ...baseline.candidateBudget };
  for (const channel of ["search", "recommendation", "ads"] as const) {
    const value = rawBudget?.[channel];
    if (typeof value === "number" && Number.isFinite(value)) {
      const limit = channel === "ads" ? 4 : 12;
      candidateBudget[channel] = Math.max(1, Math.min(limit, Math.round(value)));
      if (candidateBudget[channel] !== value) corrections.push(`${channel}_candidate_budget_clamped`);
    } else if (value !== undefined) {
      corrections.push(`${channel}_candidate_budget_invalid`);
    }
  }

  const reason = stringValue(raw.reason, 500) ?? baseline.reason;
  if (raw.reason !== undefined && reason === baseline.reason && raw.reason !== baseline.reason) {
    corrections.push("invalid_reason");
  }

  return resolution({
    originalQuery: baseline.originalQuery,
    query,
    intent,
    requirements: {
      budgetMax: baseline.requirements.budgetMax,
      preferredBrands,
      requestedCategories,
      useCases,
      hardFields: baseline.requirements.hardFields,
      unknownFields: baseline.requirements.unknownFields,
      constraints: [
        ...baseline.requirements.constraints,
        ...validUseCases
          .filter((item) => !baseline.requirements.useCases.includes(item))
          .map((item, index) => ({
            constraintId: `constraint-inferred-use-case-${index}`,
            field: "useCases",
            value: item,
            source: "inferred_model" as const,
            strength: "soft" as const,
            confidence: 0.65,
            turnId: "turn-current",
            status: "active" as const,
          })),
      ],
    },
    channels,
    candidateBudget,
    sponsoredAllowed,
    reason,
  }, corrections);
}

function proposedSkuOrder(
  candidates: CandidateEnvelope[],
  proposal: unknown,
  field = "rankedSkuIds",
): { skuIds: string[]; rationaleBySku: Record<string, string>; corrections: string[] } {
  const raw = object(proposal);
  if (!raw) return { skuIds: [], rationaleBySku: {}, corrections: ["proposal_not_object"] };
  const requested = stringArray(raw[field], 50);
  if (!requested) return { skuIds: [], rationaleBySku: {}, corrections: [`invalid_${field}`] };
  const allowed = new Set(candidates.map((candidate) => candidate.product.skuId));
  const skuIds = requested.filter((skuId) => allowed.has(skuId));
  const corrections = skuIds.length === requested.length ? [] : ["unknown_sku_removed"];
  if (skuIds.length === 0 && candidates.length > 0) corrections.push("empty_model_ranking_fallback");
  const rawRationales = object(raw.rationaleBySku) ?? {};
  const rationaleBySku: Record<string, string> = {};
  for (const skuId of skuIds) {
    const rationale = stringValue(rawRationales[skuId], 160);
    if (rationale) rationaleBySku[skuId] = rationale;
  }
  return { skuIds, rationaleBySku, corrections };
}

export function resolveChannelRanking(
  result: ChannelResult,
  proposal: unknown,
): RoleExecutionResult<ChannelResult> {
  const ranked = proposedSkuOrder(result.candidates, proposal);
  if (ranked.skuIds.length === 0) return resolution(result, ranked.corrections);
  const rankBySku = new Map(ranked.skuIds.map((skuId, index) => [skuId, index]));
  const fallbackOffset = ranked.skuIds.length;
  const candidates = result.candidates
    .map((candidate, originalIndex) => {
      const modelIndex = rankBySku.get(candidate.product.skuId) ?? fallbackOffset + originalIndex;
      const modelScore = Math.max(0, 1 - modelIndex / Math.max(1, result.candidates.length));
      const rationale = ranked.rationaleBySku[candidate.product.skuId];
      return {
        candidate: {
          ...candidate,
          normalizedScore: Math.round((candidate.normalizedScore * 0.8 + modelScore * 0.2) * 1_000_000) / 1_000_000,
          reasons: rationale
            ? [...new Set([...candidate.reasons, `model_rank:${rationale}`])]
            : candidate.reasons,
        },
        modelIndex,
      };
    })
    .sort((left, right) => left.modelIndex - right.modelIndex)
    .map(({ candidate }) => candidate);
  return resolution({ ...result, candidates }, ranked.corrections);
}

export function resolveHandoff(
  candidates: CandidateEnvelope[],
  proposal: unknown,
): RoleExecutionResult<PeerHandoff> {
  const raw = object(proposal);
  const requested = stringArray(raw?.candidateSkuIds, 12);
  const allowed = new Map(
    candidates.filter((item) =>
      item.product.category === NORMAL_3C_DOMAIN.primaryCategory
    )
      .map((item) => [item.product.skuId, item]),
  );
  const selected = (requested ?? []).flatMap((skuId) => {
    const candidate = allowed.get(skuId);
    return candidate ? [candidate] : [];
  });
  const corrections: string[] = [];
  if (!requested) corrections.push("invalid_candidateSkuIds");
  else if (selected.length !== requested.length) corrections.push("unknown_handoff_sku_removed");
  const grounded = selected.length > 0 ? selected : [...allowed.values()];
  if (selected.length === 0) corrections.push("handoff_fallback_to_grounded_primary_products");
  return resolution({
    from: "search",
    to: "recommendation",
    purpose: "primary_product_grounding",
    candidates: grounded,
  }, corrections);
}

export function resolveBundleSkuOrder(
  slate: CandidateEnvelope[],
  proposal: unknown,
): { skuIds: string[]; result: RoleExecutionResult<null> } {
  const ranked = proposedSkuOrder(slate, proposal);
  return {
    skuIds: ranked.skuIds,
    result: resolution(null, ranked.corrections),
  };
}

export function validateExactIdentifierSet(
  proposal: unknown,
  field: "offerIds" | "productIds",
  expected: string[],
): RoleExecutionResult<null> {
  const raw = object(proposal);
  const values = stringArray(raw?.[field], 50);
  const expectedSet = new Set(expected);
  const exact = values && values.length === expectedSet.size && values.every((value) => expectedSet.has(value));
  return resolution(null, exact ? [] : [`${field}_replaced_with_authoritative_set`]);
}

export function resolveCritique(
  deterministic: Critique,
  proposal: unknown,
  facts: {
    averageReviewConfidence: number;
    useCaseMatched: boolean;
    compatibilityUnknown: boolean;
  },
): RoleExecutionResult<Critique> {
  const raw = object(proposal);
  const modelVerdict = raw?.verdict === "approved" || raw?.verdict === "vetoed"
    ? raw.verdict
    : "unavailable";
  const rationale = stringValue(raw?.rationale, 500) ?? "模型未提供可用审核说明";
  const requested = stringArray(raw?.additionalViolations, 8) ?? [];
  const evidence = new Map<string, boolean>([
    ["insufficient_review_confidence", facts.averageReviewConfidence < 0.55],
    ["weak_use_case_match", !facts.useCaseMatched],
    ["ambiguous_compatibility", facts.compatibilityUnknown],
  ]);
  const additional = requested.filter((name) => evidence.get(name) === true);
  const corrections: string[] = [];
  if (!raw) corrections.push("proposal_not_object");
  if (modelVerdict === "unavailable") corrections.push("invalid_model_verdict");
  if (additional.length !== requested.length) corrections.push("unsupported_model_violation_removed");
  const violations = [...new Set([...deterministic.violations, ...additional])];
  return resolution({
    verdict: violations.length === 0 ? "approved" : "vetoed",
    violations,
    checks: deterministic.checks,
    modelVerdict,
    modelRationale: rationale,
  }, corrections);
}

function numericClaims(message: string): number[] {
  return [...message.matchAll(/\d+(?:\.\d+)?/g)]
    .map((match) => Number(match[0]))
    .filter(Number.isFinite);
}

export function resolveFinalDecision(
  deterministicMessage: string,
  approved: boolean,
  allowedNumericFacts: number[],
  proposal: unknown,
): RoleExecutionResult<{ message: string; approved: boolean }> {
  const raw = object(proposal);
  const message = stringValue(raw?.message, 2_000);
  const proposedApproved = raw?.approved;
  const corrections: string[] = [];
  if (!message) corrections.push("invalid_final_message");
  if (proposedApproved !== approved) corrections.push("approval_status_enforced");
  const allowed = new Set(allowedNumericFacts.map((value) => Number(value.toFixed(4))));
  const unsupportedNumber = message
    ? numericClaims(message).some((value) => !allowed.has(Number(value.toFixed(4))))
    : false;
  if (unsupportedNumber) corrections.push("unsupported_numeric_claim_in_message");
  if (message && /(已支付|支付成功|已下单|订单已创建)/.test(message)) {
    corrections.push("transaction_claim_removed");
  }
  return resolution({
    message: corrections.some((item) => [
      "invalid_final_message",
      "unsupported_numeric_claim_in_message",
      "transaction_claim_removed",
    ].includes(item)) ? deterministicMessage : message ?? deterministicMessage,
    approved,
  }, corrections);
}

export function summarizeRuntime(
  runtime: PiRuntimeFactory,
  trace: AgentTraceRecord[],
): AgentRuntimeSummary {
  const roleExecutions = trace
    .filter((record) => record.event === "model_execution")
    .map((record) => record.detail as unknown as RoleModelExecution);
  const description = runtime.describe();
  return {
    mode: runtime.mode,
    provider: description.provider,
    model: description.model,
    localOnly: description.localOnly,
    modelCalls: runtime.mode === "modelport" ? roleExecutions.length : 0,
    proposalAccepted: roleExecutions.filter((item) => item.outcome === "accepted").length,
    proposalCorrected: roleExecutions.filter((item) => item.outcome === "corrected").length,
    fallbackCount: roleExecutions.filter((item) => item.outcome === "fallback").length,
    totalTokens: roleExecutions.reduce((sum, item) => sum + item.totalTokens, 0),
    roleExecutions,
  };
}
