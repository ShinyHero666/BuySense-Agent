import assert from "node:assert/strict";
import { SearchAdsRecsBuyerAgent } from "./buyer-agent.js";
import { PythonDiscoveryAdapter } from "./python-adapter.js";
import { runtimeFromEnvironment } from "./runtime-from-env.js";

const runtime = runtimeFromEnvironment();
assert.equal(runtime.mode, "modelport", "set MOYUAN_AGENT_MODEL_MODE=modelport");
const dataPlane = new PythonDiscoveryAdapter(
  process.env.MOYUAN_DISCOVERY_BASE_URL ?? "http://127.0.0.1:18083",
);
const agent = new SearchAdsRecsBuyerAgent({
  runtime,
  channels: dataPlane,
  evidence: dataPlane,
});
const sessionId = `modelport-e2e-${Date.now()}`;
const proposal = await agent.handle({
  sessionId,
  userId: "modelport-e2e-user",
  message: "总预算7000元，重视拍照和续航，帮我选手机并搭配降噪耳机和充电器",
});
assert.equal(proposal.phase, "proposal");
assert.ok(proposal.decision);
assert.equal(proposal.decision.runtime.mode, "modelport");
assert.equal(proposal.decision.runtime.modelCalls, 6);
assert.equal(proposal.decision.runtime.fallbackCount, 0);
assert.equal(proposal.decision.critique.verdict, "approved");
assert.equal(proposal.decision.plan.intent, "bundle");
assert.equal(proposal.decision.bundle.items.length, 3);
assert.deepEqual(
  new Set(proposal.decision.bundle.items.map((item) => item.product.category)),
  new Set(["phone", "headphones", "charger"]),
);
assert.ok(proposal.decision.bundle.withinBudget);

const confirmation = await agent.handle({
  sessionId,
  userId: "modelport-e2e-user",
  message: "确认生成购物车草案",
  confirmed: true,
});
assert.equal(confirmation.phase, "cart_draft");
assert.ok(confirmation.cartDraft);
assert.equal(confirmation.cartDraft.paymentAuthorized, false);
const confirmationExecutions = confirmation.confirmationTrace.filter(
  (record) => record.event === "model_execution",
);
assert.equal(confirmationExecutions.length, 0);

console.log(JSON.stringify({
  passed: true,
  model: proposal.decision.runtime.model,
  proposal: {
    modelCalls: proposal.decision.runtime.modelCalls,
    accepted: proposal.decision.runtime.proposalAccepted,
    corrected: proposal.decision.runtime.proposalCorrected,
    fallbacks: proposal.decision.runtime.fallbackCount,
    totalTokens: proposal.decision.runtime.totalTokens,
    itemCount: proposal.decision.bundle.items.length,
    totalPrice: proposal.decision.bundle.totalPrice,
    verdict: proposal.decision.critique.verdict,
  },
  confirmation: {
    modelCalls: confirmationExecutions.length,
    deterministicPolicyGate: true,
    draftId: confirmation.cartDraft.draftId,
    paymentAuthorized: confirmation.cartDraft.paymentAuthorized,
  },
}, null, 2));
