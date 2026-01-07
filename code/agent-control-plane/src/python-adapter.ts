import type {
  CandidateEnvelope,
  BundleProposal,
  CatalogProduct,
  ChannelResult,
  CompatibilityResult,
  DataSourceMetadata,
  DecisionEvidenceGateway,
  DecisionOptimizationGateway,
  DiscoveryChannels,
  DiscoveryContext,
  PriceQuoteBatch,
  ProductCategory,
  ProductReviewEvidence,
  RetrievalChannel,
  RetrievalPlan,
  ReviewEvidenceBatch,
} from "./contracts.js";
import {
  isSupportedProductCategory,
  NORMAL_3C_DOMAIN,
  type CommerceDomainPack,
} from "./domain-pack.js";
import {
  assertContract,
  type BundleOptimizationWireRequest,
  type CandidateWireRecord,
  type DiscoveryWireRequest,
  type FusionWireRequest,
  type PricingQuoteWireRequest,
  type ReviewEvidenceWireRequest,
} from "./generated/contracts-v2.js";

type JsonObject = Record<string, unknown>;
type CandidateWirePayload = CandidateWireRecord & JsonObject;
const MAX_DISCOVERY_QUERY_CODE_POINTS = 4_096;
const SAFE_PROVIDER_ID = /^[a-z][a-z0-9._-]{0,127}$/;
const SAFE_SOURCE_VERSION = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/;

function takeCodePoints(value: string, limit: number): string {
  return Array.from(value).slice(0, limit).join("");
}

/** Keep both grounded inputs when possible while respecting the Python wire bound. */
export function discoveryWireQuery(originalQuery: string, query: string): string {
  if (originalQuery === query) return takeCodePoints(originalQuery, MAX_DISCOVERY_QUERY_CODE_POINTS);
  const available = MAX_DISCOVERY_QUERY_CODE_POINTS - 1;
  const fairShare = Math.floor(available / 2);
  const originalLength = Array.from(originalQuery).length;
  const queryLength = Array.from(query).length;
  let originalBudget = Math.min(originalLength, fairShare);
  let queryBudget = Math.min(queryLength, fairShare);
  let remaining = available - originalBudget - queryBudget;
  const extraOriginal = Math.min(remaining, originalLength - originalBudget);
  originalBudget += extraOriginal;
  remaining -= extraOriginal;
  queryBudget += Math.min(remaining, queryLength - queryBudget);
  return `${takeCodePoints(originalQuery, originalBudget)} ${takeCodePoints(query, queryBudget)}`.trim();
}

function object(value: unknown, field: string): JsonObject {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(`Python discovery response ${field} must be an object`);
  }
  return value as JsonObject;
}

function string(value: unknown, field: string): string {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`Python discovery response ${field} must be a non-empty string`);
  }
  return value;
}

function number(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new Error(`Python discovery response ${field} must be a finite number`);
  }
  return value;
}

function strings(value: unknown, field: string): string[] {
  if (!Array.isArray(value) || !value.every((item) => typeof item === "string")) {
    throw new Error(`Python discovery response ${field} must be a string array`);
  }
  return value;
}

function dataSource(value: unknown, field: string): DataSourceMetadata {
  const raw = object(value, field);
  const unknown = Object.keys(raw).filter(
    (key) => !["source", "source_version", "provider_id"].includes(key),
  );
  if (unknown.length > 0) {
    throw new Error(`Python data source ${field} has unknown fields: ${unknown.sort().join(", ")}`);
  }
  const source = string(raw.source, `${field}.source`);
  if (!['local_snapshot', 'remote_provider'].includes(source)) {
    throw new Error(`Python data source ${field}.source is unsupported: ${source}`);
  }
  const providerId = string(raw.provider_id, `${field}.provider_id`);
  if (!SAFE_PROVIDER_ID.test(providerId)) {
    throw new Error(`Python data source ${field}.provider_id is not a safe identifier`);
  }
  const sourceVersion = string(raw.source_version, `${field}.source_version`);
  if (!SAFE_SOURCE_VERSION.test(sourceVersion)) {
    throw new Error(`Python data source ${field}.source_version is not a safe version`);
  }
  return {
    source: source as DataSourceMetadata["source"],
    sourceVersion,
    providerId,
  };
}

