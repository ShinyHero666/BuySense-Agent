import assert from "node:assert/strict";
import test from "node:test";
import { InMemoryDiscoveryChannels } from "../src/channels.js";
import type {
  AgentRole,
  ChannelResult,
  DiscoveryChannels,
  DiscoveryContext,
  RetrievalPlan,
} from "../src/contracts.js";
import { InMemoryDecisionEvidenceGateway } from "../src/evidence.js";
import { SearchAdsRecsLeadAgent } from "../src/orchestrator.js";

test("runs Pi-backed search, recommendation, ads and critic agents", async () => {
  const reply = await new SearchAdsRecsLeadAgent().handle(
    "总预算7000元，重视拍照和续航，帮我选一台手机并搭配降噪耳机和充电器",
  );

  assert.equal(reply.plan.intent, "bundle");
  assert.deepEqual(reply.plan.channels, ["search", "recommendation", "ads"]);
  assert.equal(reply.critique.verdict, "approved");
  assert.equal(reply.bundle.items.length, 3);
  assert.ok(reply.bundle.totalPrice <= 7000);
  assert.ok(reply.bundle.compatibility.every((result) => result.status === "compatible"));
  assert.ok(reply.bundle.compatibility.every((result) => result.paths.length > 0));
  assert.ok(reply.priceQuote.quotes.every((quote) => quote.status === "active"));
  assert.equal(reply.reviewEvidence.products.length, 3);

  const roles = new Set(reply.trace.map((record) => record.role));
  const expectedRoles: AgentRole[] = [
    "lead",
    "intent_router",
    "search",
    "recommendation",
    "ads",
    "compatibility",
    "pricing",
    "review_evidence",
    "critic",
  ];
  for (const role of expectedRoles) {
    assert.ok(roles.has(role), `missing role ${role}`);
  }
  assert.ok(reply.trace.some((record) => record.event === "tool_execution_end"));
  assert.ok(reply.trace.some((record) => record.event === "handoff_fallback_scheduled"));
  assert.ok(reply.artifactIds.length >= 10);
  const modelRoles = new Set(
    reply.trace
      .filter((record) => record.event === "model_execution")
      .map((record) => record.role),
  );
  assert.deepEqual(
    [...modelRoles].sort(),
    ["ads", "critic", "intent_router", "lead", "recommendation", "search"].sort(),
  );
  assert.ok(!modelRoles.has("pricing"));
  assert.ok(!modelRoles.has("compatibility"));
  assert.ok(
    reply.trace.some(
      (record) => record.event === "run_completed" &&
        Number(record.detail.collaborationTasks) <= 18 &&
        Number(record.detail.modelCalls) <= 6,
    ),
  );
});

test("keeps sponsored candidates disclosed and capped in top three", async () => {
  const reply = await new SearchAdsRecsLeadAgent().handle(
    "预算6500元，帮我选拍照手机并搭配耳机和充电器",
  );
  const sponsored = reply.slate.filter((candidate) => candidate.sponsored);
  assert.ok(sponsored.every((candidate) => candidate.disclosure === "赞助"));
  assert.ok(reply.slate.slice(0, 3).filter((candidate) => candidate.sponsored).length <= 1);
  assert.ok(!reply.slate.some((candidate) => candidate.product.productId === "charger-irrelevant-camera"));
});

test("does not call the ads channel when the user opts out", async () => {
  const reply = await new SearchAdsRecsLeadAgent().handle(
    "预算6500元，不要广告，帮我选手机并搭配耳机和充电器",
  );
  assert.equal(reply.plan.sponsoredAllowed, false);
  assert.deepEqual(reply.plan.channels, ["search", "recommendation"]);
  assert.equal(reply.slate.some((candidate) => candidate.sponsored), false);
  assert.equal(reply.trace.some((record) => record.role === "ads"), false);
});

class BrokenAdsChannel extends InMemoryDiscoveryChannels {
  override async ads(): Promise<ChannelResult> {
    throw new Error("simulated ads outage");
  }
}

