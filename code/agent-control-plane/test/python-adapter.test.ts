import assert from "node:assert/strict";
import { createServer, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import test from "node:test";
import { DEMO_CATALOG } from "../src/catalog.js";
import type { CandidateEnvelope } from "../src/contracts.js";
import { PythonDiscoveryAdapter } from "../src/python-adapter.js";
import { buildRetrievalPlan } from "../src/router.js";

async function listen(server: Server): Promise<string> {
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

async function close(server: Server): Promise<void> {
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
}

function candidate(): CandidateEnvelope {
  const product = DEMO_CATALOG[0];
  assert.ok(product);
  return {
    product,
    channel: "search",
    sources: ["search"],
    channelScore: 1,
    normalizedScore: 1,
    reasons: ["test_fixture"],
    sponsored: false,
    disclosure: null,
  };
}

function sendJson(response: ServerResponse, payload: unknown): void {
  const body = Buffer.from(JSON.stringify(payload));
  response.writeHead(200, {
    "content-type": "application/json",
    "content-length": body.length,
  });
  response.end(body);
}

test("Python adapter rejects self-contradictory provenance and review partitions", async () => {
  let reviewMode: "provenance" | "overlap" = "provenance";
  const item = candidate();
  const server = createServer((request, response) => {
    if (request.url === "/api/v2/discovery/search") {
      sendJson(response, {
        channel: "search",
        catalog_version: "catalog-v1",
        quote_version: "quote-v1",
        data_source: {
          source: "local_snapshot",
          source_version: "catalog-v2",
          provider_id: "normal-3c-v1",
        },
        items: [],
      });
      return;
    }
    if (request.url === "/api/v2/pricing/quote") {
      sendJson(response, {
        quote_batch_id: "quote-batch-v1",
        quote_version: "quote-v1",
        issued_at: "2026-08-09T00:00:00Z",
        data_source: {
          source: "local_snapshot",
          source_version: "quote-v2",
          provider_id: "normal-3c-v1",
        },
        quotes: [{
          offer_id: item.product.offerId,
          status: "active",
          amount: item.product.price,
          currency: "CNY",
          stock: item.product.stock,
          valid_until: "2099-01-01T00:00:00Z",
          reason: "live_offer",
        }],
      });
      return;
    }
    if (request.url === "/api/v2/evidence/reviews") {
      const product = {
        product_id: item.product.productId,
        sample_size: 1,
        aspects: [],
        source: reviewMode === "provenance" ? "remote_provider" : "local_snapshot",
        source_version: "review-v1",
        provider_id: reviewMode === "provenance" ? "retail-a1b2c3" : "normal-3c-v1",
      };
      sendJson(response, {
        review_snapshot_version: "review-v1",
        data_source: {
          source: "local_snapshot",
          source_version: "review-v1",
          provider_id: "normal-3c-v1",
        },
        products: [product],
        missing_product_ids: reviewMode === "overlap" ? [item.product.productId] : [],
      });
      return;
    }
    response.writeHead(404).end();
  });
  const baseUrl = await listen(server);
  const adapter = new PythonDiscoveryAdapter(baseUrl);
  try {
    await assert.rejects(
      adapter.search(buildRetrievalPlan("预算5000元，推荐手机")),
      /data source version does not match catalog_version/,
    );
    await assert.rejects(
      adapter.quote([item]),
      /data source version does not match quote_version/,
    );
    await assert.rejects(
      adapter.reviewAspects([item.product.productId]),
      /product provenance does not match batch provenance/,
    );
    reviewMode = "overlap";
    await assert.rejects(
      adapter.reviewAspects([item.product.productId]),
      /does not partition requested products/,
    );
  } finally {
    await close(server);
  }
});

test("Python adapter composes caller cancellation with its HTTP timeout", async () => {
  let markRequestStarted!: () => void;
  const requestStarted = new Promise<void>((resolve) => {
    markRequestStarted = resolve;
  });
  const server = createServer((_request, _response) => markRequestStarted());
  const baseUrl = await listen(server);
  const adapter = new PythonDiscoveryAdapter(baseUrl);
  const controller = new AbortController();
  const operation = adapter.search(
    buildRetrievalPlan("预算5000元，推荐手机"),
    { signal: controller.signal },
  );
  try {
    await requestStarted;
    controller.abort(new Error("caller_cancelled"));
    await assert.rejects(operation, /caller_cancelled/);
  } finally {
    await close(server);
  }
});
