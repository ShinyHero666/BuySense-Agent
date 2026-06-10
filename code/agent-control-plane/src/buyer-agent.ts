import { CartDraftAgent } from "./cart-agent.js";
import { InMemoryDiscoveryChannels } from "./channels.js";
import type {
  BuyerTurnReply,
  BuyerTurnRequest,
  AgentTraceRecord,
  DecisionEvidenceGateway,
  DiscoveryChannels,
  DiscoveryContext,
} from "./contracts.js";
import { InMemoryDecisionEvidenceGateway } from "./evidence.js";
import { SearchAdsRecsMetrics } from "./metrics.js";
import { SearchAdsRecsLeadAgent } from "./orchestrator.js";
import { ReplayPiRuntimeFactory, type PiRuntimeFactory } from "./pi-runtime.js";
import {
  InMemoryCartDraftStore,
  InMemoryPendingDecisionStore,
  type CartDraftStore,
  type PendingDecisionStore,
} from "./session-store.js";

function supportsEvidence(
  channels: DiscoveryChannels,
): channels is DiscoveryChannels & DecisionEvidenceGateway {
  const value = channels as Partial<DecisionEvidenceGateway>;
  return (
    typeof value.checkCompatibility === "function" &&
    typeof value.quote === "function" &&
    typeof value.reviewAspects === "function"
  );
}

export class SearchAdsRecsBuyerAgent {
  readonly metrics: SearchAdsRecsMetrics;
  readonly drafts: CartDraftStore;
  readonly runtime: PiRuntimeFactory;
  readonly #lead: SearchAdsRecsLeadAgent;
  readonly #cart: CartDraftAgent;
  readonly #pending: PendingDecisionStore;

  constructor(options: {
    runtime?: PiRuntimeFactory;
    channels?: DiscoveryChannels;
    evidence?: DecisionEvidenceGateway;
    metrics?: SearchAdsRecsMetrics;
    pending?: PendingDecisionStore;
    drafts?: CartDraftStore;
    now?: () => number;
  } = {}) {
    const runtime = options.runtime ?? new ReplayPiRuntimeFactory();
    this.runtime = runtime;
    const channels = options.channels ?? new InMemoryDiscoveryChannels();
    const evidence = options.evidence ?? (
      supportsEvidence(channels) ? channels : new InMemoryDecisionEvidenceGateway()
    );
    const now = options.now ?? Date.now;
    this.metrics = options.metrics ?? new SearchAdsRecsMetrics(now);
    this.drafts = options.drafts ?? new InMemoryCartDraftStore(now);
    this.#pending = options.pending ?? new InMemoryPendingDecisionStore(now);
    this.#lead = new SearchAdsRecsLeadAgent({ runtime, channels, evidence });
    this.#cart = new CartDraftAgent({ runtime, evidence, now });
  }

  async handle(
    request: BuyerTurnRequest,
    options: {
      onTrace?: (record: AgentTraceRecord) => void;
      signal?: AbortSignal;
      discoveryContext?: DiscoveryContext;
    } = {},
  ): Promise<BuyerTurnReply> {
    if (!request.confirmed) {
      const decision = await this.#lead.handle(request.message, options.onTrace, {
        ...options.discoveryContext,
        identityId: request.userId,
        sessionId: request.sessionId,
        personalizationEnabled: options.discoveryContext?.personalizationEnabled ?? true,
        ...(options.signal ? { signal: options.signal } : {}),
      });
      this.metrics.recordProposal(decision);
      if (decision.critique.verdict === "approved") {
        this.#pending.put(request.sessionId, request.userId, decision);
        return {
          phase: "proposal",
          message: `${decision.message}\n如接受，请明确确认生成购物车草案；本操作不会发起支付。`,
          decision,
          cartDraft: null,
          confirmationTrace: [],
        };
      }
      this.#pending.delete(request.sessionId);
      return {
        phase: "needs_replan",
        message: decision.message,
        decision,
        cartDraft: null,
        confirmationTrace: [],
      };
    }

    const pending = this.#pending.get(request.sessionId, request.userId);
    if (!pending) {
      this.metrics.recordNoPendingConfirmation();
      return {
        phase: "no_pending_decision",
        message: "没有可确认的有效方案，请先发起一次购买需求。",
        decision: null,
        cartDraft: null,
        confirmationTrace: [],
      };
    }
    const outcome = await this.#cart.confirm(
      request.sessionId,
      pending.decision,
      options.onTrace,
    );
    this.metrics.recordConfirmation(outcome);
    const decision = {
      ...pending.decision,
      bundle: outcome.refreshedBundle,
      priceQuote: outcome.priceQuote,
    };
    this.#pending.delete(request.sessionId);
    if (outcome.status === "created" && outcome.draft) {
      this.drafts.put(outcome.draft);
      return {
        phase: "cart_draft",
        message: `购物车草案已生成：${outcome.draft.draftId}，合计 ¥${outcome.draft.totalPrice}。未发起支付。`,
        decision,
        cartDraft: outcome.draft,
        confirmationTrace: outcome.trace,
      };
    }
    return {
      phase: "needs_replan",
      message: `确认时校验未通过：${outcome.violations.join("、")}。请重新生成方案。`,
      decision,
      cartDraft: null,
      confirmationTrace: outcome.trace,
    };
  }
}
