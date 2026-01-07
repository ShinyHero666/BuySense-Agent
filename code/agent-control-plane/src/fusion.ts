import type {
  BundleProposal,
  CandidateEnvelope,
  ChannelResult,
  Critique,
  DecisionEvidenceGateway,
  PriceQuoteBatch,
  ProductCategory,
  RetrievalPlan,
  ReviewEvidenceBatch,
} from "./contracts.js";
import { NORMAL_3C_DOMAIN, type CommerceDomainPack } from "./domain-pack.js";

export function fuseSlate(results: ChannelResult[], limit = 8): CandidateEnvelope[] {
  const weights = { search: 1, recommendation: 0.9, ads: 0.55 } as const;
  const merged = new Map<string, { candidate: CandidateEnvelope; rrf: number }>();
  for (const result of results.filter((item) => item.channel !== "ads")) {
    result.candidates.forEach((candidate, index) => {
      const contribution = weights[result.channel] / (60 + index + 1);
      const existing = merged.get(candidate.product.skuId);
      if (!existing) {
        merged.set(candidate.product.skuId, {
          candidate: { ...candidate, reasons: [...candidate.reasons] },
          rrf: contribution,
        });
        return;
      }
      existing.rrf += contribution;
      existing.candidate.sources = [...new Set([
        ...existing.candidate.sources,
        ...candidate.sources,
      ])];
      existing.candidate.reasons = [...new Set([
        ...existing.candidate.reasons,
        ...candidate.reasons,
      ])];
      if (candidate.channelScore > existing.candidate.channelScore) {
        existing.candidate.channelScore = candidate.channelScore;
        existing.candidate.channel = candidate.channel;
      }
    });
  }
  const fused = [...merged.values()].sort((left, right) =>
    right.rrf - left.rrf || left.candidate.product.skuId.localeCompare(right.candidate.product.skuId)
  );
  const minimum = Math.min(...fused.map((item) => item.rrf), 0);
  const maximum = Math.max(...fused.map((item) => item.rrf), 0);
  const natural = fused.map(({ candidate, rrf }) => ({
    ...candidate,
    normalizedScore: maximum === minimum ? 1 : (rrf - minimum) / (maximum - minimum),
    reasons: [...new Set([...candidate.reasons, `weighted_rrf=${rrf.toFixed(6)}`])],
  }));
  const ads = results
    .filter((result) => result.channel === "ads")
    .flatMap((result) => result.candidates)
    .sort((left, right) => right.normalizedScore - left.normalizedScore);

  const slate = natural.slice(0, limit);
  const bestNaturalByCategory = new Map<ProductCategory, number>();
  natural.forEach((candidate) => bestNaturalByCategory.set(
    candidate.product.category,
    Math.max(bestNaturalByCategory.get(candidate.product.category) ?? 0, candidate.channelScore),
  ));
  const bestAd = ads.find((candidate) => {
    if (slate.some((naturalCandidate) => naturalCandidate.product.skuId === candidate.product.skuId)) {
      return false;
    }
    const organicFloor = 0.85 * (bestNaturalByCategory.get(candidate.product.category) ?? 0);
    return (candidate.product.adQuality ?? 0) >= 0.5 && candidate.channelScore >= organicFloor;
  });
  if (bestAd && limit > 1) {
    slate.splice(1, 0, {
      ...bestAd,
      reasons: [...new Set([...bestAd.reasons, "organic_quality_floor_passed"])],
    });
  }
  return slate.slice(0, limit);
}