function category(value: unknown, domain: CommerceDomainPack): ProductCategory {
  const parsed = string(value, "category");
  if (!isSupportedProductCategory(parsed, domain)) {
    throw new Error(`Python discovery returned unsupported category: ${parsed}`);
  }
  return parsed;
}

function ecosystem(value: unknown): CatalogProduct["ecosystem"] {
  const parsed = string(value, "ecosystem");
  if (!["ios", "android", "universal"].includes(parsed)) {
    throw new Error(`Python discovery returned unsupported ecosystem: ${parsed}`);
  }
  return parsed as CatalogProduct["ecosystem"];
}

function parseCandidate(
  value: unknown,
  expectedChannel: RetrievalChannel,
  domain: CommerceDomainPack,
  source: DataSourceMetadata,
): CandidateEnvelope {
  const raw = object(value, "items[]");
  const responseChannel = string(raw.channel, "channel");
  if (responseChannel !== expectedChannel) {
    throw new Error(`Python discovery channel mismatch: expected ${expectedChannel}, got ${responseChannel}`);
  }
  const sponsored = raw.sponsored === true;
  if (sponsored !== (expectedChannel === "ads")) {
    throw new Error("Python discovery sponsored flag does not match channel");
  }
  const maxPower = raw.max_power_watts;
  if (maxPower !== null && maxPower !== undefined) number(maxPower, "max_power_watts");
  const product: CatalogProduct = {
    spuId: string(raw.spu_id, "spu_id"),
    productId: string(raw.product_id, "product_id"),
    skuId: string(raw.sku_id, "sku_id"),
    offerId: string(raw.offer_id, "offer_id"),
    title: string(raw.title, "title"),
    category: category(raw.category, domain),
    brand: string(raw.brand, "brand"),
    price: number(raw.price, "price"),
    stock: number(raw.stock, "stock"),
    tags: strings(raw.tags, "tags"),
    ecosystem: ecosystem(raw.ecosystem),
    connectors: strings(raw.connectors, "connectors"),
    protocols: strings(raw.protocols, "protocols"),
    ...(maxPower === null || maxPower === undefined
      ? {}
      : { maxPowerWatts: number(maxPower, "max_power_watts") }),
    sponsored,
    ...(raw.ad_bid === undefined ? {} : { adBid: number(raw.ad_bid, "ad_bid") }),
    ...(raw.ad_quality === undefined ? {} : { adQuality: number(raw.ad_quality, "ad_quality") }),
    catalogVersion: string(raw.catalog_version, "catalog_version"),
    currency: string(raw.currency, "currency") as "CNY",
    quoteVersion: string(raw.quote_version, "quote_version"),
    quoteValidUntil: string(raw.quote_valid_until, "quote_valid_until"),
    dataSource: source,
  };
  if (product.currency !== "CNY") {
    throw new Error(`Python discovery returned unsupported currency: ${product.currency}`);
  }
  return {
    product,
    channel: expectedChannel,
    sources: raw.sources === undefined
      ? [expectedChannel]
      : strings(raw.sources, "sources").filter((source): source is RetrievalChannel =>
          ["search", "recommendation", "ads"].includes(source)
        ),
    channelScore: number(raw.channel_score, "channel_score"),
    normalizedScore: number(raw.normalized_score, "normalized_score"),
    reasons: strings(raw.reasons, "reasons"),
    sponsored,
    disclosure: sponsored ? "赞助" : null,
  };
}

