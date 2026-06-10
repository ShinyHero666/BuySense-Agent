import assert from "node:assert/strict";
import test from "node:test";
import { InMemoryDiscoveryChannels } from "../src/channels.js";
import {
  resolveChannelRanking,
  resolveFinalDecision,
  resolveRetrievalPlan,
} from "../src/model-policy.js";
import { buildRetrievalPlan } from "../src/router.js";

test("model plan cannot invent brand preferences or override sponsored policy", () => {
  const result = resolveRetrievalPlan(
    "预算7000元，帮我选手机并搭配耳机和充电器",
    {
      intent: "bundle",
      query: "预算内手机套装",
      requestedCategories: ["phone", "headphones", "charger"],
      preferredBrands: ["Imaginary", "Apple"],
      useCases: ["拍照"],
      channels: ["search", "recommendation"],
      sponsoredAllowed: false,
      candidateBudget: { search: 100, recommendation: 100, ads: 100 },
      reason: "model proposal",
    },
  );
  assert.deepEqual(result.payload.requirements.preferredBrands, []);
  assert.equal(result.payload.sponsoredAllowed, true);
  assert.deepEqual(result.payload.channels, ["search", "recommendation"]);
  assert.equal(result.payload.candidateBudget.search, 12);
  assert.equal(result.payload.candidateBudget.ads, 4);
  assert.equal(result.outcome, "corrected");
});

test("model may choose optional retrieval channels but a bundle keeps recommendation", () => {
  const precise = resolveRetrievalPlan("预算7000元，比较几款手机", {
    intent: "compare",
    query: "预算内手机比较",
    requestedCategories: ["phone"],
    preferredBrands: [],
    useCases: [],
    channels: ["search"],
    sponsoredAllowed: true,
    candidateBudget: { search: 8, recommendation: 8, ads: 4 },
    reason: "search is sufficient",
  });
  assert.deepEqual(precise.payload.channels, ["search"]);

  const bundle = resolveRetrievalPlan("预算7000元，手机搭配耳机", {
    intent: "bundle",
    query: "手机耳机套装",
    requestedCategories: ["phone", "headphones"],
    preferredBrands: [],
    useCases: [],
    channels: ["search"],
    sponsoredAllowed: true,
    candidateBudget: { search: 8, recommendation: 8, ads: 4 },
    reason: "search only",
  });
  assert.deepEqual(bundle.payload.channels, ["search", "recommendation"]);
  assert.ok(bundle.corrections?.includes("bundle_recommendation_channel_restored"));
});

test("an enabled ads channel always has a positive candidate budget", () => {
  const result = resolveRetrievalPlan("预算7000元，帮我选手机", {
    intent: "exploratory",
    query: "预算内手机",
    requestedCategories: ["phone"],
    preferredBrands: [],
    useCases: [],
    channels: ["search", "recommendation", "ads"],
    sponsoredAllowed: true,
    candidateBudget: { search: 8, recommendation: 8, ads: 0 },
    reason: "route",
  });
  assert.equal(result.payload.candidateBudget.ads, 1);
  assert.ok(result.corrections?.includes("ads_candidate_budget_clamped"));
});

test("model cannot shrink an explicit bundle intent or requested categories", () => {
  const result = resolveRetrievalPlan(
    "预算7000元，帮我选手机并搭配耳机和充电器",
    {
      intent: "precise",
      query: "只选手机",
      requestedCategories: ["phone"],
      preferredBrands: [],
      useCases: [],
      channels: ["search", "recommendation", "ads"],
      sponsoredAllowed: true,
      candidateBudget: { search: 8, recommendation: 8, ads: 4 },
      reason: "shrink scope",
    },
  );
  assert.equal(result.payload.intent, "bundle");
  assert.deepEqual(
    result.payload.requirements.requestedCategories,
    ["phone", "headphones", "charger"],
  );
  assert.ok(result.corrections?.includes("explicit_intent_preserved"));
  assert.ok(result.corrections?.includes("explicit_category_scope_preserved"));
});

test("model rewrite cannot erase explicit use cases or the original query", () => {
  const result = resolveRetrievalPlan("预算6000元，重视拍照和续航，帮我推荐手机", {
    intent: "exploratory",
    query: "普通手机",
    requestedCategories: ["phone"],
    preferredBrands: [],
    useCases: [],
    channels: ["search", "recommendation", "ads"],
    sponsoredAllowed: true,
    candidateBudget: { search: 8, recommendation: 8, ads: 4 },
    reason: "rewrite",
  });
  assert.equal(result.payload.originalQuery, "预算6000元,重视拍照和续航,帮我推荐手机");
  assert.deepEqual(result.payload.requirements.useCases, ["拍照", "续航"]);
  assert.ok(result.corrections?.includes("explicit_use_case_preserved"));
  assert.ok(
    result.payload.requirements.constraints.some(
      (constraint) => constraint.field === "useCases" && constraint.source === "explicit_user",
    ),
  );
});

test("model ranking can reorder grounded candidates but cannot add SKU ids", async () => {
  const plan = buildRetrievalPlan("预算7000元，帮我选手机");
  const baseline = await new InMemoryDiscoveryChannels().search(plan);
  const target = baseline.candidates.at(-1);
  assert.ok(target);
  const result = resolveChannelRanking(baseline, {
    rankedSkuIds: [target.product.skuId, "sku-does-not-exist"],
    rationaleBySku: { [target.product.skuId]: "更贴近拍照需求" },
  });
  assert.equal(result.payload.candidates[0]?.product.skuId, target.product.skuId);
  assert.ok(result.corrections?.includes("unknown_sku_removed"));
  assert.ok(result.payload.candidates[0]?.reasons.some((reason) => reason.startsWith("model_rank:")));
});

test("final answer falls back when the model invents a price", () => {
  const result = resolveFinalDecision(
    "确定性安全回复",
    true,
    [3999, 4697, 7000],
    { message: "推荐该商品，售价 9999元，已经下单。", approved: true },
  );
  assert.equal(result.payload.message, "确定性安全回复");
  assert.equal(result.outcome, "corrected");
});
