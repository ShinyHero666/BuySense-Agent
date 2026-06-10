import { SearchAdsRecsLeadAgent } from "./orchestrator.js";
import { PythonDiscoveryAdapter } from "./python-adapter.js";
import { runtimeFromEnvironment } from "./runtime-from-env.js";

const message =
  process.argv.slice(2).join(" ") ||
  "总预算7000元，重视拍照和续航，帮我选一台手机并搭配降噪耳机和充电器";

const discoveryMode = process.env.MOYUAN_DISCOVERY_MODE ?? "memory";
if (!["memory", "python"].includes(discoveryMode)) {
  throw new Error("MOYUAN_DISCOVERY_MODE must be memory or python");
}
const channels = discoveryMode === "python"
  ? new PythonDiscoveryAdapter(
      process.env.MOYUAN_DISCOVERY_BASE_URL ?? "http://127.0.0.1:18083",
    )
  : undefined;
const runtime = runtimeFromEnvironment();
const reply = await new SearchAdsRecsLeadAgent({
  runtime,
  ...(channels ? { channels } : {}),
}).handle(message);
console.log(reply.message);
console.log(`\nRuntime: ${reply.runtime.mode}:${reply.runtime.model}; discovery=${discoveryMode}`);
console.log(
  `Model calls: ${reply.runtime.modelCalls}; corrected=${reply.runtime.proposalCorrected}; fallback=${reply.runtime.fallbackCount}; tokens=${reply.runtime.totalTokens}`,
);
console.log("\nRetrieval plan:");
console.log(JSON.stringify(reply.plan, null, 2));
console.log("\nAgent collaboration:");
console.log(
  JSON.stringify(
    reply.trace.filter((record) =>
      ["delegated", "model_execution", "artifact_published", "run_completed"].includes(record.event),
    ),
    null,
    2,
  ),
);
