import { InMemoryArtifactStore, type Artifact, type ArtifactType } from "./artifacts.js";
import { randomUUID } from "node:crypto";
import { InMemoryDiscoveryChannels } from "./channels.js";
import { BoundedCollaborationCoordinator } from "./collaboration.js";
import type {
  AgentRole,
  AgentTraceRecord,
  BundleProposal,
  CandidateEnvelope,
  ChannelResult,
  Critique,
  DataSourceMetadata,
  DecisionEvidenceGateway,
  DecisionOptimizationGateway,
  DiscoveryChannels,
  DiscoveryContext,
  PeerHandoff,
  PriceQuoteBatch,
  ProductCategory,
  RetrievalChannel,
  RetrievalPlan,
  ReviewEvidenceBatch,
  RevisionRequest,
  SearchAdsRecsReply,
} from "./contracts.js";
import { InMemoryDecisionEvidenceGateway } from "./evidence.js";
import {
  createDefaultExtensionRegistries,
  type RegisteredWorkflow,
} from "./extension-registry.js";
import { applyPriceQuotes, auditProposal, fuseSlate, proposeBundle } from "./fusion.js";
import { ReplayPiRuntimeFactory, type PiRuntimeFactory } from "./pi-runtime.js";
import { PiRoleAgent, TraceCollector } from "./role-agent.js";
import { buildRetrievalPlan } from "./router.js";
import {
  ROLE_PROPOSAL_CONTRACTS,
  ROLE_SYSTEM_PROMPTS,
  retrievalPlanProposalContract,
  resolveChannelRanking,
  resolveCritique,
  resolveFinalDecision,
  resolveRetrievalPlan,
  summarizeRuntime,
} from "./model-policy.js";
import {
  loadCatalogForDomainPack,
  NORMAL_3C_DOMAIN,
  type CommerceDomainPack,
} from "./domain-pack.js";

const CHANNEL_ROLES: Record<RetrievalChannel, AgentRole> = {
  search: "search",
  recommendation: "recommendation",
  ads: "ads",
};

function dataSourceTrace(source: DataSourceMetadata): Record<string, unknown> {
  return {
    source: source.source,
    sourceVersion: source.sourceVersion,
    providerId: source.providerId,
  };
}

function supportsDecisionEvidence(
  value: DiscoveryChannels,
): value is DiscoveryChannels & DecisionEvidenceGateway {
  const candidate = value as Partial<DecisionEvidenceGateway>;
  return (
    typeof candidate.checkCompatibility === "function" &&
    typeof candidate.quote === "function" &&
    typeof candidate.reviewAspects === "function"
  );
}

function supportsDecisionOptimization(
  value: DiscoveryChannels,
): value is DiscoveryChannels & DecisionOptimizationGateway {
  const candidate = value as Partial<DecisionOptimizationGateway>;
  return typeof candidate.fuse === "function" && typeof candidate.optimizeBundle === "function";
}

function missingBundleCategories(
  plan: RetrievalPlan,
  bundle: BundleProposal,
  domain: CommerceDomainPack,
): ProductCategory[] {
  if (plan.intent !== "bundle") return [];
  const selected = new Set(bundle.items.map((candidate) => candidate.product.category));
  return plan.requirements.requestedCategories.filter(
    (category) => domain.defaultBundleCategories.includes(category) && !selected.has(category),
  );
}

function shouldRequestRevision(
  plan: RetrievalPlan,
  slate: CandidateEnvelope[],
  bundle: BundleProposal,
  critique: Critique,
  domain: CommerceDomainPack,
): ProductCategory[] {
  if (
    critique.verdict !== "vetoed" ||
    !plan.channels.includes("recommendation") ||
    !critique.violations.includes("requested_category_coverage")
  ) return [];
  return missingBundleCategories(plan, bundle, domain).filter((category) =>
    slate.some((candidate) => {
      if (candidate.product.category !== category || candidate.product.stock <= 0) return false;
      return plan.requirements.budgetMax === null ||
        bundle.totalPrice + candidate.product.price <= plan.requirements.budgetMax;
    }),
  );
}