/** Typed HTTP boundary to the normal-commerce Python discovery and evidence data plane. */
function wireCandidate(candidate: CandidateEnvelope): CandidateWirePayload {
  return {
    spu_id: candidate.product.spuId,
    product_id: candidate.product.productId,
    sku_id: candidate.product.skuId,
    offer_id: candidate.product.offerId,
    title: candidate.product.title,
    category: candidate.product.category,
    brand: candidate.product.brand,
    price: candidate.product.price,
    currency: candidate.product.currency,
    stock: candidate.product.stock,
    tags: candidate.product.tags,
    ecosystem: candidate.product.ecosystem,
    connectors: candidate.product.connectors,
    protocols: candidate.product.protocols,
    max_power_watts: candidate.product.maxPowerWatts ?? null,
    catalog_version: candidate.product.catalogVersion,
    quote_version: candidate.product.quoteVersion,
    quote_valid_until: candidate.product.quoteValidUntil,
    ad_bid: candidate.product.adBid ?? 0,
    ad_quality: candidate.product.adQuality ?? 0,
    channel: candidate.channel,
    sources: candidate.sources,
    channel_score: candidate.channelScore,
    normalized_score: candidate.normalizedScore,
    reasons: candidate.reasons,
    sponsored: candidate.sponsored,
    disclosure: candidate.disclosure,
  };
}

