import type {
  ProductCategory,
  RequirementState,
  RetrievalChannel,
  RetrievalPlan,
} from "./contracts.js";
import { NORMAL_3C_DOMAIN, type CommerceDomainPack } from "./domain-pack.js";

function unique<T>(values: T[]): T[] {
  return [...new Set(values)];
}

function extractBudget(message: string): number | null {
  const match = message.match(/(?:预算|总价)[^\d]{0,8}(\d{1,9})|(?:不超过|控制在)\s*(\d{1,9})/);
  const raw = match?.[1] ?? match?.[2];
  if (raw === undefined) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

export function buildRetrievalPlan(
  message: string,
  domain: CommerceDomainPack = NORMAL_3C_DOMAIN,
): RetrievalPlan {
  const categoryTerms: Array<[ProductCategory, string[]]> = domain.categories.map(
    (item) => [item.id, item.terms],
  );
  const normalized = message.normalize("NFKC").trim().toLowerCase();
  if (!normalized) {
    throw new Error("message must not be empty");
  }

  const bundleLanguage = ["搭配", "套装", "一套", "配一个"].some((term) =>
    normalized.includes(term),
  );
  const explicitlyRequestedCategories = unique(
    categoryTerms.filter(([, terms]) => terms.some((term) => normalized.includes(term))).map(
      ([category]) => category,
    ),
  );
  const isBundle = bundleLanguage || explicitlyRequestedCategories.length > 1;
  const requestedCategories = [...explicitlyRequestedCategories];
  if (requestedCategories.length === 0) {
    requestedCategories.push(domain.defaultCategory);
    if (isBundle) {
      requestedCategories.splice(0, requestedCategories.length, ...domain.defaultBundleCategories);
    }
  } else if (isBundle && !requestedCategories.includes(domain.primaryCategory)) {
    requestedCategories.unshift(domain.primaryCategory);
  }

  const preferredBrands: string[] = [];
  for (const brand of domain.brands) {
    if (brand.terms.some((term) => normalized.includes(term))) preferredBrands.push(brand.name);
  }

  const budgetMax = extractBudget(normalized);
  const turnId = "turn-current";
  const constraints: RequirementState["constraints"] = [];
  if (budgetMax !== null) {
    constraints.push({
      constraintId: "constraint-budget",
      field: "budgetMax",
      value: budgetMax,
      source: "explicit_user",
      strength: "hard",
      confidence: 1,
      turnId,
      status: "active",
    });
  }
  requestedCategories.forEach((category, index) => constraints.push({
    constraintId: `constraint-category-${index}`,
    field: "requestedCategories",
    value: category,
    source: explicitlyRequestedCategories.includes(category) ? "explicit_user" : "system",
    strength: "hard",
    confidence: explicitlyRequestedCategories.includes(category) ? 1 : 0.7,
    turnId,
    status: "active",
  }));
  preferredBrands.forEach((brand, index) => constraints.push({
    constraintId: `constraint-brand-${index}`,
    field: "preferredBrands",
    value: brand,
    source: "explicit_user",
    strength: "soft",
    confidence: 1,
    turnId,
    status: "active",
  }));
  const useCases = domain.useCases.filter((term) => normalized.includes(term));
  useCases.forEach((useCase, index) => constraints.push({
    constraintId: `constraint-use-case-${index}`,
    field: "useCases",
    value: useCase,
    source: "explicit_user",
    strength: "soft",
    confidence: 1,
    turnId,
    status: "active",
  }));
  const requirements: RequirementState = {
    budgetMax,
    preferredBrands,
    requestedCategories,
    useCases,
    hardFields: budgetMax === null ? [] : ["budgetMax"],
    unknownFields: budgetMax === null ? ["budgetMax"] : [],
    constraints,
  };

  let intent: RetrievalPlan["intent"] = "catalog";
  if (isBundle) intent = "bundle";
  else if (["对比", "比较"].some((term) => normalized.includes(term))) {
    intent = "compare";
  }
  else if (["推荐", "适合", "怎么选", "帮我选"].some((term) => normalized.includes(term))) {
    intent = "exploratory";
  } else if (
    domain.protocolTerms.some((term) => normalized.includes(term.toLowerCase())) ||
    (/[a-z0-9]/u.test(normalized) && domain.brands.some((brand) =>
      brand.terms.some((term) => normalized.includes(term.toLowerCase()))
    ))
  ) {
    intent = "precise";
  }

  const sponsoredAllowed = !["不要广告", "不看广告", "无广告"].some((term) =>
    normalized.includes(term),
  );
  const channels: RetrievalChannel[] = ["search", "recommendation"];
  if (sponsoredAllowed) channels.push("ads");

  return {
    originalQuery: normalized,
    query: normalized,
    intent,
    requirements,
    channels,
    candidateBudget: { search: 8, recommendation: 8, ads: 4 },
    sponsoredAllowed,
    reason: `intent=${intent}; categories=${requestedCategories.join(",")}; channels=${channels.join(",")}`,
  };
}