function renderReply(
  bundle: BundleProposal,
  critique: Critique,
  priceQuote: PriceQuoteBatch,
  reviewEvidence: ReviewEvidenceBatch,
): string {
  if (critique.verdict === "vetoed") {
    return `方案未通过独立审核：${critique.violations.join("、")}。没有生成可确认草案。`;
  }
  const lines = ["搜广推多 Agent 已完成候选融合与独立审核："];
  bundle.items.forEach((candidate, index) => {
    lines.push(
      `${index + 1}. ${candidate.product.title}｜¥${candidate.product.price}` +
      (candidate.sponsored ? "｜赞助" : ""),
    );
  });
  lines.push(`套装合计：¥${bundle.totalPrice}；预算校验：${bundle.withinBudget ? "通过" : "不通过"}。`);
  const reviewByProduct = new Map(reviewEvidence.products.map((item) => [item.productId, item]));
  const highlights = bundle.items.flatMap((candidate) => {
    const aspect = reviewByProduct.get(candidate.product.productId)?.aspects[0];
    return aspect ? [`${candidate.product.brand}：${aspect.aspect}（${aspect.mentionCount}条提及）`] : [];
  });
  if (highlights.length > 0) {
    lines.push(`评论 Aspect 快照（合成教学数据）：${highlights.join("；")}。`);
  }
  if ((bundle.alternatives?.length ?? 0) > 1) {
    lines.push(`约束优化器同时保留 ${bundle.alternatives!.length} 套可比较方案。`);
  }
  lines.push(`价格批次：${priceQuote.quoteBatchId}；下单前仍需刷新库存与 Quote。`);
  return lines.join("\n");
}

function numbersInText(value: string): number[] {
  return [...value.matchAll(/\d+(?:\.\d+)?/g)].map((match) => Number(match[0]));
}

