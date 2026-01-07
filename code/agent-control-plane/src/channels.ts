import { DEMO_CATALOG } from "./catalog.js";
import type {
  CandidateEnvelope,
  CatalogProduct,
  ChannelResult,
  DiscoveryContext,
  DiscoveryChannels,
  ProductCategory,
  RetrievalChannel,
  RetrievalPlan,
} from "./contracts.js";
import { NORMAL_3C_DOMAIN, type CommerceDomainPack } from "./domain-pack.js";

function clamp(value: number): number {
  return Math.max(0, Math.min(1, value));
}

function categoryFit(product: CatalogProduct, plan: RetrievalPlan): number {
  return plan.requirements.requestedCategories.includes(product.category) ? 1 : 0;
}

function termFit(product: CatalogProduct, plan: RetrievalPlan): number {
  const haystack = [product.title, product.brand, ...product.tags].join(" ").toLowerCase();
  const queryTerms = plan.originalQuery
    .split(/[\s,，。.!！？:：;；、]+/u)
    .filter((term) => term.length >= 2);
  const terms = [...queryTerms, ...plan.requirements.useCases, ...plan.requirements.preferredBrands];
  if (terms.length === 0) return 0.5;
  return terms.filter((term) => haystack.includes(term.toLowerCase())).length / terms.length;
}

function eligible(product: CatalogProduct, plan: RetrievalPlan): boolean {
  if (product.stock <= 0) return false;
  const budget = plan.requirements.budgetMax;
  if (budget !== null && product.price > budget) return false;
  return categoryFit(product, plan) > 0;
}

function accessoryEligible(
  product: CatalogProduct,
  plan: RetrievalPlan,
  domain: CommerceDomainPack,
): boolean {
  if (!plan.requirements.requestedCategories.includes(domain.primaryCategory)) return true;
  const requirement = domain.categoryRequirements[product.category];
  if (!requirement) return true;
  const connectorMatch = requirement.connectorsAny.length === 0 ||
    requirement.connectorsAny.some((value) => product.connectors.includes(value));
  const protocolMatch = requirement.protocolsAny.length === 0 ||
    requirement.protocolsAny.some((value) => product.protocols.includes(value));
  return connectorMatch && protocolMatch;
}

function normalize(candidates: CandidateEnvelope[]): CandidateEnvelope[] {
  if (candidates.length === 0) return [];
  const scores = candidates.map((candidate) => candidate.channelScore);
  const minimum = Math.min(...scores);
  const maximum = Math.max(...scores);
  return candidates.map((candidate) => ({
    ...candidate,
    normalizedScore:
      maximum === minimum ? 1 : (candidate.channelScore - minimum) / (maximum - minimum),
  }));
}

function envelope(
  product: CatalogProduct,
  channel: RetrievalChannel,
  score: number,
  reasons: string[],
): CandidateEnvelope {
  return {
    product,
    channel,
    sources: [channel],
    channelScore: Number(clamp(score).toFixed(6)),
    normalizedScore: 0,
    reasons,
    sponsored: channel === "ads",
    disclosure: channel === "ads" ? "赞助" : null,
  };
}

function top(
  candidates: CandidateEnvelope[],
  limit: number,
): CandidateEnvelope[] {
  return normalize(
    candidates
      .sort((left, right) => right.channelScore - left.channelScore)
      .slice(0, limit),
  );
}

export class InMemoryDiscoveryChannels implements DiscoveryChannels {
  constructor(
    private readonly catalog: CatalogProduct[] = DEMO_CATALOG,
    private readonly domain: CommerceDomainPack = NORMAL_3C_DOMAIN,
  ) {}