/** Typed HTTP boundary to the Python-owned retrieval, fusion, evidence and optimization services. */
export class PythonDiscoveryAdapter implements
  DiscoveryChannels,
  DecisionEvidenceGateway,
  DecisionOptimizationGateway
{
  constructor(
    private readonly baseUrl = "http://127.0.0.1:18083",
    private readonly domain: CommerceDomainPack = NORMAL_3C_DOMAIN,
  ) {}

  async #request(path: string, payload: unknown, signal?: AbortSignal): Promise<JsonObject> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload),
      signal: signal
        ? AbortSignal.any([signal, AbortSignal.timeout(3_000)])
        : AbortSignal.timeout(3_000),
    });
    if (!response.ok) {
      throw new Error(`Python data plane ${path} returned HTTP ${response.status}`);
    }
    return object(await response.json(), "root");
  }

  async #discover(
    channel: RetrievalChannel,
    plan: RetrievalPlan,
    context: DiscoveryContext = {},
  ): Promise<ChannelResult> {
    const endpoint = channel === "recommendation" ? "recommend" : channel;
    const request: DiscoveryWireRequest = {
      domain_pack_id: this.domain.packId,
      query: discoveryWireQuery(plan.originalQuery, plan.query),
      requested_categories: plan.requirements.requestedCategories,
      use_cases: plan.requirements.useCases,
      preferred_brands: plan.requirements.preferredBrands,
      primary_product_ids: (context.peerCandidates ?? [])
        .filter((candidate) =>
          candidate.product.category === this.domain.primaryCategory
        )
        .map((candidate) => candidate.product.productId),
      max_price: plan.requirements.budgetMax,
      limit: plan.candidateBudget[channel],
      sponsored_allowed: plan.sponsoredAllowed,
      identity_id: context.identityId ?? "",
      session_id: context.sessionId ?? "",
      personalization_enabled: context.personalizationEnabled ?? true,
      recent_product_ids: context.recentProductIds ?? [],
      excluded_product_ids: context.excludedProductIds ?? [],
      ad_exposure_product_ids: context.adExposureProductIds ?? [],
    };
    assertContract("DiscoveryWireRequest", request);
    const raw = await this.#request(
      `/api/v2/discovery/${endpoint}`,
      request,
      context.signal,
    );
    assertContract("DiscoveryWireResponse", raw);
    if (string(raw.channel, "channel") !== channel) {
      throw new Error(`Python discovery response channel mismatch for ${channel}`);
    }
    if (!Array.isArray(raw.items)) {
      throw new Error("Python discovery response items must be an array");
    }
    const source = dataSource(raw.data_source, "data_source");
    const catalogVersion = string(raw.catalog_version, "catalog_version");
    const quoteVersion = string(raw.quote_version, "quote_version");
    if (source.sourceVersion !== catalogVersion) {
      throw new Error("Python discovery data source version does not match catalog_version");
    }
    const candidates = raw.items.map((item) => parseCandidate(item, channel, this.domain, source));
    if (candidates.some((item) => item.product.catalogVersion !== catalogVersion)) {
      throw new Error("Python discovery item catalog_version does not match response");
    }
    if (candidates.some((item) => item.product.quoteVersion !== quoteVersion)) {
      throw new Error("Python discovery item quote_version does not match response");
    }
    return {
      channel,
      dataSource: source,
      candidates,
    };
  }

  search(plan: RetrievalPlan, context: DiscoveryContext = {}): Promise<ChannelResult> {
    return this.#discover("search", plan, context);
  }

  recommend(plan: RetrievalPlan, context: DiscoveryContext = {}): Promise<ChannelResult> {
    return this.#discover("recommendation", plan, context);
  }

  ads(plan: RetrievalPlan, context: DiscoveryContext = {}): Promise<ChannelResult> {
    if (!plan.sponsoredAllowed) return Promise.resolve({ channel: "ads", candidates: [] });
    return this.#discover("ads", plan, context);
  }

  async fuse(
    results: ChannelResult[],
    limit = 8,
    signal?: AbortSignal,
  ): Promise<CandidateEnvelope[]> {
    const request: FusionWireRequest = {
      domain_pack_id: this.domain.packId,
      channels: results.map((result) => ({
        channel: result.channel,
        items: result.candidates.map(wireCandidate),
      })),
      limit,
    };
    assertContract("FusionWireRequest", request);
    const raw = await this.#request("/api/v2/decision/fuse", request, signal);
    if (!Array.isArray(raw.items)) throw new Error("Python fusion items must be an array");
    const sourcesBySku = new Map(
      results.flatMap((result) => result.candidates)
        .map((candidate) => [candidate.product.skuId, candidate.product.dataSource] as const),
    );
    return raw.items.map((value) => {
      const item = object(value, "items[]");
      const channel = string(item.channel, "channel") as RetrievalChannel;
      if (!["search", "recommendation", "ads"].includes(channel)) {
        throw new Error(`Python fusion returned unsupported channel: ${channel}`);
      }
      const source = sourcesBySku.get(string(item.sku_id, "sku_id"));
      if (!source) throw new Error("Python fusion returned a SKU without catalog provenance");
      return parseCandidate(item, channel, this.domain, source);
    });
  }

  async optimizeBundle(
    slate: CandidateEnvelope[],
    plan: RetrievalPlan,
    signal?: AbortSignal,
  ): Promise<BundleProposal> {
    const request: BundleOptimizationWireRequest = {
      domain_pack_id: this.domain.packId,
      items: slate.map(wireCandidate),
      requested_categories: plan.requirements.requestedCategories,
      intent: plan.intent,
      budget_max: plan.requirements.budgetMax,
      top_n: 3,
    };
    assertContract("BundleOptimizationWireRequest", request);
    const raw = await this.#request("/api/v2/decision/bundles", request, signal);
    if (!Array.isArray(raw.bundles)) throw new Error("Python optimizer bundles must be an array");
    const candidatesBySku = new Map(slate.map((candidate) => [candidate.product.skuId, candidate]));
    const parsed = raw.bundles.map((value) => {
      const bundle = object(value, "bundles[]");
      const skuIds = strings(bundle.sku_ids, "sku_ids");
      const items = skuIds.map((skuId) => {
        const candidate = candidatesBySku.get(skuId);
        if (!candidate) throw new Error(`Python optimizer returned unknown SKU: ${skuId}`);
        return candidate;
      });
      if (!Array.isArray(bundle.compatibility)) {
        throw new Error("Python optimizer compatibility must be an array");
      }
      const compatibility: CompatibilityResult[] = bundle.compatibility.map((value) => {
        const item = object(value, "compatibility[]");
        const status = string(item.status, "status");
        if (!["compatible", "incompatible", "unknown"].includes(status)) {
          throw new Error(`Python optimizer returned invalid compatibility status: ${status}`);
        }
        return {
          productId: string(item.product_id, "product_id"),
          accessoryId: string(item.accessory_id, "accessory_id"),
          status: status as CompatibilityResult["status"],
          reasons: strings(item.reasons, "reasons"),
          ruleVersion: string(item.rule_version, "rule_version"),
          paths: strings(item.paths, "paths"),
        };
      });
      return {
        skuIds,
        items,
        totalPrice: number(bundle.total_price, "total_price"),
        score: number(bundle.score, "score"),
        sponsoredCount: number(bundle.sponsored_count, "sponsored_count"),
        compatibility,
      };
    });
    const selected = parsed[0];
    if (!selected) {
      return {
        items: [],
        totalPrice: 0,
        budgetMax: plan.requirements.budgetMax,
        withinBudget: false,
        compatibility: [],
        score: 0,
        optimization: "python_constraint_optimizer",
        alternatives: [],
      };
    }
    return {
      items: selected.items,
      totalPrice: selected.totalPrice,
      budgetMax: plan.requirements.budgetMax,
      withinBudget: plan.requirements.budgetMax === null || selected.totalPrice <= plan.requirements.budgetMax,
      compatibility: selected.compatibility,
      score: selected.score,
      optimization: "python_constraint_optimizer",
      alternatives: parsed.map((bundle) => ({
        skuIds: bundle.skuIds,
        totalPrice: bundle.totalPrice,
        score: bundle.score,
        sponsoredCount: bundle.sponsoredCount,
      })),
    };
  }

  async checkCompatibility(
    primary: CandidateEnvelope,
    accessories: CandidateEnvelope[],
    signal?: AbortSignal,
  ): Promise<CompatibilityResult[]> {
    const raw = await this.#request("/api/v2/evidence/compatibility", {
      domain_pack_id: this.domain.packId,
      pairs: accessories.map((accessory) => ({
        product_sku_id: primary.product.skuId,
        accessory_sku_id: accessory.product.skuId,
      })),
    }, signal);
    const graphVersion = string(raw.graph_version, "graph_version");
    if (!Array.isArray(raw.results)) {
      throw new Error("Python compatibility results must be an array");
    }
    const accessoriesBySku = new Map(
      accessories.map((candidate) => [candidate.product.skuId, candidate]),
    );
    return raw.results.map((value) => {
      const result = object(value, "results[]");
      const accessorySku = string(result.accessory_sku_id, "accessory_sku_id");
      const accessory = accessoriesBySku.get(accessorySku);
      if (!accessory) throw new Error(`Python compatibility returned unexpected SKU: ${accessorySku}`);
      const status = string(result.status, "status");
      if (!["compatible", "incompatible", "unknown"].includes(status)) {
        throw new Error(`Python compatibility returned invalid status: ${status}`);
      }
      return {
        productId: primary.product.productId,
        accessoryId: accessory.product.productId,
        status: status as CompatibilityResult["status"],
        reasons: strings(result.reasons, "reasons"),
        ruleVersion: graphVersion,
        paths: strings(result.paths, "paths"),
      };
    });
  }

  async quote(items: CandidateEnvelope[], signal?: AbortSignal): Promise<PriceQuoteBatch> {
    const request: PricingQuoteWireRequest = {
      domain_pack_id: this.domain.packId,
      offer_ids: items.map((item) => item.product.offerId),
    };
    assertContract("PricingQuoteWireRequest", request);
    const raw = await this.#request("/api/v2/pricing/quote", request, signal);
    assertContract("PricingQuoteWireResponse", raw);
    if (!Array.isArray(raw.quotes)) {
      throw new Error("Python pricing quotes must be an array");
    }
    const source = dataSource(raw.data_source, "data_source");
    const quoteVersion = string(raw.quote_version, "quote_version");
    if (source.sourceVersion !== quoteVersion) {
      throw new Error("Python pricing data source version does not match quote_version");
    }
    const quotes = raw.quotes.map((value) => {
      const quote = object(value, "quotes[]");
      const status = string(quote.status, "status");
      if (!["active", "unavailable"].includes(status)) {
        throw new Error(`Python pricing returned invalid status: ${status}`);
      }
      const currency = string(quote.currency, "currency");
      if (currency !== "CNY") throw new Error(`Python pricing returned unsupported currency: ${currency}`);
      const stock = number(quote.stock, "stock");
      if (!Number.isInteger(stock) || stock < 0) {
        throw new Error("Python pricing stock must be a non-negative integer");
      }
      const amount = quote.amount === null ? null : number(quote.amount, "amount");
      if (amount !== null && amount < 0) throw new Error("Python pricing amount must be non-negative");
      return {
        offerId: string(quote.offer_id, "offer_id"),
        status: status as "active" | "unavailable",
        amount,
        currency: "CNY" as const,
        stock,
        validUntil: string(quote.valid_until, "valid_until"),
        reason: string(quote.reason, "reason"),
      };
    });
    const requestedOffers = new Set(items.map((item) => item.product.offerId));
    const returnedOffers = new Set(quotes.map((quote) => quote.offerId));
    if (
      returnedOffers.size !== quotes.length ||
      returnedOffers.size !== requestedOffers.size ||
      [...returnedOffers].some((offerId) => !requestedOffers.has(offerId))
    ) throw new Error("Python pricing quotes do not exactly match requested offers");
    return {
      quoteBatchId: string(raw.quote_batch_id, "quote_batch_id"),
      quoteVersion,
      issuedAt: string(raw.issued_at, "issued_at"),
      dataSource: source,
      quotes,
    };
  }

  async reviewAspects(
    productIds: string[],
    signal?: AbortSignal,
  ): Promise<ReviewEvidenceBatch> {
    const request: ReviewEvidenceWireRequest = {
      domain_pack_id: this.domain.packId,
      product_ids: productIds,
    };
    assertContract("ReviewEvidenceWireRequest", request);
    const raw = await this.#request("/api/v2/evidence/reviews", request, signal);
    assertContract("ReviewEvidenceWireResponse", raw);
    if (!Array.isArray(raw.products)) {
      throw new Error("Python review products must be an array");
    }
    const batchSource = dataSource(raw.data_source, "data_source");
    const reviewSnapshotVersion = string(
      raw.review_snapshot_version,
      "review_snapshot_version",
    );
    if (batchSource.sourceVersion !== reviewSnapshotVersion) {
      throw new Error("Python review data source version does not match review_snapshot_version");
    }
    const products: ProductReviewEvidence[] = raw.products.map((value) => {
      const product = object(value, "products[]");
      const source = dataSource({
        source: product.source,
        source_version: product.source_version,
        provider_id: product.provider_id,
      }, "products[].data_source");
      if (!Array.isArray(product.aspects)) {
        throw new Error("Python review aspects must be an array");
      }
      return {
        productId: string(product.product_id, "product_id"),
        sampleSize: number(product.sample_size, "sample_size"),
        source: source.source,
        sourceVersion: source.sourceVersion,
        providerId: source.providerId,
        aspects: product.aspects.map((value) => {
          const aspect = object(value, "aspects[]");
          const sentiment = number(aspect.sentiment, "sentiment");
          const confidence = number(aspect.confidence, "confidence");
          if (sentiment < -1 || sentiment > 1) {
            throw new Error("Python review sentiment must be between -1 and 1");
          }
          if (confidence < 0 || confidence > 1) {
            throw new Error("Python review confidence must be between 0 and 1");
          }
          return {
            aspect: string(aspect.aspect, "aspect"),
            sentiment,
            mentionCount: number(aspect.mention_count, "mention_count"),
            confidence,
            summary: string(aspect.summary, "summary"),
          };
        }),
      };
    });
    const requestedProducts = new Set(productIds);
    const returnedProducts = new Set(products.map((product) => product.productId));
    const missingProductIds = strings(raw.missing_product_ids, "missing_product_ids");
    const missingProducts = new Set(missingProductIds);
    if (
      returnedProducts.size !== products.length ||
      missingProductIds.length !== missingProducts.size ||
      [...returnedProducts].some((productId) => missingProducts.has(productId)) ||
      [...returnedProducts, ...missingProductIds].some((productId) => !requestedProducts.has(productId)) ||
      [...requestedProducts].some(
        (productId) => !returnedProducts.has(productId) && !missingProducts.has(productId),
      )
    ) throw new Error("Python review evidence does not partition requested products");
    if (products.some((product) =>
      product.source !== batchSource.source ||
      product.sourceVersion !== batchSource.sourceVersion ||
      product.providerId !== batchSource.providerId
    )) {
      throw new Error("Python review product provenance does not match batch provenance");
    }
    return {
      reviewSnapshotVersion,
      dataSource: batchSource,
      products,
      missingProductIds,
    };
  }
}
