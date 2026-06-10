import assert from "node:assert/strict";
import { once } from "node:events";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";
import { SearchAdsRecsBuyerAgent } from "../src/buyer-agent.js";
import { SearchAdsRecsLeadAgent } from "../src/orchestrator.js";
import { PythonDiscoveryAdapter } from "../src/python-adapter.js";

const CODE_ROOT = fileURLToPath(new URL("../../", import.meta.url));

async function startPythonDataPlane(): Promise<{
  process: ChildProcessWithoutNullStreams;
  baseUrl: string;
}> {
  const child = spawn(
    "python3",
    ["-m", "shoprec.server", "--host", "127.0.0.1", "--port", "0"],
    {
      cwd: CODE_ROOT,
      env: {
        ...process.env,
        PYTHONPATH: path.join(CODE_ROOT, "src"),
        PYTHONUNBUFFERED: "1",
      },
    },
  );
  let diagnostics = "";
  child.stderr.on("data", (chunk) => {
    diagnostics += chunk.toString();
  });
  const baseUrl = await new Promise<string>((resolve, reject) => {
    const timeout = setTimeout(() => {
      reject(new Error(`Python data plane startup timed out: ${diagnostics}`));
    }, 5_000);
    child.stdout.on("data", (chunk) => {
      const output = chunk.toString();
      diagnostics += output;
      const match = output.match(/listening on (http:\/\/127\.0\.0\.1:\d+)/);
      if (match?.[1]) {
        clearTimeout(timeout);
        resolve(match[1]);
      }
    });
    child.once("exit", (code) => {
      clearTimeout(timeout);
      reject(new Error(`Python data plane exited with ${code}: ${diagnostics}`));
    });
  });
  return { process: child, baseUrl };
}

async function stopPythonDataPlane(child: ChildProcessWithoutNullStreams): Promise<void> {
  if (child.exitCode !== null) return;
  child.kill("SIGTERM");
  await once(child, "exit");
}

test("Pi control plane consumes the real Python SPU/SKU/Offer endpoints", { timeout: 15_000 }, async () => {
  const dataPlane = await startPythonDataPlane();
  try {
    const reply = await new SearchAdsRecsLeadAgent({
      channels: new PythonDiscoveryAdapter(dataPlane.baseUrl),
    }).handle("总预算7000元，重视拍照和续航，帮我选手机并搭配降噪耳机和充电器");
    assert.equal(reply.critique.verdict, "approved");
    assert.equal(reply.bundle.items.length, 3);
    assert.ok(
      reply.bundle.items.every(
        (candidate) =>
          candidate.product.spuId.startsWith("spu-") &&
          candidate.product.skuId.startsWith("sku-") &&
          candidate.product.offerId.startsWith("offer-") &&
          candidate.product.quoteVersion.startsWith("realtime-"),
      ),
    );
    assert.equal(reply.priceQuote.quotes.length, 3);
    assert.equal(reply.reviewEvidence.reviewSnapshotVersion, "review-aspects-v1");
    assert.equal(reply.reviewEvidence.products.length, 3);
    assert.ok(
      reply.bundle.compatibility.every(
        (result) =>
          result.ruleVersion === "compatibility-graph-v1" && result.paths.length > 0,
      ),
    );
    assert.ok(reply.trace.some((record) => record.event === "handoff_fallback_scheduled"));

    const dataPlaneAdapter = new PythonDiscoveryAdapter(dataPlane.baseUrl);
    const buyer = new SearchAdsRecsBuyerAgent({
      channels: dataPlaneAdapter,
      evidence: dataPlaneAdapter,
    });
    const proposal = await buyer.handle({
      sessionId: "python-integration-session",
      userId: "python-integration-user",
      message: "总预算7000元，帮我选手机并搭配耳机和充电器",
    });
    assert.equal(proposal.phase, "proposal");
    const confirmation = await buyer.handle({
      sessionId: "python-integration-session",
      userId: "python-integration-user",
      message: "确认生成购物车草案",
      confirmed: true,
    });
    assert.equal(confirmation.phase, "cart_draft");
    assert.equal(confirmation.cartDraft?.paymentAuthorized, false);
    assert.equal(buyer.metrics.snapshot().northStar.value, 1);
  } finally {
    await stopPythonDataPlane(dataPlane.process);
  }
});
