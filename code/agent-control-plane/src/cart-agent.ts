import { randomUUID } from "node:crypto";
import { InMemoryArtifactStore } from "./artifacts.js";
import type {
  CartDraft,
  CartDraftOutcome,
  DecisionEvidenceGateway,
  PriceQuoteBatch,
  AgentTraceRecord,
  SearchAdsRecsReply,
} from "./contracts.js";
import { applyPriceQuotes } from "./fusion.js";
import type { PiRuntimeFactory } from "./pi-runtime.js";
import { TraceCollector } from "./role-agent.js";

function minimumQuoteExpiry(batch: PriceQuoteBatch, fallback: number): number {
  const values = batch.quotes
    .map((quote) => Date.parse(quote.validUntil))
    .filter((value) => Number.isFinite(value));
  return values.length === 0 ? fallback : Math.min(...values);
}

/** Confirmation is a deterministic tool workflow; no LLM can authorize cart or payment state. */
export class CartDraftAgent {
  readonly #evidence: DecisionEvidenceGateway;
  readonly #now: () => number;

  constructor(options: {
    evidence: DecisionEvidenceGateway;
    runtime?: PiRuntimeFactory;
    now?: () => number;
  }) {
    this.#evidence = options.evidence;
    this.#now = options.now ?? Date.now;
  }

  async confirm(
    sessionId: string,
    decision: SearchAdsRecsReply,
    onTrace?: (record: AgentTraceRecord) => void,
    signal?: AbortSignal,
  ): Promise<CartDraftOutcome> {
    const runId = `cart-run-${randomUUID()}`;
    const artifacts = new InMemoryArtifactStore();
    const trace = new TraceCollector(onTrace);
    trace.add("cart", "run_started", { sessionIdChars: sessionId.length });

    trace.add("cart", "tool_delegated", { to: "pricing", reason: "confirmation_refresh" });
    const quotes = await this.#evidence.quote(decision.bundle.items, signal);
    trace.add("pricing", "data_plane_result", {
      resource: "pricing",
      purpose: "confirmation_refresh",
      quoteBatchId: quotes.quoteBatchId,
      quoteVersion: quotes.quoteVersion,
      quoteCount: quotes.quotes.length,
      source: quotes.dataSource.source,
      sourceVersion: quotes.dataSource.sourceVersion,
      providerId: quotes.dataSource.providerId,
    });
    const priceArtifact = artifacts.publish({
      runId,
      parentTaskId: decision.runId,
      type: "price_quote",
      producer: "pricing",
      status: "verified",
      payload: quotes,
    });
    trace.add("pricing", "tool_artifact_published", {
      artifactId: priceArtifact.artifactId,
      artifactType: priceArtifact.type,
    });
    const refreshedBundle = applyPriceQuotes(decision.bundle, quotes);
    const quoteByOffer = new Map(quotes.quotes.map((quote) => [quote.offerId, quote]));
    const now = this.#now();
    const violations: string[] = [];
    if (decision.critique.verdict !== "approved") violations.push("proposal_not_approved");
    if (refreshedBundle.items.length === 0) violations.push("empty_bundle");
    if (!refreshedBundle.withinBudget) violations.push("budget_changed");
    if (refreshedBundle.items.some((item) => item.product.stock <= 0)) {
      violations.push("offer_unavailable");
    }
    if (refreshedBundle.compatibility.some((item) => item.status !== "compatible")) {
      violations.push("compatibility_not_verified");
    }
    for (const item of refreshedBundle.items) {
      const quote = quoteByOffer.get(item.product.offerId);
      if (
        !quote ||
        quote.status !== "active" ||
        quote.amount !== item.product.price ||
        Date.parse(quote.validUntil) <= now
      ) violations.push(`invalid_quote:${item.product.offerId}`);
    }

    let draft: CartDraft | null = null;
    if (violations.length === 0) {
      const createdAt = this.#now();
      const expiresAt = Math.min(
        createdAt + 15 * 60 * 1_000,
        minimumQuoteExpiry(quotes, createdAt),
      );
      draft = {
        draftId: `cart-draft-${randomUUID()}`,
        sessionId,
        status: "ready",
        items: refreshedBundle.items.map((item) => ({
          spuId: item.product.spuId,
          skuId: item.product.skuId,
          offerId: item.product.offerId,
          title: item.product.title,
          quantity: 1,
          unitPrice: item.product.price,
          currency: item.product.currency,
          quoteVersion: item.product.quoteVersion,
        })),
        totalPrice: refreshedBundle.totalPrice,
        currency: "CNY",
        quoteBatchId: quotes.quoteBatchId,
        createdAt: new Date(createdAt).toISOString(),
        expiresAt: new Date(expiresAt).toISOString(),
        paymentAuthorized: false,
      };
    }
    const status = draft ? "created" as const : "rejected" as const;
    const cartArtifact = artifacts.publish({
      runId,
      parentTaskId: priceArtifact.artifactId,
      type: "cart_draft",
      producer: "cart",
      status: draft ? "verified" : "vetoed",
      payload: { status, violations, draft },
    });
    trace.add("cart", "policy_gate", {
      artifactId: cartArtifact.artifactId,
      status,
      violationCount: violations.length,
      paymentAuthorized: false,
    });
    trace.add("cart", "run_completed", { status });
    return {
      status,
      violations,
      draft,
      refreshedBundle,
      priceQuote: quotes,
      artifactIds: artifacts.list(runId).map((artifact) => artifact.artifactId),
      trace: trace.records,
    };
  }
}
