import { DEMO_CATALOG } from "./catalog.js";
import type {
  CandidateEnvelope,
  CatalogProduct,
  CompatibilityResult,
  DecisionEvidenceGateway,
  PriceQuoteBatch,
  ProductReviewEvidence,
  ReviewEvidenceBatch,
} from "./contracts.js";

function compatibility(
  primary: CandidateEnvelope,
  accessory: CandidateEnvelope,
): CompatibilityResult {
  const reasons: string[] = [];
  const paths: string[] = [];
  const sharedConnectors = primary.product.connectors.filter((value) =>
    accessory.product.connectors.includes(value),
  );
  const sharedProtocols = primary.product.protocols.filter((value) =>
    accessory.product.protocols.includes(value),
  );
  reasons.push(sharedConnectors.length > 0 ? "connector_graph_match" : "connector_graph_unknown");
  reasons.push(sharedProtocols.length > 0 ? "protocol_graph_match" : "protocol_graph_unknown");
  paths.push(
    ...sharedConnectors.map(
      (value) =>
        `${primary.product.skuId}-[HAS_CONNECTOR]->${value}<-[HAS_CONNECTOR]-${accessory.product.skuId}`,
    ),
    ...sharedProtocols.map(
      (value) =>
        `${primary.product.skuId}-[SUPPORTS_PROTOCOL]->${value}<-[SUPPORTS_PROTOCOL]-${accessory.product.skuId}`,
    ),
  );
  if (paths.length > 0) {
    return {
      productId: primary.product.productId,
      accessoryId: accessory.product.productId,
      status: "compatible",
      reasons,
      ruleVersion: "compatibility-graph-replay-v2",
      paths,
    };
  }
  return {
    productId: primary.product.productId,
    accessoryId: accessory.product.productId,
    status: "unknown",
    reasons: ["no_shared_compatibility_attribute"],
    ruleVersion: "compatibility-graph-replay-v2",
    paths: [],
  };
}

function syntheticEvidence(
  catalog: CatalogProduct[],
  productId: string,
  sourceVersion: string,
  providerId: string,
): ProductReviewEvidence | null {
  const product = catalog.find((item) => item.productId === productId);
  if (!product) return null;
  const aspect = product.tags.find((tag) => !["手机", "耳机", "充电器", "usb-c"].includes(tag))
    ?? "综合体验";
  return {
    productId,
    sampleSize: 100,
    aspects: [
      {
        aspect,
        sentiment: 0.72,
        mentionCount: 64,
        confidence: 0.86,
        summary: `${aspect}是离线合成评论快照中的主要正向主题。`,
      },
    ],
    source: "local_snapshot",
    sourceVersion,
    providerId,
  };
}

export class InMemoryDecisionEvidenceGateway implements DecisionEvidenceGateway {
  constructor(private readonly catalog: CatalogProduct[] = DEMO_CATALOG) {}

  #providerId(): string {
    return this.catalog[0]?.dataSource.providerId ?? "embedded-replay";
  }

  async checkCompatibility(
    primary: CandidateEnvelope,
    accessories: CandidateEnvelope[],
    signal?: AbortSignal,
  ): Promise<CompatibilityResult[]> {
    signal?.throwIfAborted();
    return accessories.map((accessory) => compatibility(primary, accessory));
  }

  async quote(items: CandidateEnvelope[], signal?: AbortSignal): Promise<PriceQuoteBatch> {
    signal?.throwIfAborted();
    const quoteVersion = "realtime-quote-replay-v1";
    return {
      quoteBatchId: "quote-batch-replay-v1",
      quoteVersion,
      issuedAt: new Date(0).toISOString(),
      dataSource: {
        source: "local_snapshot",
        sourceVersion: quoteVersion,
        providerId: this.#providerId(),
      },
      quotes: items.map((item) => ({
        offerId: item.product.offerId,
        status: item.product.stock > 0 ? "active" : "unavailable",
        amount: item.product.stock > 0 ? item.product.price : null,
        currency: item.product.currency,
        stock: item.product.stock,
        validUntil: item.product.quoteValidUntil,
        reason: "offline_offer_snapshot",
      })),
    };
  }

  async reviewAspects(
    productIds: string[],
    signal?: AbortSignal,
  ): Promise<ReviewEvidenceBatch> {
    signal?.throwIfAborted();
    const sourceVersion = "review-aspects-replay-v1";
    const providerId = this.#providerId();
    const products = productIds
      .map((productId) => syntheticEvidence(this.catalog, productId, sourceVersion, providerId))
      .filter((item): item is ProductReviewEvidence => item !== null);
    const found = new Set(products.map((item) => item.productId));
    return {
      reviewSnapshotVersion: "review-aspects-replay-v1",
      dataSource: {
        source: "local_snapshot",
        sourceVersion,
        providerId,
      },
      products,
      missingProductIds: productIds.filter((productId) => !found.has(productId)),
    };
  }
}
