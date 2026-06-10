import { DEMO_CATALOG } from "./catalog.js";
import type {
  CandidateEnvelope,
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
  if (accessory.product.category === "headphones") {
    const compatible =
      primary.product.connectors.includes("bluetooth") &&
      accessory.product.connectors.includes("bluetooth");
    reasons.push(compatible ? "connector_graph_match" : "connector_graph_mismatch");
    if (compatible) {
      paths.push(
        `${primary.product.skuId}-[HAS_CONNECTOR]->bluetooth<-[HAS_CONNECTOR]-${accessory.product.skuId}`,
      );
    }
    return {
      productId: primary.product.productId,
      accessoryId: accessory.product.productId,
      status: compatible ? "compatible" : "incompatible",
      reasons,
      ruleVersion: "compatibility-graph-replay-v1",
      paths,
    };
  }
  if (accessory.product.category === "charger") {
    const sharedConnectors = primary.product.connectors.filter((value) =>
      accessory.product.connectors.includes(value),
    );
    const sharedProtocols = primary.product.protocols.filter((value) =>
      accessory.product.protocols.includes(value),
    );
    reasons.push(sharedConnectors.length > 0 ? "connector_graph_match" : "connector_graph_mismatch");
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
    return {
      productId: primary.product.productId,
      accessoryId: accessory.product.productId,
      status: sharedConnectors.length > 0 && sharedProtocols.length > 0
        ? "compatible"
        : sharedConnectors.length > 0
        ? "unknown"
        : "incompatible",
      reasons,
      ruleVersion: "compatibility-graph-replay-v1",
      paths,
    };
  }
  return {
    productId: primary.product.productId,
    accessoryId: accessory.product.productId,
    status: "unknown",
    reasons: ["no_graph_rule_for_category_pair"],
    ruleVersion: "compatibility-graph-replay-v1",
    paths: [],
  };
}

function syntheticEvidence(productId: string): ProductReviewEvidence | null {
  const product = DEMO_CATALOG.find((item) => item.productId === productId);
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
    source: "synthetic_review_snapshot",
  };
}

export class InMemoryDecisionEvidenceGateway implements DecisionEvidenceGateway {
  async checkCompatibility(
    primary: CandidateEnvelope,
    accessories: CandidateEnvelope[],
  ): Promise<CompatibilityResult[]> {
    return accessories.map((accessory) => compatibility(primary, accessory));
  }

  async quote(items: CandidateEnvelope[]): Promise<PriceQuoteBatch> {
    return {
      quoteBatchId: "quote-batch-replay-v1",
      quoteVersion: "realtime-quote-replay-v1",
      issuedAt: new Date(0).toISOString(),
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

  async reviewAspects(productIds: string[]): Promise<ReviewEvidenceBatch> {
    const products = productIds
      .map((productId) => syntheticEvidence(productId))
      .filter((item): item is ProductReviewEvidence => item !== null);
    const found = new Set(products.map((item) => item.productId));
    return {
      reviewSnapshotVersion: "review-aspects-replay-v1",
      products,
      missingProductIds: productIds.filter((productId) => !found.has(productId)),
    };
  }
}