export async function proposeBundle(
  slate: CandidateEnvelope[],
  plan: RetrievalPlan,
  evidence: DecisionEvidenceGateway,
  preferredSkuIds: string[] = [],
  domain: CommerceDomainPack = NORMAL_3C_DOMAIN,
  signal?: AbortSignal,
): Promise<BundleProposal> {
  signal?.throwIfAborted();
  const preferredRank = new Map(preferredSkuIds.map((skuId, index) => [skuId, index]));
  const orderedSlate = [...slate].sort((left, right) => {
    const leftRank = preferredRank.get(left.product.skuId) ?? Number.MAX_SAFE_INTEGER;
    const rightRank = preferredRank.get(right.product.skuId) ?? Number.MAX_SAFE_INTEGER;
    return leftRank === rightRank ? 0 : leftRank - rightRank;
  });
  const primaryCategory = plan.requirements.requestedCategories.includes(
    domain.primaryCategory,
  )
    ? domain.primaryCategory
    : (plan.requirements.requestedCategories[0] ?? domain.defaultCategory);
  const requestedAccessories = primaryCategory === domain.primaryCategory &&
    plan.intent === "bundle"
    ? plan.requirements.requestedCategories.filter((category) => category !== primaryCategory)
    : [];
  const requiredCategories = [primaryCategory, ...requestedAccessories];
  const candidatesByCategory = new Map<ProductCategory, CandidateEnvelope[]>();
  for (const category of requiredCategories) {
    const candidates = orderedSlate.filter((candidate) => candidate.product.category === category);
    const naturalBest = Math.max(
      ...candidates.filter((candidate) => !candidate.sponsored).map((candidate) => candidate.normalizedScore),
      0,
    );
    candidatesByCategory.set(category, candidates.filter((candidate) =>
      !candidate.sponsored || candidate.normalizedScore >= 0.85 * naturalBest
    ).slice(0, 8));
  }
  const primaryCandidates = candidatesByCategory.get(primaryCategory) ?? [];
  if (primaryCandidates.length === 0) {
    return {
      items: [],
      totalPrice: 0,
      budgetMax: plan.requirements.budgetMax,
      withinBudget: false,
      compatibility: [],
      score: 0,
      optimization: "global_enumeration",
      alternatives: [],
    };
  }

  const rankedBundles: Array<{
    items: CandidateEnvelope[];
    compatibility: BundleProposal["compatibility"];
    totalPrice: number;
    score: number;
  }> = [];
  for (const primary of primaryCandidates) {
    signal?.throwIfAborted();
    const accessoryCandidates = requestedAccessories.flatMap(
      (category) => candidatesByCategory.get(category) ?? [],
    );
    const results = await evidence.checkCompatibility(primary, accessoryCandidates, signal);
    const compatibleByProduct = new Map(results.map((result) => [result.accessoryId, result]));
    const choose = (
      categoryIndex: number,
      selected: CandidateEnvelope[],
      compatibility: BundleProposal["compatibility"],
    ): void => {
      if (categoryIndex >= requestedAccessories.length) {
        const totalPrice = selected.reduce((sum, item) => sum + item.product.price, 0);
        if (plan.requirements.budgetMax !== null && totalPrice > plan.requirements.budgetMax) return;
        const relevance = selected.reduce((sum, item) => sum + item.normalizedScore, 0);
        const sponsoredPenalty = 0.04 * selected.filter((item) => item.sponsored).length;
        const preferredBonus = selected.reduce((sum, item) =>
          sum + (preferredRank.has(item.product.skuId) ? 0.02 : 0), 0);
        const headroom = plan.requirements.budgetMax
          ? 0.08 * (1 - totalPrice / plan.requirements.budgetMax)
          : 0;
        rankedBundles.push({
          items: [...selected],
          compatibility: [...compatibility],
          totalPrice,
          score: relevance + preferredBonus + headroom - sponsoredPenalty,
        });
        return;
      }
      const category = requestedAccessories[categoryIndex];
      if (!category) return;
      for (const candidate of candidatesByCategory.get(category) ?? []) {
        const result = compatibleByProduct.get(candidate.product.productId);
        if (!result || result.status !== "compatible") continue;
        choose(categoryIndex + 1, [...selected, candidate], [...compatibility, result]);
      }
    };
    choose(0, [primary], []);
  }
  rankedBundles.sort((left, right) =>
    right.score - left.score || left.totalPrice - right.totalPrice
  );
  const winner = rankedBundles[0] ?? {
    items: [primaryCandidates[0]!],
    compatibility: [],
    totalPrice: primaryCandidates[0]!.product.price,
    score: primaryCandidates[0]!.normalizedScore,
  };
  return {
    items: winner.items,
    totalPrice: winner.totalPrice,
    budgetMax: plan.requirements.budgetMax,
    withinBudget: plan.requirements.budgetMax === null || winner.totalPrice <= plan.requirements.budgetMax,
    compatibility: winner.compatibility,
    score: winner.score,
    optimization: "global_enumeration",
    alternatives: rankedBundles.slice(0, 3).map((bundle) => ({
      skuIds: bundle.items.map((item) => item.product.skuId),
      totalPrice: bundle.totalPrice,
      score: bundle.score,
      sponsoredCount: bundle.items.filter((item) => item.sponsored).length,
    })),
  };
}

export function applyPriceQuotes(
  bundle: BundleProposal,
  batch: PriceQuoteBatch,
): BundleProposal {
  const quotes = new Map(batch.quotes.map((quote) => [quote.offerId, quote]));
  const items = bundle.items.map((candidate) => {
    const quote = quotes.get(candidate.product.offerId);
    if (!quote || quote.status !== "active" || quote.amount === null) {
      return {
        ...candidate,
        product: { ...candidate.product, stock: 0 },
      };
    }
    return {
      ...candidate,
      product: {
        ...candidate.product,
        price: quote.amount,
        currency: quote.currency,
        stock: quote.stock,
        quoteVersion: batch.quoteVersion,
        quoteValidUntil: quote.validUntil,
      },
    };
  });
  const totalPrice = items.reduce((sum, item) => sum + item.product.price, 0);
  return {
    ...bundle,
    items,
    totalPrice,
    withinBudget: bundle.budgetMax === null || totalPrice <= bundle.budgetMax,
  };
}