test("ads outage degrades independently without taking down organic search", async () => {
  const reply = await new SearchAdsRecsLeadAgent({
    channels: new BrokenAdsChannel(),
  }).handle("预算5000元，帮我推荐一台拍照手机");
  assert.equal(reply.critique.verdict, "approved");
  assert.ok(reply.slate.some((candidate) => !candidate.sponsored));
  assert.ok(
    reply.trace.some(
      (record) => record.role === "ads" && record.event === "degraded" &&
        record.detail.tier === "ads_skipped",
    ),
  );
});

test("critic fails closed when the budget cannot produce a complete bundle", async () => {
  const reply = await new SearchAdsRecsLeadAgent().handle(
    "总预算3000元，帮我选一台手机并搭配耳机和充电器",
  );
  assert.equal(reply.critique.verdict, "vetoed");
  assert.match(reply.message, /没有生成可确认草案/);
});

class FirstPassIncompatibleRecommendation implements DiscoveryChannels {
  readonly base = new InMemoryDiscoveryChannels();
  recommendationCalls = 0;

  search(plan: RetrievalPlan): Promise<ChannelResult> {
    return this.base.search(plan);
  }

  ads(plan: RetrievalPlan): Promise<ChannelResult> {
    return this.base.ads(plan);
  }

  async recommend(
    plan: RetrievalPlan,
    context: DiscoveryContext = {},
  ): Promise<ChannelResult> {
    this.recommendationCalls += 1;
    const result = await this.base.recommend(plan, context);
    if (this.recommendationCalls > 1) return result;
    return {
      ...result,
      candidates: result.candidates.map((candidate) =>
        candidate.product.category === "charger"
          ? {
              ...candidate,
              product: {
                ...candidate.product,
                connectors: ["proprietary"],
                protocols: ["camera-battery"],
              },
            }
          : candidate,
      ),
    };
  }
}

test("critic delegates one bounded revision without relaxing hard constraints", async () => {
  const channels = new FirstPassIncompatibleRecommendation();
  const reply = await new SearchAdsRecsLeadAgent({ channels }).handle(
    "总预算7000元，帮我选一台手机并搭配降噪耳机和充电器",
  );
  assert.equal(channels.recommendationCalls, 2);
  assert.equal(reply.critique.verdict, "approved");
  assert.equal(reply.plan.requirements.budgetMax, 7000);
  assert.ok(reply.bundle.totalPrice <= 7000);
  assert.ok(
    reply.trace.some(
      (record) => record.role === "critic" && record.event === "peer_delegated",
    ),
  );
  assert.ok(reply.artifactIds.length > 7);
});

class MissingReviewEvidenceGateway extends InMemoryDecisionEvidenceGateway {
  override async reviewAspects(productIds: string[]) {
    const evidence = await super.reviewAspects(productIds);
    const missing = productIds[0];
    if (!missing) return evidence;
    return {
      ...evidence,
      products: evidence.products.filter((item) => item.productId !== missing),
      missingProductIds: [missing],
    };
  }
}

test("critic fails closed when selected products lack review evidence", async () => {
  const reply = await new SearchAdsRecsLeadAgent({
    evidence: new MissingReviewEvidenceGateway(),
  }).handle("总预算7000元，帮我选一台手机并搭配耳机和充电器");
  assert.equal(reply.critique.verdict, "vetoed");
  assert.ok(reply.critique.violations.includes("review_evidence_coverage"));
  assert.match(reply.message, /没有生成可确认草案/);
});

class UnavailableQuoteGateway extends InMemoryDecisionEvidenceGateway {
  override async quote(items: Parameters<InMemoryDecisionEvidenceGateway["quote"]>[0]) {
    const batch = await super.quote(items);
    const first = batch.quotes[0];
    if (!first) return batch;
    return {
      ...batch,
      quotes: [
        {
          ...first,
          status: "unavailable" as const,
          amount: null,
          stock: 0,
          reason: "offer_expired_during_checkout",
        },
        ...batch.quotes.slice(1),
      ],
    };
  }
}

test("critic fails closed when a live offer quote becomes unavailable", async () => {
  const reply = await new SearchAdsRecsLeadAgent({
    evidence: new UnavailableQuoteGateway(),
  }).handle("总预算7000元，帮我选一台手机并搭配耳机和充电器");
  assert.equal(reply.critique.verdict, "vetoed");
  assert.ok(reply.critique.violations.includes("live_quote_coverage"));
  assert.ok(reply.critique.violations.includes("all_items_in_stock"));
});
