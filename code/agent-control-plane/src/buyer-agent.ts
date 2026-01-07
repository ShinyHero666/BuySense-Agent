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
import {
  loadCatalogForDomainPack,
  NORMAL_3C_DOMAIN,
  type CommerceDomainPack,
} from "./domain-pack.js";
import { SearchAdsRecsMetrics } from "./metrics.js";
import { SearchAdsRecsLeadAgent } from "./orchestrator.js";
import type { RegisteredWorkflow } from "./extension-registry.js";
import { ReplayPiRuntimeFactory, type PiRuntimeFactory } from "./pi-runtime.js";
import {
  InMemoryCartDraftStore,
  InMemoryPendingDecisionStore,
  type CartDraftStore,
  type PendingDecision,
  type PendingDecisionStore,
} from "./session-store.js";

export interface ConfirmationCommitRequest {
  pending: PendingDecision;
  result: BuyerTurnReply;
}

export interface ConfirmationCommitResult {
  status: "committed" | "replayed" | "stale";
  result?: BuyerTurnReply;
}

export type ConfirmationCommitter = (
  request: ConfirmationCommitRequest,
) => ConfirmationCommitResult;

export interface DecisionCommitRequest {
  result: BuyerTurnReply;
}

export type DecisionCommitter = (
  request: DecisionCommitRequest,
) => ConfirmationCommitResult;

function noPendingReply(message: string): BuyerTurnReply {
  return {
    phase: "no_pending_decision",
    message,
    decision: null,
    cartDraft: null,
    confirmationTrace: [],
  };
}

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
  readonly domain: CommerceDomainPack;
  readonly #lead: SearchAdsRecsLeadAgent;
  readonly #cart: CartDraftAgent;
  readonly #pending: PendingDecisionStore;
  readonly #commitConfirmation: ConfirmationCommitter | undefined;

  constructor(options: {
    runtime?: PiRuntimeFactory;
    channels?: DiscoveryChannels;
    evidence?: DecisionEvidenceGateway;
    metrics?: SearchAdsRecsMetrics;
    pending?: PendingDecisionStore;
    drafts?: CartDraftStore;
    now?: () => number;
    domain?: CommerceDomainPack;
    workflow?: RegisteredWorkflow;
    commitConfirmation?: ConfirmationCommitter;
  } = {}) {
    const domain = options.domain ?? NORMAL_3C_DOMAIN;
    this.domain = domain;
    const catalog = loadCatalogForDomainPack(domain);
    const runtime = options.runtime ?? new ReplayPiRuntimeFactory();
    this.runtime = runtime;
    const channels = options.channels ?? new InMemoryDiscoveryChannels(catalog, domain);
    const evidence = options.evidence ?? (
      supportsEvidence(channels) ? channels : new InMemoryDecisionEvidenceGateway(catalog)
    );
    const now = options.now ?? Date.now;
    this.metrics = options.metrics ?? new SearchAdsRecsMetrics(now);
    this.drafts = options.drafts ?? new InMemoryCartDraftStore(now);
    this.#pending = options.pending ?? new InMemoryPendingDecisionStore(now);
    this.#commitConfirmation = options.commitConfirmation;
    this.#lead = new SearchAdsRecsLeadAgent({
      runtime,
      channels,
      evidence,
      domain,
      ...(options.workflow ? { workflow: options.workflow } : {}),
    });
    this.#cart = new CartDraftAgent({ runtime, evidence, now });
  }

  async handle(
    request: BuyerTurnRequest,
    options: {
      onTrace?: (record: AgentTraceRecord) => void;
      signal?: AbortSignal;
      discoveryContext?: DiscoveryContext;
      commitDecision?: DecisionCommitter;
      commitConfirmation?: ConfirmationCommitter;
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
      const result: BuyerTurnReply = decision.critique.verdict === "approved"
        ? {
          phase: "proposal",
          message: `${decision.message}\n如接受，请明确确认生成购物车草案；本操作不会发起支付。`,
          decision,
          cartDraft: null,
          confirmationTrace: [],
        }
        : {
          phase: "needs_replan",
          message: decision.message,
          decision,
          cartDraft: null,
          confirmationTrace: [],
        };
      const commitResult = options.commitDecision
        ? options.commitDecision({ result })
        : (() => {
            if (result.phase === "proposal") {
              this.#pending.put(request.sessionId, request.userId, decision);
            } else {
              this.#pending.delete(request.sessionId);
            }
            return { status: "committed" as const, result };
          })();
      if (commitResult.status === "committed") this.metrics.recordProposal(decision);
      return commitResult.result ?? result;
    }

    const pending = this.#pending.get(request.sessionId, request.userId);
    if (!pending) {
      this.metrics.recordNoPendingConfirmation();
      return noPendingReply("没有可确认的有效方案，请先发起一次购买需求。");
    }
    // Pending decisions share an identity-scoped store across all pack-specific
    // Agent instances. Bind confirmation to the pack/workflow that produced the
    // proposal so an API caller cannot confirm a camping decision through a 3C Run.
    const pendingDomainPackId = pending.decision.domainPackId ?? NORMAL_3C_DOMAIN.packId;
    const pendingWorkflowId = pending.decision.workflowId ?? NORMAL_3C_DOMAIN.workflowId;
    if (
      pendingDomainPackId !== this.domain.packId ||
      pendingWorkflowId !== this.domain.workflowId
    ) {
      this.metrics.recordNoPendingConfirmation();
      return noPendingReply("待确认方案属于另一个领域包，请回到原方案后再确认。");
    }
    if (request.proposalRunId && request.proposalRunId !== pending.decision.runId) {
      this.metrics.recordNoPendingConfirmation();
      return noPendingReply("待确认方案已更新，请确认当前展示的方案。");
    }
    const outcome = await this.#cart.confirm(
      request.sessionId,
      pending.decision,
      options.onTrace,
      options.signal,
    );
    const decision = {
      ...pending.decision,
      bundle: outcome.refreshedBundle,
      priceQuote: outcome.priceQuote,
    };
    const result: BuyerTurnReply = outcome.status === "created" && outcome.draft
      ? {
        phase: "cart_draft",
        message: `购物车草案已生成：${outcome.draft.draftId}，合计 ¥${outcome.draft.totalPrice}。未发起支付。`,
        decision,
        cartDraft: outcome.draft,
        confirmationTrace: outcome.trace,
      }
      : {
        phase: "needs_replan",
        message: `确认时校验未通过：${outcome.violations.join("、")}。请重新生成方案。`,
        decision,
        cartDraft: null,
        confirmationTrace: outcome.trace,
      };

    const commit = options.commitConfirmation ?? this.#commitConfirmation;
    const commitResult = commit
      ? commit({ pending, result })
      : (() => {
          // Keep the pending decision recoverable if draft persistence throws.
          if (result.cartDraft) this.drafts.put(result.cartDraft);
          this.#pending.delete(request.sessionId);
          return { status: "committed" as const, result };
        })();
    if (commitResult.status === "stale") {
      this.metrics.recordNoPendingConfirmation();
      return noPendingReply("方案已被更新或确认，请刷新后查看最新状态。");
    }
    if (commitResult.status === "committed") this.metrics.recordConfirmation(outcome);
    return commitResult.result ?? result;
  }
}
