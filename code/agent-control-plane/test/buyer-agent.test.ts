import assert from "node:assert/strict";
import test from "node:test";
import { SearchAdsRecsBuyerAgent } from "../src/buyer-agent.js";
import type { CandidateEnvelope } from "../src/contracts.js";
import { InMemoryDecisionEvidenceGateway } from "../src/evidence.js";
import { loadCatalogForDomainPack, NORMAL_3C_DOMAIN } from "../src/domain-pack.js";

const FIXED_NOW = Date.parse("2026-08-01T10:00:00Z");

test("creates a cart draft only after explicit confirmation and quote refresh", async () => {
  const agent = new SearchAdsRecsBuyerAgent({ now: () => FIXED_NOW });
  const proposal = await agent.handle({
    sessionId: "session-cart-1",
    userId: "user-1",
    message: "总预算7000元，帮我选手机并搭配降噪耳机和充电器",
  });
  assert.equal(proposal.phase, "proposal");
  assert.equal(proposal.cartDraft, null);
  assert.match(proposal.message, /不会发起支付/);

  const confirmation = await agent.handle({
    sessionId: "session-cart-1",
    userId: "user-1",
    message: "确认",
    confirmed: true,
  });
  assert.equal(confirmation.phase, "cart_draft");
  assert.ok(confirmation.cartDraft);
  assert.equal(confirmation.cartDraft.paymentAuthorized, false);
  assert.equal(confirmation.cartDraft.items.length, 3);
  assert.equal(agent.drafts.get(confirmation.cartDraft.draftId)?.status, "ready");
  assert.ok(confirmation.confirmationTrace.some((record) => record.role === "pricing"));
  assert.ok(confirmation.confirmationTrace.some((record) => record.role === "cart"));

  const metrics = agent.metrics.snapshot();
  assert.equal(metrics.northStar.value, 1);
  assert.equal(metrics.counters.valid_cart_drafts, 1);
});

test("confirmation without a matching pending session fails closed", async () => {
  const agent = new SearchAdsRecsBuyerAgent({ now: () => FIXED_NOW });
  const reply = await agent.handle({
    sessionId: "missing-session",
    userId: "user-1",
    message: "确认",
    confirmed: true,
  });
  assert.equal(reply.phase, "no_pending_decision");
  assert.equal(reply.cartDraft, null);
  assert.equal(agent.metrics.snapshot().counters.no_pending_confirmations, 1);
});

test("qualified proposal, confirmation conversion and cart completion stay separate", async () => {
  const agent = new SearchAdsRecsBuyerAgent({ now: () => FIXED_NOW });
  const proposal = await agent.handle({
    sessionId: "metrics-separated",
    userId: "user-1",
    message: "预算6000元，帮我选拍照手机",
  });
  assert.equal(proposal.phase, "proposal");
  const beforeConfirmation = agent.metrics.snapshot();
  assert.equal(beforeConfirmation.northStar.value, 1);
  assert.equal(beforeConfirmation.northStar.numerator, 1);
  assert.equal(beforeConfirmation.northStar.denominator, 1);
  assert.equal(beforeConfirmation.layers.agentReliability.confirmationConversionRate, 0);
  assert.equal(beforeConfirmation.layers.businessOutcome.validCartDraftCompletionRate, 0);
  assert.equal(beforeConfirmation.layers.businessOutcome.endToEndQualifiedDraftRate, 0);

  await agent.handle({
    sessionId: "metrics-separated",
    userId: "user-1",
    message: "确认",
    confirmed: true,
  });
  const afterConfirmation = agent.metrics.snapshot();
  assert.equal(afterConfirmation.layers.agentReliability.confirmationConversionRate, 1);
  assert.equal(afterConfirmation.layers.businessOutcome.validCartDraftCompletionRate, 1);
  assert.equal(afterConfirmation.layers.businessOutcome.endToEndQualifiedDraftRate, 1);
});

test("single-category requests do not silently add unrelated products", async () => {
  const agent = new SearchAdsRecsBuyerAgent({ now: () => FIXED_NOW });
  const headphones = await agent.handle({
    sessionId: "headphones-only",
    userId: "user-1",
    message: "预算1000元，帮我选降噪耳机",
  });
  assert.equal(headphones.phase, "proposal");
  assert.deepEqual(
    headphones.decision?.bundle.items.map((item) => item.product.category),
    ["headphones"],
  );

  const partialBundle = await agent.handle({
    sessionId: "phone-charger",
    userId: "user-1",
    message: "预算5000元，帮我选手机搭配充电器",
  });
  assert.deepEqual(
    new Set(partialBundle.decision?.bundle.items.map((item) => item.product.category)),
    new Set(["phone", "charger"]),
  );
});

class QuoteExpiresOnConfirmation extends InMemoryDecisionEvidenceGateway {
  calls = 0;

  override async quote(items: CandidateEnvelope[]) {
    this.calls += 1;
    const batch = await super.quote(items);
    if (this.calls === 1) return batch;
    return {
      ...batch,
      quotes: batch.quotes.map((quote, index) =>
        index === 0
          ? {
              ...quote,
              status: "unavailable" as const,
              amount: null,
              stock: 0,
              reason: "offer_expired_during_confirmation",
            }
          : quote,
      ),
    };
  }
}

test("quote loss between proposal and confirmation rejects the cart draft", async () => {
  const evidence = new QuoteExpiresOnConfirmation(loadCatalogForDomainPack(NORMAL_3C_DOMAIN));
  const agent = new SearchAdsRecsBuyerAgent({ evidence, now: () => FIXED_NOW });
  const proposal = await agent.handle({
    sessionId: "quote-loss",
    userId: "user-1",
    message: "总预算7000元，帮我选手机并搭配耳机和充电器",
  });
  assert.equal(proposal.phase, "proposal");
  const confirmation = await agent.handle({
    sessionId: "quote-loss",
    userId: "user-1",
    message: "确认",
    confirmed: true,
  });
  assert.equal(confirmation.phase, "needs_replan");
  assert.equal(confirmation.cartDraft, null);
  assert.match(confirmation.message, /offer_unavailable|invalid_quote/);
});
