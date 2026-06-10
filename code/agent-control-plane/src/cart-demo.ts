import { SearchAdsRecsBuyerAgent } from "./buyer-agent.js";
import { PythonDiscoveryAdapter } from "./python-adapter.js";
import { runtimeFromEnvironment } from "./runtime-from-env.js";

const discoveryMode = process.env.MOYUAN_DISCOVERY_MODE ?? "memory";
if (!["memory", "python"].includes(discoveryMode)) {
  throw new Error("MOYUAN_DISCOVERY_MODE must be memory or python");
}
const dataPlane = discoveryMode === "python"
  ? new PythonDiscoveryAdapter(
      process.env.MOYUAN_DISCOVERY_BASE_URL ?? "http://127.0.0.1:18083",
    )
  : undefined;
const agent = new SearchAdsRecsBuyerAgent({
  runtime: runtimeFromEnvironment(),
  ...(dataPlane ? { channels: dataPlane, evidence: dataPlane } : {}),
});
const sessionId = "cart-demo-session";
const proposal = await agent.handle({
  sessionId,
  userId: "cart-demo-user",
  message: "总预算7000元，重视拍照和续航，帮我选手机并搭配降噪耳机和充电器",
});
console.log(proposal.message);

if (proposal.phase === "proposal") {
  const confirmation = await agent.handle({
    sessionId,
    userId: "cart-demo-user",
    message: "确认生成购物车草案",
    confirmed: true,
  });
  console.log(`\n${confirmation.message}`);
  console.log("\nCart draft:");
  console.log(JSON.stringify(confirmation.cartDraft, null, 2));
}

console.log("\nSix-layer metrics:");
console.log(JSON.stringify(agent.metrics.snapshot(), null, 2));
