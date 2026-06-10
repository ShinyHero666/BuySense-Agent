import type {
  CandidateEnvelope,
  BundleProposal,
  CatalogProduct,
  ChannelResult,
  CompatibilityResult,
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
import { isSupportedProductCategory, NORMAL_3C_DOMAIN } from "./domain-pack.js";

type JsonObject = Record<string, unknown>;

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

function category(value: unknown): ProductCategory {
  const parsed = string(value, "category");
  if (!isSupportedProductCategory(parsed)) {
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

function parseCandidate(value: unknown, expectedChannel: RetrievalChannel): CandidateEnvelope {
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
    category: category(raw.category),
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
function wireCandidate(candidate: CandidateEnvelope): JsonObject {
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
  constructor(private readonly baseUrl = "http://127.0.0.1:18083") {}

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
    const raw = await this.#request(`/api/v2/discovery/${endpoint}`, {
        query: `${plan.originalQuery} ${plan.query}`.trim(),
        requested_categories: plan.requirements.requestedCategories,
        use_cases: plan.requirements.useCases,
        preferred_brands: plan.requirements.preferredBrands,
        primary_product_ids: (context.peerCandidates ?? [])
          .filter((candidate) =>
            candidate.product.category === NORMAL_3C_DOMAIN.primaryCategory
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
    }, context.signal);
    if (string(raw.channel, "channel") !== channel) {
      throw new Error(`Python discovery response channel mismatch for ${channel}`);
    }
    if (!Array.isArray(raw.items)) {
      throw new Error("Python discovery response items must be an array");
    }
    return {
      channel,
      candidates: raw.items.map((item) => parseCandidate(item, channel)),
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

  async fuse(results: ChannelResult[], limit = 8): Promise<CandidateEnvelope[]> {
    const raw = await this.#request("/api/v2/decision/fuse", {
      channels: results.map((result) => ({
        channel: result.channel,
        items: result.candidates.map(wireCandidate),
      })),
      limit,
    });
    if (!Array.isArray(raw.items)) throw new Error("Python fusion items must be an array");
    return raw.items.map((value) => {
      const item = object(value, "items[]");
      const channel = string(item.channel, "channel") as RetrievalChannel;
      if (!["search", "recommendation", "ads"].includes(channel)) {
        throw new Error(`Python fusion returned unsupported channel: ${channel}`);
      }
      return parseCandidate(item, channel);
    });
  }

  async optimizeBundle(
    slate: CandidateEnvelope[],
    plan: RetrievalPlan,
  ): Promise<BundleProposal> {
    const raw = await this.#request("/api/v2/decision/bundles", {
      items: slate.map(wireCandidate),
      requested_categories: plan.requirements.requestedCategories,
      intent: plan.intent,
      budget_max: plan.requirements.budgetMax,
      top_n: 3,
    });
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
  ): Promise<CompatibilityResult[]> {
    const raw = await this.#request("/api/v2/evidence/compatibility", {
      pairs: accessories.map((accessory) => ({
        product_sku_id: primary.product.skuId,
        accessory_sku_id: accessory.product.skuId,
      })),
    });
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

  async quote(items: CandidateEnvelope[]): Promise<PriceQuoteBatch> {
    const raw = await this.#request("/api/v2/pricing/quote", {
      offer_ids: items.map((item) => item.product.offerId),
    });
    if (!Array.isArray(raw.quotes)) {
      throw new Error("Python pricing quotes must be an array");
    }
    return {
      quoteBatchId: string(raw.quote_batch_id, "quote_batch_id"),
      quoteVersion: string(raw.quote_version, "quote_version"),
      issuedAt: string(raw.issued_at, "issued_at"),
      quotes: raw.quotes.map((value) => {
        const quote = object(value, "quotes[]");
        const status = string(quote.status, "status");
        if (!["active", "unavailable"].includes(status)) {
          throw new Error(`Python pricing returned invalid status: ${status}`);
        }
        const currency = string(quote.currency, "currency");
        if (currency !== "CNY") throw new Error(`Python pricing returned unsupported currency: ${currency}`);
        return {
          offerId: string(quote.offer_id, "offer_id"),
          status: status as "active" | "unavailable",
          amount: quote.amount === null ? null : number(quote.amount, "amount"),
          currency: "CNY" as const,
          stock: number(quote.stock, "stock"),
          validUntil: string(quote.valid_until, "valid_until"),
          reason: string(quote.reason, "reason"),
        };
      }),
    };
  }

  async reviewAspects(productIds: string[]): Promise<ReviewEvidenceBatch> {
    const raw = await this.#request("/api/v2/evidence/reviews", {
      product_ids: productIds,
    });
    if (!Array.isArray(raw.products)) {
      throw new Error("Python review products must be an array");
    }
    const products: ProductReviewEvidence[] = raw.products.map((value) => {
      const product = object(value, "products[]");
      if (product.source !== "synthetic_review_snapshot") {
        throw new Error("Python review evidence returned an unsupported source");
      }
      if (!Array.isArray(product.aspects)) {
        throw new Error("Python review aspects must be an array");
      }
      return {
        productId: string(product.product_id, "product_id"),
        sampleSize: number(product.sample_size, "sample_size"),
        source: "synthetic_review_snapshot",
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
    return {
      reviewSnapshotVersion: string(
        raw.review_snapshot_version,
        "review_snapshot_version",
      ),
      products,
      missingProductIds: strings(raw.missing_product_ids, "missing_product_ids"),
    };
  }
}