export class SearchAdsRecsLeadAgent {
  readonly #runtime: PiRuntimeFactory;
  readonly #channels: DiscoveryChannels;
  readonly #evidence: DecisionEvidenceGateway;
  readonly #optimizer: DecisionOptimizationGateway | null;
  readonly #domain: CommerceDomainPack;
  readonly #workflow: RegisteredWorkflow;
  constructor(options: {
    runtime?: PiRuntimeFactory;
    channels?: DiscoveryChannels;
    evidence?: DecisionEvidenceGateway;
    domain?: CommerceDomainPack;
    workflow?: RegisteredWorkflow;
  } = {}) {
    this.#domain = options.domain ?? NORMAL_3C_DOMAIN;
    this.#workflow = options.workflow ??
      createDefaultExtensionRegistries().workflows.require(this.#domain.workflowId);
    if (this.#workflow.id !== this.#domain.workflowId) {
      throw new Error(`domain workflow mismatch: ${this.#domain.packId}`);
    }
    if (this.#workflow.capabilityProfileId !== this.#domain.capabilityProfileId) {
      throw new Error(`domain capability profile mismatch: ${this.#domain.packId}`);
    }
    this.#runtime = options.runtime ?? new ReplayPiRuntimeFactory();
    const catalog = loadCatalogForDomainPack(this.#domain);
    this.#channels = options.channels ?? new InMemoryDiscoveryChannels(catalog, this.#domain);
    this.#evidence = options.evidence ?? (
      supportsDecisionEvidence(this.#channels)
        ? this.#channels
        : new InMemoryDecisionEvidenceGateway(options.channels ? undefined : catalog)
    );
    this.#optimizer = supportsDecisionOptimization(this.#channels) ? this.#channels : null;
  }

  async handle(
    message: string,
    onTrace?: (record: AgentTraceRecord) => void,
    discoveryContext: DiscoveryContext = {},
  ): Promise<SearchAdsRecsReply> {
    const runId = discoveryContext.executionRunId ?? `sar-run-${randomUUID()}`;
    const artifacts = new InMemoryArtifactStore();
    const trace = new TraceCollector(onTrace);
    const collaboration = new BoundedCollaborationCoordinator(
      runId,
      trace,
      {},
      this.#workflow.graph,
    );
    const runSignal = discoveryContext.signal
      ? AbortSignal.any([discoveryContext.signal, collaboration.signal])
      : collaboration.signal;
    const executionContext: DiscoveryContext = { ...discoveryContext, signal: runSignal };
    const roles = new PiRoleAgent(
      this.#runtime,
      artifacts,
      trace,
      runSignal,
      (proposal) => collaboration.proposeDelegation(proposal),
    );

    const publishTool = <T>(input: {
      type: ArtifactType;
      producer: AgentRole;
      parentTaskId: string | null;
      status: Artifact<T>["status"];
      payload: T;
    }): Artifact<T> => {
      const artifact = artifacts.publish({ runId, ...input });
      trace.add(input.producer, "tool_artifact_published", {
        artifactId: artifact.artifactId,
        artifactType: input.type,
        parentTaskId: input.parentTaskId,
      });
      return artifact;
    };

    trace.add("lead", "run_started", {
      messageChars: message.length,
      domainPackId: this.#domain.packId,
      workflowId: this.#domain.workflowId,
      capabilityProfileId: this.#domain.capabilityProfileId,
    });
    const planArtifact = await collaboration.delegate({
      delegatedBy: "lead",
      role: "intent_router",
      capability: "understand_and_route",
      parentTaskId: runId,
      execute: async (task) => {
        collaboration.consumeModelCall("intent_router");
        return roles.run<{ message: string }, RetrievalPlan>({
          role: "intent_router",
          runId,
          parentTaskId: task.taskId,
          artifactType: "retrieval_plan",
          status: "verified",
          input: { message },
          proposalContract: retrievalPlanProposalContract(this.#domain),
          systemPrompt: ROLE_SYSTEM_PROMPTS.intent_router,
          execute: ({ message: authoritativeMessage }, proposal) =>
            resolveRetrievalPlan(authoritativeMessage, proposal, this.#domain),
          fallback: ({ message: authoritativeMessage }) => buildRetrievalPlan(authoritativeMessage, this.#domain),
        });
      },
    });
    const plan = planArtifact.payload;

    const runChannel = async (input: {
      channel: RetrievalChannel;
      parentTaskId: string;
      delegatedBy: AgentRole;
      peerCandidates?: CandidateEnvelope[];
      revision?: RevisionRequest;
      useModel?: boolean;
      planOverride?: RetrievalPlan;
    }): Promise<Artifact<ChannelResult>> => {
      const role = CHANNEL_ROLES[input.channel];
      return collaboration.delegate({
        delegatedBy: input.delegatedBy,
        role,
        capability: `${input.channel}_strategy_and_retrieval`,
        parentTaskId: input.parentTaskId,
        revisionAttempt: input.revision?.attempt ?? 0,
        execute: async (task) => {
          const channelPlan = input.planOverride ?? plan;
          const context: DiscoveryContext = {
            ...executionContext,
            peerCandidates: input.peerCandidates ?? [],
            ...(input.revision ? { revision: input.revision } : {}),
          };
          let result: ChannelResult;
          try {
            result = input.channel === "search"
              ? await this.#channels.search(channelPlan, context)
              : input.channel === "recommendation"
                ? await this.#channels.recommend(channelPlan, context)
                : await this.#channels.ads(channelPlan, context);
          } catch (error) {
            if (input.channel === "search") throw error;
            trace.add(role, "degraded", {
              taskId: task.taskId,
              tier: input.channel === "ads" ? "ads_skipped" : "recommendation_unavailable",
              errorType: error instanceof Error ? error.name : "unknown",
            });
            result = { channel: input.channel, candidates: [] };
          }
          trace.add(role, "data_plane_result", {
            taskId: task.taskId,
            resource: "catalog",
            channel: input.channel,
            candidateCount: result.candidates.length,
            ...(result.dataSource
              ? dataSourceTrace(result.dataSource)
              : result.candidates[0]
                ? dataSourceTrace(result.candidates[0].product.dataSource)
                : {}),
          });
          if (input.useModel === false) {
            return publishTool({
              type: "candidate_set",
              producer: role,
              parentTaskId: task.taskId,
              status: "verified",
              payload: result,
            });
          }
          collaboration.consumeModelCall(role);
          return roles.run<{ plan: RetrievalPlan; result: ChannelResult }, ChannelResult>({
            role,
            runId,
            parentTaskId: task.taskId,
            artifactType: "candidate_set",
            status: "verified",
            input: { plan: channelPlan, result },
            proposalContract: ROLE_PROPOSAL_CONTRACTS.channelRanking,
            systemPrompt: ROLE_SYSTEM_PROMPTS[role],
            execute: ({ result: verifiedResult }, proposal) =>
              resolveChannelRanking(verifiedResult, proposal),
            fallback: ({ result: verifiedResult }) => verifiedResult,
          });
        },
      });
    };

    const searchPromise = plan.channels.includes("search")
      ? runChannel({
          channel: "search",
          delegatedBy: "lead",
          parentTaskId: planArtifact.artifactId,
        })
      : null;
    const adsPromise = plan.channels.includes("ads")
      ? runChannel({
          channel: "ads",
          delegatedBy: "lead",
          parentTaskId: planArtifact.artifactId,
        })
      : null;
    const searchArtifact = searchPromise ? await searchPromise : null;

    let handoffArtifact: Artifact<PeerHandoff> | null = null;
    const modelRequestedRecommendation = collaboration.takeApprovedProposal({
      proposedBy: "search",
      role: "recommendation",
      capability: "recommendation_strategy_and_retrieval",
    });
    if (searchArtifact && plan.channels.includes("recommendation")) {
      const primaryCandidates = searchArtifact.payload.candidates.filter(
        (candidate) => candidate.product.category === this.#domain.primaryCategory,
      );
      handoffArtifact = publishTool({
        type: "peer_handoff",
        producer: "search",
        parentTaskId: searchArtifact.artifactId,
        status: "verified",
        payload: {
          from: "search",
          to: "recommendation",
          purpose: "primary_product_grounding",
          candidates: primaryCandidates,
        },
      });
      if (modelRequestedRecommendation) {
        trace.add("search", "peer_delegated", {
          to: "recommendation",
          sourceArtifactId: handoffArtifact.artifactId,
          proposalId: modelRequestedRecommendation.proposalId,
          schedulingMode: "model_proposed_coordinator_approved",
        });
      } else {
        trace.add("lead", "handoff_fallback_scheduled", {
          to: "recommendation",
          sourceArtifactId: handoffArtifact.artifactId,
          schedulingMode: "retrieval_plan_fallback",
        });
      }
    }
    const recommendationArtifact = plan.channels.includes("recommendation")
      ? await runChannel({
          channel: "recommendation",
          delegatedBy: modelRequestedRecommendation ? "search" : "lead",
          parentTaskId: handoffArtifact?.artifactId ?? planArtifact.artifactId,
          peerCandidates: handoffArtifact?.payload.candidates ?? [],
        })
      : null;
    const adsArtifact = adsPromise ? await adsPromise : null;

    const channelMap = new Map<RetrievalChannel, ChannelResult>();
    for (const artifact of [searchArtifact, recommendationArtifact, adsArtifact]) {
      if (artifact) channelMap.set(artifact.payload.channel, artifact.payload);
    }
    let channelResults = plan.channels.flatMap((channel) => {
      const result = channelMap.get(channel);
      return result ? [result] : [];
    });

    const fuse = async (parentTaskId: string): Promise<CandidateEnvelope[]> =>
      collaboration.delegate({
        delegatedBy: "lead",
        role: "lead",
        capability: "calibrated_candidate_fusion",
        parentTaskId,
        execute: async (task) => {
          let fused: CandidateEnvelope[];
          try {
            fused = this.#optimizer
              ? await this.#optimizer.fuse(channelResults, undefined, runSignal)
              : fuseSlate(channelResults);
          } catch (error) {
            runSignal.throwIfAborted();
            trace.add("lead", "degraded", {
              tier: "local_rrf_fallback",
              errorType: error instanceof Error ? error.name : "unknown",
            });
            fused = fuseSlate(channelResults);
          }
          publishTool({
            type: "candidate_set",
            producer: "lead",
            parentTaskId: task.taskId,
            status: "verified",
            payload: { channel: "search" as const, candidates: fused },
          });
          return fused;
        },
      });

    let slate = await fuse(planArtifact.artifactId);

    const buildDecisionEvidence = async (parentTaskId: string) => {
      const bundleArtifact = await collaboration.delegate({
        delegatedBy: "lead",
        role: "compatibility",
        capability: "constraint_bundle_optimization",
        parentTaskId,
        execute: async (task) => {
          let bundle: BundleProposal;
          try {
            bundle = this.#optimizer
              ? await this.#optimizer.optimizeBundle(slate, plan, runSignal)
              : await proposeBundle(slate, plan, this.#evidence, [], this.#domain, runSignal);
          } catch (error) {
            runSignal.throwIfAborted();
            trace.add("compatibility", "degraded", {
              tier: "local_constraint_optimizer_fallback",
              errorType: error instanceof Error ? error.name : "unknown",
            });
            bundle = await proposeBundle(
              slate,
              plan,
              this.#evidence,
              [],
              this.#domain,
              runSignal,
            );
          }
          return publishTool({
            type: "bundle_proposal",
            producer: "compatibility",
            parentTaskId: task.taskId,
            status: "draft",
            payload: bundle,
          });
        },
      });
      const [priceArtifact, reviewArtifact] = await Promise.all([
        collaboration.delegate({
          delegatedBy: "compatibility",
          role: "pricing",
          capability: "live_quote_tool",
          parentTaskId: bundleArtifact.artifactId,
          execute: async (task) => {
            const payload = await this.#evidence.quote(bundleArtifact.payload.items, runSignal);
            trace.add("pricing", "data_plane_result", {
              taskId: task.taskId,
              resource: "pricing",
              quoteBatchId: payload.quoteBatchId,
              quoteVersion: payload.quoteVersion,
              quoteCount: payload.quotes.length,
              ...dataSourceTrace(payload.dataSource),
            });
            return publishTool({
              type: "price_quote",
              producer: "pricing",
              parentTaskId: task.taskId,
              status: "verified",
              payload,
            });
          },
        }),
        collaboration.delegate({
          delegatedBy: "compatibility",
          role: "review_evidence",
          capability: "review_aspect_tool",
          parentTaskId: bundleArtifact.artifactId,
          execute: async (task) => {
            const payload = await this.#evidence.reviewAspects(
              bundleArtifact.payload.items.map((item) => item.product.productId),
              runSignal,
            );
            trace.add("review_evidence", "data_plane_result", {
              taskId: task.taskId,
              resource: "reviews",
              reviewSnapshotVersion: payload.reviewSnapshotVersion,
              productCount: payload.products.length,
              missingProductCount: payload.missingProductIds.length,
              ...dataSourceTrace(payload.dataSource),
            });
            return publishTool({
              type: "review_evidence",
              producer: "review_evidence",
              parentTaskId: task.taskId,
              status: "verified",
              payload,
            });
          },
        }),
      ]);
      return {
        bundle: applyPriceQuotes(bundleArtifact.payload, priceArtifact.payload),
        priceQuote: priceArtifact.payload,
        reviewEvidence: reviewArtifact.payload,
        parentTaskId: reviewArtifact.artifactId,
      };
    };

    const runModelCritic = async (
      parentTaskId: string,
      bundle: BundleProposal,
      priceQuote: PriceQuoteBatch,
      reviewEvidence: ReviewEvidenceBatch,
    ) => collaboration.delegate({
      delegatedBy: "lead",
      role: "critic",
      capability: "independent_decision_audit",
      parentTaskId,
      execute: async (task) => {
        collaboration.consumeModelCall("critic");
        return roles.run<{
          plan: RetrievalPlan;
          slate: CandidateEnvelope[];
          bundle: BundleProposal;
          priceQuote: PriceQuoteBatch;
          reviewEvidence: ReviewEvidenceBatch;
        }, Critique>({
          role: "critic",
          runId,
          parentTaskId: task.taskId,
          artifactType: "critique",
          status: "verified",
          input: { plan, slate, bundle, priceQuote, reviewEvidence },
          modelInput: {
            plan,
            slate: slate.map((item) => ({
              skuId: item.product.skuId,
              category: item.product.category,
              score: item.normalizedScore,
              sponsored: item.sponsored,
            })),
            bundle,
            quotes: priceQuote.quotes,
            reviews: reviewEvidence.products,
            deterministicAudit: auditProposal(plan, slate, bundle, priceQuote, reviewEvidence, this.#domain),
          },
          proposalContract: ROLE_PROPOSAL_CONTRACTS.critique,
          systemPrompt: ROLE_SYSTEM_PROMPTS.critic,
          execute: (input, proposal) => {
            const deterministic = auditProposal(
              input.plan,
              input.slate,
              input.bundle,
              input.priceQuote,
              input.reviewEvidence,
              this.#domain,
            );
            const confidences = input.reviewEvidence.products.flatMap((item) =>
              item.aspects.map((aspect) => aspect.confidence)
            );
            return resolveCritique(deterministic, proposal, {
              averageReviewConfidence: confidences.length === 0
                ? 0
                : confidences.reduce((sum, value) => sum + value, 0) / confidences.length,
              useCaseMatched: input.plan.requirements.useCases.length === 0 ||
                input.plan.requirements.useCases.some((useCase) =>
                  input.bundle.items.some((item) =>
                    item.product.tags.some((tag) => tag.includes(useCase) || useCase.includes(tag))
                  )
                ),
              compatibilityUnknown: input.bundle.compatibility.some((item) => item.status === "unknown"),
            });
          },
          fallback: (input) => auditProposal(
            input.plan,
            input.slate,
            input.bundle,
            input.priceQuote,
            input.reviewEvidence,
            this.#domain,
          ),
        });
      },
    });

    let evidence = await buildDecisionEvidence(planArtifact.artifactId);
    let bundle = evidence.bundle;
    let priceQuote = evidence.priceQuote;
    let reviewEvidence = evidence.reviewEvidence;
    let critiqueArtifact = await runModelCritic(
      evidence.parentTaskId,
      bundle,
      priceQuote,
      reviewEvidence,
    );
    let critique = critiqueArtifact.payload;
    if (critique.verdict === "vetoed") critiqueArtifact.status = "vetoed";

    const missingCategories = shouldRequestRevision(plan, slate, bundle, critique, this.#domain);
    if (missingCategories.length > 0) {
      const revision: RevisionRequest = {
        attempt: 1,
        from: "critic",
        to: "recommendation",
        violations: critique.violations,
        missingCategories,
        instruction: "expand_candidates_without_relaxing_hard_constraints",
      };
      const revisionArtifact = publishTool({
        type: "revision_request",
        producer: "critic",
        parentTaskId: critiqueArtifact.artifactId,
        status: "verified",
        payload: revision,
      });
      trace.add("critic", "peer_delegated", {
        to: "recommendation",
        revisionArtifactId: revisionArtifact.artifactId,
        attempt: 1,
      });
      const revisedPlan: RetrievalPlan = {
        ...plan,
        candidateBudget: {
          ...plan.candidateBudget,
          recommendation: Math.min(20, Math.max(4, plan.candidateBudget.recommendation * 2)),
        },
      };
      const revisedRecommendation = await runChannel({
        channel: "recommendation",
        delegatedBy: "critic",
        parentTaskId: revisionArtifact.artifactId,
        peerCandidates: handoffArtifact?.payload.candidates ?? [],
        revision,
        useModel: false,
        planOverride: revisedPlan,
      });
      channelResults = channelResults.map((result) =>
        result.channel === "recommendation" ? revisedRecommendation.payload : result
      );
      slate = await fuse(revisedRecommendation.artifactId);
      evidence = await buildDecisionEvidence(revisedRecommendation.artifactId);
      bundle = evidence.bundle;
      priceQuote = evidence.priceQuote;
      reviewEvidence = evidence.reviewEvidence;
      critique = auditProposal(plan, slate, bundle, priceQuote, reviewEvidence, this.#domain);
      critiqueArtifact = publishTool({
        type: "critique",
        producer: "critic",
        parentTaskId: evidence.parentTaskId,
        status: critique.verdict === "approved" ? "verified" : "vetoed",
        payload: critique,
      });
      trace.add("critic", "deterministic_reaudit", { verdict: critique.verdict, attempt: 2 });
    }

    const messageText = renderReply(bundle, critique, priceQuote, reviewEvidence);
    const finalArtifact = await collaboration.delegate({
      delegatedBy: "critic",
      role: "lead",
      capability: "grounded_response_composition",
      parentTaskId: critiqueArtifact.artifactId,
      execute: async (task) => {
        collaboration.consumeModelCall("lead");
        return roles.run<{ message: string; critique: Critique }, { message: string; approved: boolean }>({
          role: "lead",
          runId,
          parentTaskId: task.taskId,
          artifactType: "final_decision",
          status: critique.verdict === "approved" ? "verified" : "vetoed",
          input: { message: messageText, critique },
          proposalContract: ROLE_PROPOSAL_CONTRACTS.finalDecision,
          systemPrompt: ROLE_SYSTEM_PROMPTS.lead,
          execute: ({ message: authoritativeMessage, critique: authoritativeCritique }, proposal) =>
            resolveFinalDecision(
              authoritativeMessage,
              authoritativeCritique.verdict === "approved",
              [
                ...bundle.items.flatMap((item) => [
                  item.product.price,
                  item.product.stock,
                  ...numbersInText(item.product.title),
                  ...(item.product.maxPowerWatts === undefined ? [] : [item.product.maxPowerWatts]),
                ]),
                bundle.totalPrice,
                bundle.items.length,
                ...(bundle.budgetMax === null ? [] : [bundle.budgetMax]),
                ...reviewEvidence.products.flatMap((item) => [
                  item.sampleSize,
                  ...item.aspects.flatMap((aspect) => [
                    aspect.mentionCount,
                    aspect.sentiment,
                    aspect.confidence,
                    ...numbersInText(aspect.summary),
                  ]),
                ]),
              ],
              proposal,
            ),
          fallback: ({ message: authoritativeMessage, critique: authoritativeCritique }) => ({
            message: authoritativeMessage,
            approved: authoritativeCritique.verdict === "approved",
          }),
        });
      },
    });
    trace.add("lead", "run_completed", {
      verdict: critique.verdict,
      collaborationTasks: collaboration.tasks.length,
      delegationProposals: collaboration.proposals.length,
      modelCalls: collaboration.modelCalls,
    });

    return {
      runId,
      domainPackId: this.#domain.packId,
      workflowId: this.#workflow.id,
      message: finalArtifact.payload.message,
      plan,
      slate,
      bundle,
      priceQuote,
      reviewEvidence,
      critique,
      artifactIds: artifacts.list(runId).map((artifact) => artifact.artifactId),
      trace: trace.records,
      runtime: summarizeRuntime(this.#runtime, trace.records),
    };
  }
}