export function auditProposal(
  plan: RetrievalPlan,
  slate: CandidateEnvelope[],
  bundle: BundleProposal,
  priceQuote: PriceQuoteBatch,
  reviewEvidence: ReviewEvidenceBatch,
  domain: CommerceDomainPack = NORMAL_3C_DOMAIN,
): Critique {
  const topThreeAds = slate.slice(0, 3).filter((candidate) => candidate.sponsored).length;
  const bundleCategories = new Set(bundle.items.map((item) => item.product.category));
  const primaryCategory = plan.requirements.requestedCategories.includes(
    domain.primaryCategory,
  )
    ? domain.primaryCategory
    : (plan.requirements.requestedCategories[0] ?? domain.defaultCategory);
  const requiredBundleCategories: ProductCategory[] = plan.intent === "bundle"
    ? plan.requirements.requestedCategories.filter((category) =>
        domain.defaultBundleCategories.includes(category),
      )
    : [primaryCategory];
  const quoteVersions = new Set(bundle.items.map((item) => item.product.quoteVersion));
  const currencies = new Set(bundle.items.map((item) => item.product.currency));
  const quotesByOffer = new Map(priceQuote.quotes.map((quote) => [quote.offerId, quote]));
  const reviewedProducts = new Set(reviewEvidence.products.map((item) => item.productId));
  const checks = {
    has_primary_product: bundle.items.some(
      (item) => item.product.category === primaryCategory,
    ),
    requested_category_coverage: requiredBundleCategories.every((category) =>
      bundleCategories.has(category),
    ),
    all_items_in_stock: bundle.items.every((item) => item.product.stock > 0),
    budget_respected: bundle.withinBudget,
    offer_quote_grounded: bundle.items.every(
      (item) =>
        item.product.offerId.length > 0 &&
        item.product.quoteVersion.length > 0 &&
        item.product.quoteValidUntil.length > 0 &&
        item.product.price >= 0,
    ),
    catalog_provenance_versioned: bundle.items.every(
      (item) =>
        ["local_snapshot", "remote_provider"].includes(item.product.dataSource.source) &&
        item.product.dataSource.sourceVersion === item.product.catalogVersion &&
        item.product.dataSource.providerId.length > 0,
    ),
    quote_version_consistent: quoteVersions.size <= 1,
    single_supported_currency: currencies.size <= 1 &&
      [...currencies].every((currency) => currency === "CNY"),
    compatibility_verified: bundle.compatibility.every((item) => item.status === "compatible"),
    compatibility_graph_grounded: bundle.compatibility.every(
      (item) => item.ruleVersion.length > 0 && item.paths.length > 0,
    ),
    live_quote_coverage: bundle.items.every((item) => {
      const quote = quotesByOffer.get(item.product.offerId);
      return quote?.status === "active" && quote.amount === item.product.price;
    }),
    live_quote_version_applied: bundle.items.every(
      (item) => item.product.quoteVersion === priceQuote.quoteVersion,
    ),
    price_quote_provenance_versioned:
      ["local_snapshot", "remote_provider"].includes(priceQuote.dataSource.source) &&
      priceQuote.dataSource.sourceVersion === priceQuote.quoteVersion &&
      priceQuote.dataSource.providerId.length > 0,
    review_evidence_coverage: bundle.items.every((item) =>
      reviewedProducts.has(item.product.productId),
    ) && reviewEvidence.missingProductIds.length === 0,
    review_evidence_versioned: reviewEvidence.reviewSnapshotVersion.length > 0 &&
      ["local_snapshot", "remote_provider"].includes(reviewEvidence.dataSource.source) &&
      reviewEvidence.dataSource.sourceVersion === reviewEvidence.reviewSnapshotVersion &&
      reviewEvidence.dataSource.providerId.length > 0 &&
      reviewEvidence.products.every(
        (item) =>
          item.source === reviewEvidence.dataSource.source &&
          item.sourceVersion === reviewEvidence.dataSource.sourceVersion &&
          item.providerId === reviewEvidence.dataSource.providerId &&
          item.aspects.length > 0,
      ),
    sponsored_disclosed: slate
      .filter((candidate) => candidate.sponsored)
      .every((candidate) => candidate.disclosure === "赞助"),
    sponsored_top3_cap: topThreeAds <= 1,
    requested_channels_executed: plan.channels.length > 0,
  };
  const violations = Object.entries(checks)
    .filter(([, passed]) => !passed)
    .map(([name]) => name);
  return {
    verdict: violations.length === 0 ? "approved" : "vetoed",
    violations,
    checks,
  };
}