  async search(plan: RetrievalPlan, _context: DiscoveryContext = {}): Promise<ChannelResult> {
    const primaryCategory: ProductCategory = plan.requirements.requestedCategories.includes(
      this.domain.primaryCategory,
    )
      ? this.domain.primaryCategory
      : (plan.requirements.requestedCategories[0] ?? this.domain.defaultCategory);
    const candidates = this.catalog
      .filter((product) => eligible(product, plan) && product.category === primaryCategory)
      .map((product) => {
        const terms = termFit(product, plan);
        const brand = plan.requirements.preferredBrands.includes(product.brand) ? 0.2 : 0;
        const price = plan.requirements.budgetMax
          ? 0.15 * (1 - product.price / plan.requirements.budgetMax)
          : 0.05;
        return envelope(product, "search", 0.55 + 0.25 * terms + brand + price, [
          "structured_filter_passed",
          `term_fit=${terms.toFixed(2)}`,
        ]);
      });
    return {
      channel: "search",
      ...(this.catalog[0] ? { dataSource: this.catalog[0].dataSource } : {}),
      candidates: top(candidates, plan.candidateBudget.search),
    };
  }

  async recommend(plan: RetrievalPlan, context: DiscoveryContext = {}): Promise<ChannelResult> {
    const accessoryCategories = new Set<ProductCategory>(
      plan.requirements.requestedCategories.filter(
        (category) => category !== this.domain.primaryCategory,
      ),
    );
    const allowed = accessoryCategories.size > 0
      ? accessoryCategories
      : new Set([this.domain.primaryCategory] as ProductCategory[]);
    const candidates = this.catalog
      .filter(
        (product) =>
          eligible(product, plan) &&
          allowed.has(product.category) &&
          accessoryEligible(product, plan, this.domain),
      )
      .map((product) => {
        const terms = termFit(product, plan);
        const universal = product.ecosystem === "universal" ? 0.12 : 0;
        const peerEcosystems = new Set(
          (context.peerCandidates ?? [])
            .filter((candidate) =>
              candidate.product.category === this.domain.primaryCategory
            )
            .map((candidate) => candidate.product.ecosystem),
        );
        const peerFit = peerEcosystems.size > 0 &&
          (product.ecosystem === "universal" || peerEcosystems.has(product.ecosystem))
          ? 0.08
          : 0;
        const value = plan.requirements.budgetMax
          ? 0.18 * (1 - product.price / plan.requirements.budgetMax)
          : 0.05;
        const reasons = [
          "session_intent_match",
          universal > 0 ? "universal_ecosystem" : "ecosystem_specific",
        ];
        if (peerFit > 0) reasons.push("search_peer_context_match");
        if (context.revision) reasons.push("critic_revision_attempt=1");
        return envelope(
          product,
          "recommendation",
          0.45 + 0.25 * terms + universal + peerFit + value,
          reasons,
        );
      });
    return {
      channel: "recommendation",
      ...(this.catalog[0] ? { dataSource: this.catalog[0].dataSource } : {}),
      candidates: top(candidates, plan.candidateBudget.recommendation),
    };
  }

  async ads(plan: RetrievalPlan, context: DiscoveryContext = {}): Promise<ChannelResult> {
    if (!plan.sponsoredAllowed) {
      return {
        channel: "ads",
        ...(this.catalog[0] ? { dataSource: this.catalog[0].dataSource } : {}),
        candidates: [],
      };
    }
    const candidates = this.catalog
      .filter((product) =>
        product.sponsored &&
        eligible(product, plan) &&
        !(context.excludedProductIds ?? []).includes(product.productId)
      )
      .map((product) => {
        const relevance = 0.65 * categoryFit(product, plan) + 0.35 * termFit(product, plan);
        const quality = product.adQuality ?? 0;
        const bid = Math.min(1, (product.adBid ?? 0) / 3);
        const fatigue = (context.adExposureProductIds ?? []).includes(product.productId) ? 0.2 : 0;
        return { product, relevance, quality, bid, fatigue };
      })
      .filter(({ relevance, quality }) => relevance >= 0.45 && quality >= 0.5)
      .map(({ product, relevance, quality, bid, fatigue }) =>
        envelope(product, "ads", 0.7 * relevance + 0.2 * quality + 0.1 * bid - fatigue, [
          `ad_relevance=${relevance.toFixed(2)}`,
          ...(fatigue > 0 ? ["ad_frequency_penalty"] : []),
          "sponsored_disclosure_required",
        ]),
      );
    return {
      channel: "ads",
      ...(this.catalog[0] ? { dataSource: this.catalog[0].dataSource } : {}),
      candidates: top(candidates, plan.candidateBudget.ads),
    };
  }
}
