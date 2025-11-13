package com.buysense.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.buysense.domain.Candidate;
import com.buysense.domain.DecisionResult;
import com.buysense.domain.Product;
import com.buysense.domain.Requirement;
import com.buysense.platform.CommerceDomainPack;
import com.buysense.platform.DomainPackRegistry;
import com.buysense.retail.ReviewEvidenceGateway;
import com.buysense.sar.data.JavaRetailDataPlane;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Search-Ads-Recommendation lead orchestration for BuySense.
 * Every request follows the same bounded collaboration chain.
 */
@Service
public class SearchAdsRecsLeadService {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern POWER = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:W|瓦)");
    private static final List<String> CANONICAL_CHANNELS =
            List.of("search", "recommendation", "ads");
    private static final Set<String> CHANNELS = Set.copyOf(CANONICAL_CHANNELS);
    private static final ExecutionObserver NOOP_OBSERVER = trace -> { };

    private final IntentParser parser;
    private final DomainPackRegistry domains;
    private final JavaRetailDataPlane dataPlane;
    private final ReviewEvidenceGateway reviewEvidence;
    private final ModelPortAgentBridge model;
    private final ObjectMapper mapper;
    private final Executor collaborationExecutor;

    public SearchAdsRecsLeadService(
            IntentParser parser,
            DomainPackRegistry domains,
            JavaRetailDataPlane dataPlane,
            ReviewEvidenceGateway reviewEvidence,
            ModelPortAgentBridge model,
            ObjectMapper mapper,
            @Qualifier("collaborationExecutor") Executor collaborationExecutor
    ) {
        this.parser = parser;
        this.domains = domains;
        this.dataPlane = dataPlane;
        this.reviewEvidence = reviewEvidence;
        this.model = model;
        this.mapper = mapper;
        this.collaborationExecutor = collaborationExecutor;
    }

    public Execution decide(
            String runId,
            String message,
            String domainPackId,
            DiscoveryContext discoveryContext
    ) {
        return decide(runId, message, domainPackId, discoveryContext, () -> false);
    }

    public Execution decide(
            String runId,
            String message,
            String domainPackId,
            DiscoveryContext discoveryContext,
            BooleanSupplier cancellationRequested
    ) {
        return decide(
                runId, message, domainPackId, discoveryContext,
                cancellationRequested, NOOP_OBSERVER);
    }

    public Execution decide(
            String runId,
            String message,
            String domainPackId,
            DiscoveryContext discoveryContext,
            BooleanSupplier cancellationRequested,
            ExecutionObserver observer
    ) {
        java.util.Objects.requireNonNull(observer, "observer");
        CommerceDomainPack domain = domains.require(domainPackId);
        List<DecisionResult.TraceStep> trace = java.util.Collections.synchronizedList(new ArrayList<>());
        BoundedCollaborationCoordinator.TraceSink traceSink = (role, event, detail) -> {
            DecisionResult.TraceStep step =
                    new DecisionResult.TraceStep(role, event, detail);
            trace.add(step);
            observer.onTrace(step);
        };
        var workflowGraph = domains.requireWorkflowGraph(domain.workflowId());
        var defaults = BoundedCollaborationCoordinator.DEFAULT_POLICY;
        var policy = new BoundedCollaborationCoordinator.Policy(
                defaults.maxTasks(), defaults.maxDelegationProposals(), defaults.maxDepth(),
                defaults.maxConcurrent(), model.maxCallsPerRun(),
                defaults.maxRevisionAttempts(), defaults.deadlineMs());
        BoundedCollaborationCoordinator coordinator =
                new BoundedCollaborationCoordinator(
                        runId,
                        traceSink,
                        policy,
                        workflowGraph.allowedDelegations(),
                        workflowGraph.roleCapabilities(),
                        cancellationRequested);
        List<ModelPortAgentBridge.RoleCall> roleCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        ArtifactStore artifacts = new ArtifactStore(runId);
        BoundedRoleAgent roleAgent =
                new BoundedRoleAgent(model, mapper, artifacts, coordinator, traceSink);

        traceSink.add("lead", "run_started", Map.of(
                "messageChars", message.length(),
                "domainPackId", domain.packId(),
                "workflowId", domain.workflowId(),
                "capabilityProfileId", domain.capabilityProfileId()));

        PlanExecution planExecution = coordinator.delegate(
                "lead", "intent_router", "understand_and_route", runId, 1, 0,
                task -> resolvePlan(
                        runId, message, domain, discoveryContext, coordinator,
                        roleAgent, roleCalls, task.taskId()));
        Plan plan = planExecution.plan();
        AgentArtifact planArtifact = planExecution.artifact();
        if (!plan.requirement().clarificationQuestions().isEmpty()) {
            String question = String.join(" ", plan.requirement().clarificationQuestions())
                    + " 补充后请重新发送完整选购需求。";
            traceSink.add("intent_router", "clarification_required", Map.of("questions",
                    plan.requirement().clarificationQuestions()));
            publishToolArtifact(artifacts, traceSink, "clarification", "intent_router",
                    planArtifact.artifactId(), AgentArtifact.Status.VETOED, Map.of("message", question));
            Audit unclear = new Audit(false, List.of("requirements_unclear"), Map.of(),
                    "unavailable", question);
            DecisionResult result = new DecisionResult(plan.requirement(), List.of(), List.of(),
                    List.copyOf(trace), Map.of("clarificationRequired", true, "criticVerdict", "vetoed",
                            "criticViolations", unclear.violations()),
                    runtime(plan, question, unclear, false, trace));
            return new Execution(result, List.copyOf(roleCalls), coordinator.tasks(),
                    coordinator.proposals(), false, artifacts.list());
        }

        FutureTask<ChannelResult> searchFuture = async(() -> runChannel(
                coordinator, artifacts, roleAgent, roleCalls, traceSink, plan, "search", "lead", planArtifact.artifactId(),
                List.of(), false, true));
        FutureTask<ChannelResult> adsFuture = plan.channels().contains("ads")
                ? async(() -> runChannel(
                        coordinator, artifacts, roleAgent, roleCalls, traceSink, plan, "ads", "lead", planArtifact.artifactId(),
                        List.of(), false, true))
                : completedTask(null);

        ChannelResult search = join(searchFuture, coordinator);
        List<JsonNode> primaryProducts = search.items().stream()
                .filter(item -> item.path("category").asText().equals(domain.primaryCategory()))
                .toList();

        BoundedCollaborationCoordinator.DelegationProposal approvedHandoff = coordinator.takeApprovedProposal(
                "search", "recommendation", "recommendation_strategy_and_retrieval");
        AgentArtifact handoffArtifact = null;
        if (plan.channels().contains("recommendation")) {
            handoffArtifact = publishToolArtifact(artifacts, traceSink,
                    "peer_handoff",
                    "search",
                    search.artifactId(),
                    AgentArtifact.Status.VERIFIED,
                    Map.of(
                            "from", "search",
                            "to", "recommendation",
                            "purpose", "primary_product_grounding",
                            "candidateCount", primaryProducts.size(),
                            "candidates", primaryProducts));
            traceSink.add(
                    approvedHandoff == null ? "lead" : "search",
                    approvedHandoff == null ? "handoff_fallback_scheduled" : "peer_delegated",
                    Map.of(
                            "to", "recommendation",
                            "candidateCount", primaryProducts.size(),
                            "sourceArtifactId", handoffArtifact.artifactId(),
                            "schedulingMode", approvedHandoff == null
                                    ? "retrieval_plan_fallback"
                                    : "model_proposed_coordinator_approved"));
        }

        ChannelResult recommendation = plan.channels().contains("recommendation")
                ? runChannel(
                        coordinator, artifacts, roleAgent, roleCalls, traceSink, plan, "recommendation",
                        approvedHandoff == null ? "lead" : "search", handoffArtifact.artifactId(),
                        primaryProducts, false, true)
                : null;
        ChannelResult ads = join(adsFuture, coordinator);

        List<ChannelResult> channels = orderedChannels(plan, search, recommendation, ads);
        DecisionBuild decision = buildDecision(
                coordinator, artifacts, plan, channels, domain, planArtifact.artifactId(), trace, traceSink);

        Audit deterministicAudit = audit(plan, channels, decision, domain);
        DecisionBuild initialDecision = decision;
        CriticExecution criticExecution = coordinator.delegate(
                "lead", "critic", "independent_decision_audit",
                initialDecision.parentTaskId(), 1, 0,
                task -> {
                    ObjectNode modelInput =
                            criticInput(plan, initialDecision, deterministicAudit);
                    ObjectNode authoritative =
                            criticAuthoritativeInput(plan, initialDecision);
                    java.util.concurrent.atomic.AtomicReference<Audit> reviewed =
                            new java.util.concurrent.atomic.AtomicReference<>();
                    BoundedRoleAgent.Result agentResult = roleAgent.run(
                            new BoundedRoleAgent.Spec(
                                    "critic",
                                    runId,
                                    task.taskId(),
                                    "critique",
                                    AgentArtifact.Status.VERIFIED,
                                    authoritative,
                                    modelInput,
                                    "{\"verdict\":\"approved|vetoed\","
                                            + "\"additionalViolations\":["
                                            + "\"insufficient_review_confidence|"
                                            + "weak_use_case_match|ambiguous_compatibility\"],"
                                            + "\"rationale\":\"grounded audit summary\"}",
                                    "你是独立 Critic Agent。检查预算、库存、Quote、兼容、证据、广告披露与类目覆盖。"
                                            + "你可以更严格，但绝不能覆盖确定性失败。",
                                    (input, proposal) -> {
                                        CriticResolution resolution = resolveCritic(
                                                deterministicAudit, proposal, initialDecision);
                                        reviewed.set(resolution.audit());
                                        JsonNode payload = auditPayload(resolution.audit(), 1);
                                        return resolution.corrections().isEmpty()
                                                ? BoundedRoleAgent.Resolution.accepted(payload)
                                                : BoundedRoleAgent.Resolution.corrected(
                                                        payload, resolution.corrections());
                                    },
                                    input -> {
                                        reviewed.set(deterministicAudit);
                                        return auditPayload(deterministicAudit, 1);
                                    }));
                    roleCalls.add(agentResult.roleCall());
                    return new CriticExecution(reviewed.get(), agentResult.artifact());
                });
        Audit audit = criticExecution.audit();
        DecisionResult withAudit = withAudit(decision.result(), audit);
        AgentArtifact critiqueArtifact = criticExecution.artifact();
        if (!audit.approved()) {
            critiqueArtifact = artifacts.updateStatus(
                    critiqueArtifact.artifactId(), AgentArtifact.Status.VETOED);
        }
        boolean revised = false;
        Set<String> missing = missingRequestedCategories(plan, domain, decision.selectedItems());
        if (audit.violations().contains("requested_category_coverage")
                && plan.channels().contains("recommendation")
                && !missing.isEmpty()
                && hasFeasibleMissingCategory(
                        channels.stream().flatMap(channel -> channel.items().stream()).toList(),
                        missing,
                        plan.budgetMax(),
                        decision.result().bundles().stream()
                                .findFirst()
                                .map(DecisionResult.BundleProposal::totalPrice)
                                .orElse(BigDecimal.ZERO))) {
            revised = true;
            AgentArtifact revisionArtifact = publishToolArtifact(artifacts, traceSink,
                    "revision_request",
                    "critic",
                    critiqueArtifact.artifactId(),
                    AgentArtifact.Status.VERIFIED,
                    Map.of(
                            "attempt", 1,
                            "from", "critic",
                            "to", "recommendation",
                            "violations", audit.violations(),
                            "missingCategories", List.copyOf(missing),
                            "instruction", "expand_candidates_without_relaxing_hard_constraints"));
            traceSink.add("critic", "peer_delegated", Map.of(
                    "to", "recommendation", "attempt", 1,
                    "revisionArtifactId", revisionArtifact.artifactId(),
                    "instruction", "expand_candidates_without_relaxing_hard_constraints"));
            Plan expanded = plan.withRecommendationBudget(
                    Math.min(20, Math.max(4, plan.candidateBudget().get("recommendation") * 2)));
            ChannelResult revisedRecommendation = runChannel(
                    coordinator, artifacts, roleAgent, roleCalls, traceSink, expanded, "recommendation", "critic",
                    revisionArtifact.artifactId(),
                    primaryProducts, true, false);
            channels = channels.stream()
                    .map(item -> item.channel().equals("recommendation") ? revisedRecommendation : item)
                    .toList();
            int expandedFusionLimit = Math.min(50, Math.max(12, channels.stream()
                    .mapToInt(channel -> channel.items().size())
                    .sum()));
            decision = buildDecision(
                    coordinator, artifacts, plan, channels, domain,
                    revisedRecommendation.artifactId(), trace, traceSink,
                    expandedFusionLimit);
            ObjectNode priorReview = mapper.createObjectNode().put("verdict", audit.modelVerdict());
            priorReview.set("additionalViolations", mapper.valueToTree(audit.violations()));
            priorReview.put("rationale", audit.rationale());
            Audit repairedAudit = audit(plan, channels, decision, domain);
            Audit rechecked = resolveCritic(repairedAudit, priorReview, decision).audit();
            traceSink.add("critic", "previous_violations_rechecked", Map.of(
                    "previous", audit.violations(), "remaining", rechecked.violations()));
            audit = rechecked;
            withAudit = withAudit(decision.result(), audit);
            critiqueArtifact = publishToolArtifact(artifacts, traceSink,
                    "critique",
                    "critic",
                    decision.parentTaskId(),
                    audit.approved() ? AgentArtifact.Status.VERIFIED : AgentArtifact.Status.VETOED,
                    Map.of(
                            "verdict", audit.approved() ? "approved" : "vetoed",
                            "violations", audit.violations(),
                            "checks", audit.checks(),
                            "attempt", 2));
            traceSink.add("critic", "deterministic_reaudit", Map.of(
                    "verdict", audit.approved() ? "approved" : "vetoed", "attempt", 2));
        }

        String deterministicMessage = renderMessage(decision, audit, plan);
        boolean finalApproved = audit.approved();
        Audit finalAudit = audit;
        boolean revisionApplied = revised;
        DecisionBuild finalDecision = decision;
        java.util.concurrent.atomic.AtomicReference<String> finalText =
                new java.util.concurrent.atomic.AtomicReference<>();
        coordinator.delegate(
                "critic", "lead", "grounded_response_composition",
                critiqueArtifact.artifactId(), 1, 0,
                task -> {
                    ObjectNode authoritative = mapper.createObjectNode();
                    authoritative.put("message", deterministicMessage);
                    authoritative.put("approved", finalApproved);
                    authoritative.set("critique", auditPayload(finalAudit, revisionApplied ? 2 : 1));
                    BoundedRoleAgent.Result agentResult = roleAgent.run(
                            new BoundedRoleAgent.Spec(
                                    "lead",
                                    runId,
                                    task.taskId(),
                                    "final_decision",
                                    finalApproved
                                            ? AgentArtifact.Status.VERIFIED
                                            : AgentArtifact.Status.VETOED,
                                    authoritative,
                                    authoritative,
                                    "{\"message\":\"concise Chinese buyer-facing answer using "
                                            + "only supplied items, prices and evidence\","
                                            + "\"approved\":boolean}",
                                    "你是搜广推 Lead Agent，负责把已核验的计划、商品、价格、兼容和评论证据"
                                            + "组织成简洁中文结论。只能引用上下文事实，不得声称已下单或已支付。",
                                    (input, proposal) -> {
                                        FinalResolution resolution = resolveFinalProposal(
                                                deterministicMessage,
                                                finalApproved,
                                                finalDecision,
                                                proposal);
                                        finalText.set(resolution.message());
                                        JsonNode payload = finalDecisionPayload(
                                                resolution.message(),
                                                finalApproved,
                                                finalDecision);
                                        return resolution.corrections().isEmpty()
                                                ? BoundedRoleAgent.Resolution.accepted(payload)
                                                : BoundedRoleAgent.Resolution.corrected(
                                                        payload, resolution.corrections());
                                    },
                                    input -> {
                                        finalText.set(deterministicMessage);
                                        return finalDecisionPayload(
                                                deterministicMessage,
                                                finalApproved,
                                                finalDecision);
                                    }));
                    roleCalls.add(agentResult.roleCall());
                    return agentResult.artifact();
                });
        String finalMessage = finalText.get();
        DecisionResult.ModelRuntime runtime = runtime(
                plan, finalMessage, audit, revised, trace);
        DecisionResult result = withAudit.withRuntime(runtime);
        traceSink.add("lead", "run_completed", Map.of(
                "verdict", audit.approved() ? "approved" : "vetoed",
                "collaborationTasks", coordinator.tasks().size(),
                "delegationProposals", coordinator.proposals().size(),
                "modelCalls", coordinator.modelCalls()));
        result = new DecisionResult(
                result.requirement(), result.slate(), result.bundles(),
                List.copyOf(trace), result.metrics(), result.runtime());
        return new Execution(
                result,
                List.copyOf(roleCalls),
                coordinator.tasks(),
                coordinator.proposals(),
                revised,
                artifacts.list());
    }

    private PlanExecution resolvePlan(
            String runId,
            String message,
            CommerceDomainPack domain,
            DiscoveryContext discoveryContext,
            BoundedCollaborationCoordinator coordinator,
            BoundedRoleAgent roleAgent,
            List<ModelPortAgentBridge.RoleCall> calls,
            String parentTaskId
    ) {
        Requirement baseline = parser.parse(message, domain.packId());
        ObjectNode authoritative = mapper.createObjectNode().put("message", message);
        authoritative.set("baseline", mapper.valueToTree(baseline));
        authoritative.set("supportedCategories", mapper.valueToTree(domain.categories()));
        authoritative.set("supportedBrands", mapper.valueToTree(domain.brands()));
        java.util.concurrent.atomic.AtomicReference<Plan> selected =
                new java.util.concurrent.atomic.AtomicReference<>();

        BoundedRoleAgent.Result modelResult = roleAgent.run(new BoundedRoleAgent.Spec(
                "intent_router",
                runId,
                parentTaskId,
                "retrieval_plan",
                AgentArtifact.Status.VERIFIED,
                authoritative,
                null,
                retrievalPlanContract(domain),
                "你是电商意图与检索路由 Agent。理解自然语言需求并选择必要的 Search、Recommendation、"
                        + "Ads 通道；Search 是保底通道，套装必须保留 Recommendation，Ads 仅在用户"
                        + "未退出且确有必要时选择。预算和广告退出属于硬约束，不得放宽。"
                        + "先提取需求；补充基线未识别的预算或品类时，必须在 evidence 中引用用户原文。"
                        + "没有预算就返回 null，不把型号当预算；不要把拒绝品牌当偏好。"
                        + "单品不必推荐配件，无法识别的品类不强行套用默认商品。",
                (input, proposal) -> {
                    PlanResolution resolution = resolvePlanProposal(
                            runId, message, domain, discoveryContext, baseline, proposal);
                    selected.set(resolution.plan());
                    JsonNode payload = planPayload(resolution.plan());
                    return resolution.corrections().isEmpty()
                            ? BoundedRoleAgent.Resolution.accepted(payload)
                            : BoundedRoleAgent.Resolution.corrected(
                                    payload, resolution.corrections());
                },
                input -> {
                    PlanResolution resolution = resolvePlanProposal(
                            runId, message, domain, discoveryContext, baseline, null);
                    selected.set(resolution.plan());
                    return planPayload(resolution.plan());
                }));
        calls.add(modelResult.roleCall());
        return new PlanExecution(selected.get(), modelResult.artifact());
    }

    PlanResolution resolvePlanProposal(
            String runId,
            String message,
            CommerceDomainPack domain,
            DiscoveryContext discoveryContext,
            Requirement parsedBaseline,
            JsonNode proposal
    ) {
        Requirement baseline = parser.resolveGrounded(parsedBaseline, proposal, domain);
        boolean proposed = proposal != null && proposal.isObject();
        List<String> corrections = new ArrayList<>();
        if (proposal != null && !proposal.isObject()) {
            corrections.add("proposal_not_object");
        }

        String baselineIntent = baseline.bundleRequested()
                ? "bundle"
                : inferIntent(baseline.originalQuery(), domain);
        List<String> baselineChannels = new ArrayList<>(List.of("search"));
        if (baseline.bundleRequested()) baselineChannels.add("recommendation");
        if (baseline.sponsoredAllowed()) baselineChannels.add("ads");
        Map<String, Integer> baselineBudgets = new LinkedHashMap<>();
        baselineBudgets.put("search", 8);
        baselineBudgets.put("recommendation", 8);
        baselineBudgets.put("ads", 4);
        String baselineReason = "intent=" + baselineIntent + "; categories="
                + String.join(",", baseline.requiredCategories()) + "; channels="
                + String.join(",", baselineChannels);

        JsonNode rawIntent = proposed ? proposal.get("intent") : null;
        boolean validProposedIntent = rawIntent != null && rawIntent.isTextual()
                && Set.of("precise", "catalog", "exploratory", "bundle", "compare")
                        .contains(rawIntent.asText());
        String proposedIntent = validProposedIntent ? rawIntent.asText() : baselineIntent;
        String intent = baselineIntent;
        if (rawIntent != null && !validProposedIntent) {
            corrections.add("invalid_intent");
        } else if (!proposedIntent.equals(baselineIntent)) {
            corrections.add("explicit_intent_preserved");
        }

        String proposedQuery = proposed ? boundedString(proposal.get("query"), 240) : null;
        String query = baseline.originalQuery();
        if (proposedQuery != null && !query.equals(proposedQuery)) {
            corrections.add("original_query_preserved");
        }
        if (proposed && proposal.has("query") && proposedQuery == null) {
            corrections.add("invalid_query");
        }

        List<String> proposedCategories = proposed
                ? boundedStringArray(proposal.get("requestedCategories"), 20) : null;
        Set<String> categoryContract = domain.categories().stream()
                .map(CommerceDomainPack.CategoryDefinition::id)
                .collect(Collectors.toSet());
        List<String> validCategories = proposedCategories == null ? List.of()
                : proposedCategories.stream().filter(categoryContract::contains).toList();
        if (proposedCategories != null && validCategories.size() != proposedCategories.size()) {
            corrections.add("unsupported_category_removed");
        }
        if (!validCategories.isEmpty()
                && (validCategories.size() != baseline.requiredCategories().size()
                || validCategories.stream().anyMatch(
                        value -> !baseline.requiredCategories().contains(value)))) {
            corrections.add("explicit_category_scope_preserved");
        }

        List<String> proposedUseCases = proposed
                ? boundedStringArray(proposal.get("useCases"), 20) : null;
        List<String> validUseCases = proposedUseCases == null ? List.of()
                : proposedUseCases.stream().filter(domain.useCases()::contains)
                        .filter(useCase -> baseline.useCases().contains(useCase)
                                || IntentParser.groundedSpan(baseline.originalQuery(),
                                        proposal.path("evidence").path("useCases").path(useCase)) != null)
                        .toList();
        LinkedHashSet<String> useCases = new LinkedHashSet<>(baseline.useCases());
        useCases.addAll(validUseCases);
        if (proposedUseCases != null && validUseCases.size() != proposedUseCases.size()) {
            corrections.add("unsupported_use_case_removed");
        }
        if (proposedUseCases != null
                && baseline.useCases().stream().anyMatch(
                        value -> !validUseCases.contains(value))) {
            corrections.add("explicit_use_case_preserved");
        }

        List<String> proposedBrands = proposed
                ? boundedStringArray(proposal.get("preferredBrands"), 8) : null;
        if (proposed && proposal.has("preferredBrands") && proposedBrands == null) {
            corrections.add("invalid_preferred_brands");
        } else if (proposedBrands != null
                && (proposedBrands.size() != baseline.preferredBrands().size()
                || proposedBrands.stream().anyMatch(
                        value -> !baseline.preferredBrands().contains(value)))) {
            corrections.add("ungrounded_brand_preference_removed");
        }

        boolean proposedSponsored = proposed && proposal.path("sponsoredAllowed").isBoolean()
                ? proposal.path("sponsoredAllowed").asBoolean()
                : baseline.sponsoredAllowed();
        if (proposedSponsored != baseline.sponsoredAllowed()) {
            corrections.add(baseline.sponsoredAllowed()
                    ? "sponsored_policy_preserved" : "ad_opt_out_enforced");
        }

        List<Requirement.Constraint> constraints = new ArrayList<>(baseline.constraints());
        int inferredIndex = 0;
        for (String useCase : validUseCases) {
            if (baseline.useCases().contains(useCase)) continue;
            constraints.add(new Requirement.Constraint(
                    "constraint-inferred-use-case-" + inferredIndex++,
                    "useCases",
                    useCase,
                    Requirement.ConstraintSource.INFERRED_MODEL,
                    Requirement.ConstraintStrength.SOFT,
                    0.65,
                    "turn-current",
                    Requirement.ConstraintStatus.ACTIVE));
        }
        Requirement effective = new Requirement(
                baseline.originalQuery(),
                query,
                baseline.budget(),
                baseline.requiredCategories(),
                baseline.preferredBrands(),
                List.copyOf(useCases),
                List.copyOf(constraints),
                baseline.sponsoredAllowed(),
                baseline.bundleRequested());

        List<String> proposedChannels = proposed
                ? boundedStringArray(proposal.get("channels"), 3) : null;
        List<String> validChannels = proposedChannels == null ? List.of()
                : proposedChannels.stream().filter(CHANNELS::contains).toList();
        LinkedHashSet<String> channelSet = new LinkedHashSet<>(
                proposedChannels == null ? baselineChannels : validChannels);
        channelSet.add("search");
        if (baselineIntent.equals("bundle") && !channelSet.contains("recommendation")) {
            channelSet.add("recommendation");
            corrections.add("bundle_recommendation_channel_restored");
        }
        if (!effective.sponsoredAllowed()) channelSet.remove("ads");
        if (proposedChannels != null && validChannels.size() != proposedChannels.size()) {
            corrections.add("unsupported_channel_removed");
        }
        if (!effective.sponsoredAllowed() && validChannels.contains("ads")) {
            corrections.add("ads_channel_removed");
        }
        List<String> channels = CANONICAL_CHANNELS.stream()
                .filter(channelSet::contains)
                .toList();

        Map<String, Integer> budgets = new LinkedHashMap<>(baselineBudgets);
        JsonNode proposedBudgets = proposed && proposal.path("candidateBudget").isObject()
                ? proposal.path("candidateBudget") : null;
        for (String channel : CANONICAL_CHANNELS) {
            JsonNode value = proposedBudgets == null ? null : proposedBudgets.get(channel);
            if (value == null) continue;
            if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                corrections.add(channel + "_candidate_budget_invalid");
                continue;
            }
            double original = value.asDouble();
            int limit = channel.equals("ads") ? 4 : 12;
            long rounded = Math.round(original);
            int bounded = (int) Math.max(1L, Math.min((long) limit, rounded));
            budgets.put(channel, bounded);
            if (Double.compare(bounded, original) != 0) {
                corrections.add(channel + "_candidate_budget_clamped");
            }
        }

        String proposedReason = proposed ? boundedString(proposal.get("reason"), 500) : null;
        String reason = proposedReason == null ? baselineReason : proposedReason;
        if (proposed && proposal.has("reason") && proposedReason == null) {
            corrections.add("invalid_reason");
        }

        Plan plan = new Plan(
                runId,
                domain.packId(),
                effective,
                intent,
                channels,
                Map.copyOf(budgets),
                reason,
                discoveryContext);
        return new PlanResolution(plan, List.copyOf(new LinkedHashSet<>(corrections)));
    }

    private ObjectNode planPayload(Plan plan) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("originalQuery", plan.requirement().originalQuery());
        payload.put("query", plan.requirement().retrievalQuery());
        payload.put("intent", plan.intent());
        payload.set("requirements", mapper.valueToTree(plan.requirement()));
        payload.set("channels", mapper.valueToTree(plan.channels()));
        payload.set("candidateBudget", mapper.valueToTree(plan.candidateBudget()));
        payload.put("sponsoredAllowed", plan.requirement().sponsoredAllowed());
        payload.put("reason", plan.reason());
        payload.put("domainPackId", plan.domainPackId());
        return payload;
    }

    private String retrievalPlanContract(CommerceDomainPack domain) {
        String categories = domain.categories().stream()
                .map(CommerceDomainPack.CategoryDefinition::id)
                .collect(Collectors.joining("|"));
        String useCases = String.join("|", domain.useCases());
        return "{\"intent\":\"precise|catalog|exploratory|bundle|compare\","
                + "\"query\":\"original user query\","
                + "\"budgetMax\":number|null,"
                + "\"requestedCategories\":[\"" + categories + "\"],"
                + "\"preferredBrands\":[\"brand\"],"
                + "\"excludedBrands\":[\"brand\"],"
                + "\"useCases\":[\"" + useCases + "\"],"
                + "\"channels\":[\"search|recommendation|ads\"],"
                + "\"sponsoredAllowed\":boolean,"
                + "\"candidateBudget\":{\"search\":1-12,"
                + "\"recommendation\":1-12,\"ads\":1-4},"
                + "\"evidence\":{\"budgetMax\":\"exact original phrase with budget\","
                + "\"requestedCategories\":{\"categoryId\":\"exact original phrase\"},"
                + "\"excludedBrands\":{\"brand\":\"exact rejection phrase\"},"
                + "\"useCases\":{\"useCase\":\"exact original phrase\"},"
                + "\"sponsoredAllowed\":\"exact advertising rejection phrase\"},"
                + "\"reason\":\"short grounded reason\"}";
    }

    private AgentArtifact publishToolArtifact(
            ArtifactStore artifacts,
            BoundedCollaborationCoordinator.TraceSink traceSink,
            String type,
            String producer,
            String parentTaskId,
            AgentArtifact.Status status,
            Map<String, Object> payload
    ) {
        AgentArtifact artifact = artifacts.publish(
                type, producer, parentTaskId, status, payload);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("artifactId", artifact.artifactId());
        detail.put("artifactType", artifact.type());
        detail.put("status", artifact.status().value());
        detail.put("artifactPayload", artifact.payload());
        detail.put("createdAt", artifact.createdAt().toString());
        if (artifact.parentTaskId() != null) {
            detail.put("parentTaskId", artifact.parentTaskId());
        }
        traceSink.add(
                producer, "tool_artifact_published", Map.copyOf(detail));
        return artifact;
    }

    private ChannelResult runChannel(
            BoundedCollaborationCoordinator coordinator,
            ArtifactStore artifacts,
            BoundedRoleAgent roleAgent,
            List<ModelPortAgentBridge.RoleCall> calls,
            BoundedCollaborationCoordinator.TraceSink traceSink,
            Plan plan,
            String channel,
            String delegatedBy,
            String parentTaskId,
            List<JsonNode> primaryProducts,
            boolean revision,
            boolean useModel
    ) {
        return coordinator.delegate(
                delegatedBy,
                channel,
                channel + "_strategy_and_retrieval",
                parentTaskId,
                1,
                revision ? 1 : 0,
                task -> {
                    ObjectNode request = discoveryRequest(plan, channel, primaryProducts);
                    ObjectNode responseValue;
                    boolean retrievalFailed;
                    try {
                        Map<String, Object> raw =
                                dataPlane.discovery(plan.domainPackId(), channel, request);
                        responseValue = mapper.valueToTree(raw);
                        retrievalFailed = false;
                    } catch (RuntimeException error) {
                        coordinator.assertActive();
                        if (error instanceof CancellationException) throw error;
                        if (channel.equals("search")) throw error;
                        retrievalFailed = true;
                        traceSink.add(channel, "degraded", Map.of(
                                "taskId", task.taskId(),
                                "tier", channel.equals("ads")
                                        ? "ads_skipped" : "recommendation_unavailable",
                                "errorType", error.getClass().getSimpleName()));
                        responseValue = emptyChannel(channel);
                    }
                    ObjectNode response = responseValue;
                    JsonNode provenance = response.path("data_source");
                    response.withArray("items").forEach(item -> {
                        if (item instanceof ObjectNode object && provenance.isObject()) {
                            object.set("data_source", provenance.deepCopy());
                        }
                    });
                    Map<String, Object> dataTrace = new LinkedHashMap<>();
                    dataTrace.put("taskId", task.taskId());
                    dataTrace.put("resource", "catalog");
                    dataTrace.put("channel", channel);
                    dataTrace.put("candidateCount", response.path("items").size());
                    if (provenance.isObject()) {
                        dataTrace.put("source", provenance.path("source").asText());
                        dataTrace.put("sourceVersion", provenance.path("source_version").asText());
                        dataTrace.put("providerId", provenance.path("provider_id").asText());
                    }
                    traceSink.add(channel, "data_plane_result", Map.copyOf(dataTrace));

                    if (!useModel) {
                        List<JsonNode> items = array(response.path("items"));
                        AgentArtifact artifact = publishToolArtifact(artifacts, traceSink,
                                "candidate_set", channel, task.taskId(),
                                AgentArtifact.Status.VERIFIED,
                                channelArtifactPayload(channel, response, revision, retrievalFailed));
                        return new ChannelResult(
                                channel, items, response, task.taskId(), artifact.artifactId());
                    }

                    ObjectNode authoritative = mapper.createObjectNode();
                    authoritative.set("plan", planPayload(plan));
                    authoritative.set("result", channelArtifactNode(channel, response));
                    java.util.concurrent.atomic.AtomicReference<ObjectNode> ranked =
                            new java.util.concurrent.atomic.AtomicReference<>();
                    BoundedRoleAgent.Result agentResult = roleAgent.run(new BoundedRoleAgent.Spec(
                            channel,
                            plan.runId(),
                            task.taskId(),
                            "candidate_set",
                            AgentArtifact.Status.VERIFIED,
                            authoritative,
                            null,
                            "{\"rankedSkuIds\":[\"only SKU ids present in candidates\"],"
                                    + "\"rationaleBySku\":{\"sku-id\":"
                                    + "\"short reason grounded in candidate fields\"}}",
                            channelPrompt(channel),
                            (input, proposal) -> {
                                RankingResolution resolution =
                                        resolveModelRanking(response, proposal);
                                ranked.set(resolution.response());
                                JsonNode payload =
                                        channelArtifactNode(channel, resolution.response());
                                return resolution.corrections().isEmpty()
                                        ? BoundedRoleAgent.Resolution.accepted(payload)
                                        : BoundedRoleAgent.Resolution.corrected(
                                                payload, resolution.corrections());
                            },
                            input -> {
                                ObjectNode fallback = response.deepCopy();
                                ranked.set(fallback);
                                return channelArtifactNode(channel, fallback);
                            }));
                    calls.add(agentResult.roleCall());
                    ObjectNode output = ranked.get();
                    List<JsonNode> items = array(output.path("items"));
                    return new ChannelResult(
                            channel, items, output, task.taskId(),
                            agentResult.artifact().artifactId());
                });
    }

    private String channelPrompt(String channel) {
        return switch (channel) {
            case "search" -> "你是 Search Agent。只在给定搜索候选中排序，兼顾 Query、类目、用途、品牌和预算；"
                    + "不得新增 SKU。当计划包含 Recommendation 且搜索候选能提供主商品上下文时，应先用 "
                    + "request_handoff 请求 recommendation_strategy_and_retrieval，再发布排序产物。";
            case "recommendation" -> "你是 Recommendation Agent。结合 Search handoff 与套装需求，"
                    + "只在给定候选中排序；不得新增 SKU 或修改价格。";
            case "ads" -> "你是 Ads Agent。只在已通过相关性和库存过滤的赞助候选中排序；"
                    + "必须保留赞助披露，不得填充无关广告。";
            default -> throw new IllegalArgumentException("unknown channel: " + channel);
        };
    }

    private Map<String, Object> channelArtifactPayload(
            String channel,
            ObjectNode response,
            boolean revision,
            boolean retrievalFailed
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel);
        payload.put("candidateCount", response.path("items").size());
        payload.put("revisionAttempt", revision ? 1 : 0);
        payload.put("retrievalFailed", retrievalFailed);
        JsonNode dataSource = response.path("data_source");
        if (dataSource.isObject()) {
            payload.put("dataSource", mapper.convertValue(dataSource, Object.class));
        }
        payload.put("candidates", mapper.convertValue(
                response.path("items"), Object.class));
        return Map.copyOf(payload);
    }

    private ObjectNode channelArtifactNode(String channel, ObjectNode response) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("channel", channel);
        JsonNode dataSource = response.path("data_source");
        if (dataSource.isObject()) {
            payload.set("dataSource", dataSource.deepCopy());
        }
        payload.set("candidates", response.path("items").deepCopy());
        return payload;
    }

    private Map<String, Object> fusedArtifactPayload(ObjectNode fused) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("channel", "search");
        payload.set("candidates", fused.path("items").deepCopy());
        return jsonObjectPayload(payload);
    }

    private Map<String, Object> bundleArtifactPayload(
            ObjectNode optimized,
            List<JsonNode> items,
            Plan plan
    ) {
        return jsonObjectPayload(bundlePayloadNode(optimized, items, plan));
    }

    private ObjectNode bundlePayloadNode(
            ObjectNode optimized,
            List<JsonNode> items,
            Plan plan
    ) {
        ObjectNode payload = mapper.createObjectNode();
        ArrayNode payloadItems = payload.putArray("items");
        items.forEach(item -> payloadItems.add(item.deepCopy()));
        BigDecimal totalPrice = items.stream()
                .map(item -> item.path("price").decimalValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        payload.put("totalPrice", totalPrice);
        if (plan.budgetMax() == null) payload.putNull("budgetMax");
        else payload.put("budgetMax", plan.budgetMax());
        payload.put("withinBudget", plan.budgetMax() == null
                || totalPrice.compareTo(plan.budgetMax()) <= 0);

        JsonNode winner = optimized.path("bundles").path(0);
        payload.set("compatibility", winner.path("compatibility").isArray()
                ? winner.path("compatibility").deepCopy()
                : mapper.createArrayNode());
        payload.put("score", winner.path("score").asDouble(0));
        payload.put("optimization", "global_enumeration");
        ArrayNode alternatives = payload.putArray("alternatives");
        optimized.path("bundles").forEach(bundle -> {
            ObjectNode alternative = alternatives.addObject();
            alternative.set("skuIds", bundle.path("sku_ids").deepCopy());
            alternative.set("totalPrice", bundle.path("total_price").deepCopy());
            alternative.put("score", bundle.path("score").asDouble(0));
            alternative.put("sponsoredCount", bundle.path("sponsored_count").asInt(0));
        });
        return payload;
    }

    private Map<String, Object> jsonObjectPayload(ObjectNode value) {
        return mapper.convertValue(
                value,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }

    private Map<String, Object> quoteDataTrace(String taskId, ObjectNode quotes) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskId", taskId);
        detail.put("resource", "pricing");
        detail.put("quoteBatchId", quotes.path("quote_batch_id").asText());
        detail.put("quoteVersion", quotes.path("quote_version").asText());
        detail.put("quoteCount", quotes.path("quotes").size());
        appendDataSourceTrace(detail, quotes.path("data_source"));
        return Map.copyOf(detail);
    }

    private Map<String, Object> reviewDataTrace(String taskId, ObjectNode reviews) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskId", taskId);
        detail.put("resource", "reviews");
        detail.put("reviewSnapshotVersion",
                reviews.path("review_snapshot_version").asText());
        detail.put("productCount", reviews.path("products").size());
        detail.put("missingProductCount", reviews.path("missing_product_ids").size());
        appendDataSourceTrace(detail, reviews.path("data_source"));
        return Map.copyOf(detail);
    }

    private void appendDataSourceTrace(Map<String, Object> detail, JsonNode dataSource) {
        if (!dataSource.isObject()) return;
        detail.put("source", dataSource.path("source").asText());
        detail.put("sourceVersion", dataSource.path("source_version").asText());
        detail.put("providerId", dataSource.path("provider_id").asText());
    }

    RankingResolution resolveModelRanking(ObjectNode source, JsonNode proposal) {
        return resolveModelRanking(source, proposal, 0.20);
    }

    RankingResolution resolveModelRanking(ObjectNode source, JsonNode proposal, double modelWeight) {
        if (!Double.isFinite(modelWeight) || modelWeight < 0 || modelWeight > 1) {
            throw new IllegalArgumentException("modelWeight must be between zero and one");
        }
        ObjectNode response = source.deepCopy();
        List<JsonNode> items = array(response.path("items"));
        List<String> requested = proposal != null && proposal.path("rankedSkuIds").isArray()
                ? strings(proposal.path("rankedSkuIds")).stream().limit(50).toList()
                : List.of();
        Set<String> allowed = items.stream().map(this::productId)
                .collect(Collectors.toSet());
        List<String> grounded = requested.stream().filter(allowed::contains).distinct().toList();
        List<String> corrections = new ArrayList<>();
        if (proposal == null || !proposal.isObject()
                || !proposal.path("rankedSkuIds").isArray()) {
            corrections.add("invalid_rankedSkuIds");
        }
        if (grounded.size() != requested.size()) corrections.add("unknown_sku_removed");
        if (grounded.isEmpty() && !items.isEmpty()) {
            corrections.add("empty_model_ranking_fallback");
            return new RankingResolution(response, List.copyOf(corrections));
        }

        Map<String, Integer> rank = new LinkedHashMap<>();
        for (int index = 0; index < grounded.size(); index++) {
            rank.putIfAbsent(grounded.get(index), index);
        }
        JsonNode rationaleBySku = proposal.path("rationaleBySku");
        List<ObjectNode> adjusted = new ArrayList<>();
        for (int originalIndex = 0; originalIndex < items.size(); originalIndex++) {
            ObjectNode item = (ObjectNode) items.get(originalIndex).deepCopy();
            int modelIndex = rank.getOrDefault(productId(item), rank.size() + originalIndex);
            double modelScore = Math.max(
                    0, 1 - modelIndex / (double) Math.max(1, items.size()));
            double score = item.path("normalized_score").asDouble();
            item.put("normalized_score", round6(score * (1 - modelWeight) + modelScore * modelWeight));
            String rationale = rationaleBySku.path(productId(item)).asText("").trim();
            if (!rationale.isBlank() && rationale.length() <= 160) {
                ArrayNode reasons = item.withArray("reasons");
                boolean exists = false;
                for (JsonNode reason : reasons) {
                    if (reason.asText().equals("model_rank:" + rationale)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) reasons.add("model_rank:" + rationale);
            }
            item.put("_model_index", modelIndex);
            adjusted.add(item);
        }
        adjusted.sort(Comparator.comparingDouble(
                (ObjectNode item) -> item.path("normalized_score").asDouble()).reversed());
        ArrayNode output = response.putArray("items");
        adjusted.forEach(item -> {
            item.remove("_model_index");
            output.add(item);
        });
        return new RankingResolution(
                response, List.copyOf(new LinkedHashSet<>(corrections)));
    }

    private DecisionBuild buildDecision(
            BoundedCollaborationCoordinator coordinator,
            ArtifactStore artifacts,
            Plan plan,
            List<ChannelResult> channels,
            CommerceDomainPack domain,
            String parentArtifactId,
            List<DecisionResult.TraceStep> trace,
            BoundedCollaborationCoordinator.TraceSink traceSink
    ) {
        return buildDecision(
                coordinator, artifacts, plan, channels, domain,
                parentArtifactId, trace, traceSink, 12);
    }

    private DecisionBuild buildDecision(
            BoundedCollaborationCoordinator coordinator,
            ArtifactStore artifacts,
            Plan plan,
            List<ChannelResult> channels,
            CommerceDomainPack domain,
            String parentArtifactId,
            List<DecisionResult.TraceStep> trace,
            BoundedCollaborationCoordinator.TraceSink traceSink,
            int fusionLimit
    ) {
        FusedExecution fusedExecution = coordinator.delegate(
                "lead", "lead", "calibrated_candidate_fusion", parentArtifactId, 1, 0,
                task -> {
                    ObjectNode request = mapper.createObjectNode();
                    request.put("domain_pack_id", domain.packId());
                    ArrayNode values = request.putArray("channels");
                    channels.forEach(channel -> values.add(channel.response()));
                    request.put("limit", fusionLimit);
                    if (plan.intent().equals("bundle")) {
                        request.set("required_categories", mapper.valueToTree(plan.requirement().requiredCategories()));
                    }
                    ObjectNode response =
                            mapper.valueToTree(dataPlane.fuse(domain.packId(), request));
                    AgentArtifact artifact = publishToolArtifact(
                            artifacts,
                            traceSink,
                            "candidate_set",
                            "lead",
                            task.taskId(),
                            AgentArtifact.Status.VERIFIED,
                            fusedArtifactPayload(response));
                    return new FusedExecution(response, artifact);
                });
        ObjectNode fused = fusedExecution.response();
        List<JsonNode> slateItems = array(fused.path("items"));
        AgentArtifact fusedArtifact = fusedExecution.artifact();

        BundleExecution bundleExecution = coordinator.delegate(
                "lead", "compatibility", "constraint_bundle_optimization",
                fusedArtifact.artifactId(), 1, 0,
                task -> {
                    ObjectNode request = mapper.createObjectNode();
                    request.put("domain_pack_id", domain.packId());
                    ArrayNode items = request.putArray("items");
                    slateItems.forEach(items::add);
                    ArrayNode requested = request.putArray("requested_categories");
                    plan.requirement().requiredCategories().forEach(requested::add);
                    ArrayNode useCases = request.putArray("use_cases");
                    plan.requirement().useCases().forEach(useCases::add);
                    request.put("intent", plan.intent());
                    if (plan.budgetMax() == null) request.putNull("budget_max");
                    else request.put("budget_max", plan.budgetMax());
                    request.put("top_n", 3);
                    ObjectNode response =
                            mapper.valueToTree(dataPlane.bundles(domain.packId(), request));
                    List<JsonNode> responseBundles = array(response.path("bundles"));
                    List<JsonNode> responsePrimaryItems = responseBundles.isEmpty()
                            ? List.of()
                            : resolveBundleItems(
                                    slateItems,
                                    strings(responseBundles.get(0).path("sku_ids")));
                    AgentArtifact artifact = publishToolArtifact(
                            artifacts,
                            traceSink,
                            "bundle_proposal",
                            "compatibility",
                            task.taskId(),
                            AgentArtifact.Status.DRAFT,
                            bundleArtifactPayload(response, responsePrimaryItems, plan));
                    return new BundleExecution(
                            response,
                            responsePrimaryItems,
                            artifact);
                });

        ObjectNode optimized = bundleExecution.response();
        List<JsonNode> bundleNodes = array(optimized.path("bundles"));
        List<JsonNode> primaryBundleItems = bundleExecution.primaryItems();
        List<JsonNode> selectedItems = bundleNodes.isEmpty()
                ? List.of()
                : plan.intent().equals("compare")
                        ? resolveComparisonItems(slateItems, bundleNodes)
                        : primaryBundleItems;
        AgentArtifact bundleArtifact = bundleExecution.artifact();

        FutureTask<EvidenceExecution> quoteFuture = async(() -> coordinator.delegate(
                "compatibility", "pricing", "live_quote_tool", bundleArtifact.artifactId(), 1, 0,
                task -> {
                    ObjectNode request = mapper.createObjectNode();
                    request.put("domain_pack_id", domain.packId());
                    ArrayNode offerIds = request.putArray("offer_ids");
                    selectedItems.forEach(item -> offerIds.add(item.path("offer_id").asText()));
                    ObjectNode response = mapper.valueToTree(
                            dataPlane.quote(domain.packId(), request));
                    traceSink.add(
                            "pricing", "data_plane_result",
                            quoteDataTrace(task.taskId(), response));
                    AgentArtifact artifact = publishToolArtifact(
                            artifacts,
                            traceSink,
                            "price_quote",
                            "pricing",
                            task.taskId(),
                            AgentArtifact.Status.VERIFIED,
                            jsonObjectPayload(response));
                    return new EvidenceExecution(response, artifact);
                }));
        FutureTask<EvidenceExecution> reviewFuture = async(() -> coordinator.delegate(
                "compatibility", "review_evidence", "review_aspect_tool",
                bundleArtifact.artifactId(), 1, 0,
                task -> {
                    List<String> productIds = selectedItems.stream()
                            .map(item -> item.path("product_id").asText())
                            .distinct()
                            .toList();
                    ObjectNode response = reviewEvidence.reviews(domain.packId(), productIds);
                    traceSink.add(
                            "review_evidence", "data_plane_result",
                            reviewDataTrace(task.taskId(), response));
                    AgentArtifact artifact = publishToolArtifact(
                            artifacts,
                            traceSink,
                            "review_evidence",
                            "review_evidence",
                            task.taskId(),
                            AgentArtifact.Status.VERIFIED,
                            jsonObjectPayload(response));
                    return new EvidenceExecution(response, artifact);
                }));
        EvidenceExecution quoteEvidence = join(quoteFuture, coordinator);
        EvidenceExecution reviewEvidenceResult = join(reviewFuture, coordinator);
        ObjectNode quotes = quoteEvidence.response();
        ObjectNode reviews = reviewEvidenceResult.response();
        applyLiveQuotes(selectedItems, quotes);
        AgentArtifact reviewArtifact = reviewEvidenceResult.artifact();

        Map<String, Candidate> slateBySku = slateItems.stream().collect(Collectors.toMap(
                item -> item.path("sku_id").asText(),
                this::candidate,
                (left, right) -> left,
                LinkedHashMap::new));
        List<Candidate> slate = slateItems.stream().map(this::candidate).toList();
        List<DecisionResult.BundleProposal> bundles = bundleNodes.stream()
                .map(node -> bundle(node, slateBySku, plan, fused, reviews, quotes))
                .toList();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("domainPackId", domain.packId());
        metrics.put("searchCandidates", count(channels, "search"));
        metrics.put("recommendationCandidates", count(channels, "recommendation"));
        metrics.put("adCandidates", count(channels, "ads"));
        metrics.put("sponsoredInTop3", slate.stream().limit(3).filter(Candidate::sponsored).count());
        metrics.put("fusionVersion", fused.path("fusion_version").asText());
        metrics.put("organicWeightProfile", fused.path("organic_weight_profile").asText());
        metrics.put("optimizerVersion", optimized.path("optimizer_version").asText());
        metrics.put("quoteBatchId", quotes.path("quote_batch_id").asText());
        metrics.put("quoteVersion", quotes.path("quote_version").asText());
        metrics.put("reviewVersion", reviews.path("review_snapshot_version").asText());
        metrics.put("reviewSource", reviews.path("data_source").path("source").asText());
        metrics.put("reviewProviderId", reviews.path("data_source").path("provider_id").asText());
        metrics.put("reviewFallback", reviews.path("data_source").path("fallback").asBoolean(false));

        DecisionResult result = new DecisionResult(
                plan.requirement(),
                slate,
                bundles,
                List.copyOf(trace),
                Map.copyOf(metrics),
                DecisionResult.offlineRuntime());
        return new DecisionBuild(
                result, slateItems, selectedItems, optimized, quotes, reviews,
                reviewArtifact.artifactId());
    }

    private void applyLiveQuotes(List<JsonNode> selected, ObjectNode batch) {
        Map<String, JsonNode> quotes = array(batch.path("quotes")).stream()
                .collect(Collectors.toMap(item -> item.path("offer_id").asText(), item -> item,
                        (left, right) -> left));
        String quoteVersion = batch.path("quote_version").asText();
        for (JsonNode item : selected) {
            if (!(item instanceof ObjectNode product)) continue;
            JsonNode quote = quotes.get(product.path("offer_id").asText());
            if (quote == null
                    || !quote.path("status").asText().equals("active")
                    || quote.path("amount").isNull()
                    || quote.path("amount").isMissingNode()) {
                product.put("stock", 0);
                continue;
            }
            product.set("price", quote.path("amount").deepCopy());
            product.put("currency", quote.path("currency").asText());
            product.set("stock", quote.path("stock").deepCopy());
            product.put("quote_version", quoteVersion);
            product.put("quote_valid_until", quote.path("valid_until").asText());
        }
    }

    private Audit audit(
            Plan plan,
            List<ChannelResult> channels,
            DecisionBuild decision,
            CommerceDomainPack domain
    ) {
        List<JsonNode> selected = decision.selectedItems();
        Set<String> selectedCategories = selected.stream()
                .map(item -> item.path("category").asText()).collect(Collectors.toSet());
        String primaryCategory = plan.requirement().requiredCategories().contains(domain.primaryCategory())
                ? domain.primaryCategory()
                : plan.requirement().requiredCategories().stream().findFirst().orElse(domain.defaultCategory());
        Set<String> required = plan.intent().equals("bundle")
                ? new LinkedHashSet<>(plan.requirement().requiredCategories())
                : Set.of(primaryCategory);
        Map<String, JsonNode> quotes = array(decision.quotes().path("quotes")).stream()
                .collect(Collectors.toMap(item -> item.path("offer_id").asText(), item -> item,
                        (left, right) -> left));
        Set<String> reviewedProducts = array(decision.reviews().path("products")).stream()
                .map(item -> item.path("product_id").asText()).collect(Collectors.toSet());
        List<JsonNode> compatibility = decision.bundleResponse().path("bundles").isArray()
                && decision.bundleResponse().path("bundles").size() > 0
                ? array(decision.bundleResponse().path("bundles").get(0).path("compatibility"))
                : List.of();
        Set<String> executedChannels = channels.stream().map(ChannelResult::channel)
                .collect(Collectors.toSet());

        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("has_primary_product", selected.stream()
                .anyMatch(item -> item.path("category").asText().equals(primaryCategory)));
        checks.put("requested_category_coverage", selectedCategories.containsAll(required));
        checks.put("all_items_in_stock", selected.stream().allMatch(item -> item.path("stock").asInt() > 0));
        BigDecimal total = selected.stream().map(item -> item.path("price").decimalValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean budgetRespected = plan.budgetMax() == null
                || (plan.intent().equals("compare")
                        ? selected.stream().allMatch(item ->
                                item.path("price").decimalValue().compareTo(plan.budgetMax()) <= 0)
                        : total.compareTo(plan.budgetMax()) <= 0);
        checks.put("budget_respected", budgetRespected);
        checks.put("excluded_brands_respected", selected.stream()
                .noneMatch(item -> plan.requirement().excludedBrands().contains(item.path("brand").asText())));
        checks.put("offer_quote_grounded", selected.stream().allMatch(item ->
                !item.path("offer_id").asText().isBlank()
                        && !item.path("quote_version").asText().isBlank()
                        && !item.path("quote_valid_until").asText().isBlank()
                        && item.path("price").isNumber()
                        && item.path("price").decimalValue().signum() >= 0));
        checks.put("catalog_provenance_versioned", selected.stream().allMatch(item -> {
            JsonNode source = item.path("data_source");
            return Set.of("local_snapshot", "remote_provider").contains(source.path("source").asText())
                    && source.path("source_version").asText().equals(item.path("catalog_version").asText())
                    && !source.path("provider_id").asText().isBlank();
        }));
        checks.put("quote_version_consistent", selected.stream()
                .map(item -> item.path("quote_version").asText()).distinct().count() <= 1);
        checks.put("single_supported_currency", selected.stream()
                .map(item -> item.path("currency").asText()).distinct().count() <= 1
                && selected.stream().allMatch(item -> item.path("currency").asText().equals("CNY")));
        checks.put("compatibility_verified", compatibility.stream()
                .allMatch(item -> item.path("status").asText().equals("compatible")));
        checks.put("compatibility_graph_grounded", compatibility.stream()
                .allMatch(item -> !item.path("rule_version").asText().isBlank()
                        && item.path("paths").isArray() && !item.path("paths").isEmpty()));
        checks.put("live_quote_coverage", selected.stream().allMatch(item -> {
            JsonNode quote = quotes.get(item.path("offer_id").asText());
            return quote != null && quote.path("status").asText().equals("active")
                    && quote.path("amount").isNumber()
                    && quote.path("amount").decimalValue().compareTo(item.path("price").decimalValue()) == 0;
        }));
        String quoteVersion = decision.quotes().path("quote_version").asText();
        checks.put("live_quote_version_applied", selected.stream()
                .allMatch(item -> item.path("quote_version").asText().equals(quoteVersion)));
        JsonNode quoteSource = decision.quotes().path("data_source");
        checks.put("price_quote_provenance_versioned",
                Set.of("local_snapshot", "remote_provider").contains(quoteSource.path("source").asText())
                        && quoteSource.path("source_version").asText().equals(quoteVersion)
                        && !quoteSource.path("provider_id").asText().isBlank());
        checks.put("review_evidence_coverage", selected.stream()
                .allMatch(item -> reviewedProducts.contains(item.path("product_id").asText()))
                && decision.reviews().path("missing_product_ids").isArray()
                && decision.reviews().path("missing_product_ids").isEmpty());
        JsonNode reviewSource = decision.reviews().path("data_source");
        String reviewVersion = decision.reviews().path("review_snapshot_version").asText();
        checks.put("review_evidence_versioned", !reviewVersion.isBlank()
                && Set.of("local_snapshot", "remote_provider").contains(reviewSource.path("source").asText())
                && reviewSource.path("source_version").asText().equals(reviewVersion)
                && !reviewSource.path("provider_id").asText().isBlank()
                && array(decision.reviews().path("products")).stream().allMatch(item ->
                        item.path("source").asText().equals(reviewSource.path("source").asText())
                                && item.path("source_version").asText().equals(reviewVersion)
                                && item.path("provider_id").asText().equals(reviewSource.path("provider_id").asText())
                                && item.path("aspects").isArray() && !item.path("aspects").isEmpty()));
        checks.put("sponsored_disclosed", decision.slateItems().stream()
                .filter(this::requiresSponsoredDisclosure)
                .allMatch(item -> item.path("disclosure").asText().equals("赞助")));
        checks.put("sponsored_top3_cap", decision.slateItems().stream().limit(3)
                .filter(item -> item.path("sponsored").asBoolean()).count() <= 1);
        checks.put("requested_channels_executed", executedChannels.containsAll(plan.channels()));

        List<String> violations = checks.entrySet().stream()
                .filter(entry -> !entry.getValue()).map(Map.Entry::getKey).toList();
        return new Audit(violations.isEmpty(), List.copyOf(violations), Map.copyOf(checks),
                "deterministic", "确定性审计完成");
    }

    private ObjectNode criticAuthoritativeInput(Plan plan, DecisionBuild decision) {
        ObjectNode input = mapper.createObjectNode();
        input.set("plan", planPayload(plan));
        ArrayNode slate = input.putArray("slate");
        decision.slateItems().forEach(item -> slate.add(item.deepCopy()));
        input.set("bundle", bundlePayloadNode(
                decision.bundleResponse(), decision.selectedItems(), plan));
        input.set("priceQuote", decision.quotes().deepCopy());
        input.set("reviewEvidence", decision.reviews().deepCopy());
        return input;
    }

    private ObjectNode criticInput(
            Plan plan,
            DecisionBuild decision,
            Audit deterministic
    ) {
        ObjectNode input = mapper.createObjectNode();
        input.set("plan", planPayload(plan));
        ArrayNode slate = input.putArray("slate");
        decision.slateItems().forEach(item -> {
            ObjectNode candidate = slate.addObject();
            candidate.put("skuId", item.path("sku_id").asText());
            candidate.put("category", item.path("category").asText());
            candidate.put("score", item.path("normalized_score").asDouble());
            candidate.put("sponsored", item.path("sponsored").asBoolean());
        });
        input.set("bundle", bundlePayloadNode(
                decision.bundleResponse(), decision.selectedItems(), plan));
        input.set("quotes", decision.quotes().path("quotes").deepCopy());
        input.set("reviews", decision.reviews().path("products").deepCopy());
        input.set("deterministicAudit", auditPayload(deterministic, 1));
        return input;
    }

    private ObjectNode auditPayload(Audit audit, int attempt) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("verdict", audit.approved() ? "approved" : "vetoed");
        payload.set("violations", mapper.valueToTree(audit.violations()));
        payload.set("checks", mapper.valueToTree(audit.checks()));
        payload.put("modelVerdict", audit.modelVerdict());
        payload.put("rationale", audit.rationale());
        payload.put("attempt", attempt);
        return payload;
    }

    private CriticResolution resolveCritic(
            Audit deterministic,
            JsonNode proposal,
            DecisionBuild decision
    ) {
        Set<String> supported = new LinkedHashSet<>();
        double averageConfidence = array(decision.reviews().path("products")).stream()
                .flatMap(product -> array(product.path("aspects")).stream())
                .mapToDouble(aspect -> aspect.path("confidence").asDouble())
                .average().orElse(0);
        if (averageConfidence < 0.55) supported.add("insufficient_review_confidence");
        boolean useCaseMatched = decision.result().requirement().useCases().isEmpty()
                || decision.selectedItems().stream().anyMatch(item ->
                decision.result().requirement().useCases().stream().anyMatch(useCase ->
                        strings(item.path("tags")).stream().anyMatch(tag ->
                                tag.contains(useCase) || useCase.contains(tag))));
        if (!useCaseMatched) supported.add("weak_use_case_match");
        boolean compatibilityUnknown = array(decision.bundleResponse().path("bundles")).stream()
                .flatMap(bundle -> array(bundle.path("compatibility")).stream())
                .anyMatch(item -> item.path("status").asText().equals("unknown"));
        if (compatibilityUnknown) supported.add("ambiguous_compatibility");

        List<String> corrections = new ArrayList<>();
        boolean validProposal = proposal != null && proposal.isObject();
        String modelVerdict = validProposal
                ? proposal.path("verdict").asText("unavailable") : "unavailable";
        if (!Set.of("approved", "vetoed").contains(modelVerdict)) {
            modelVerdict = "unavailable";
            corrections.add("invalid_model_verdict");
        }
        List<String> requested = validProposal
                && proposal.path("additionalViolations").isArray()
                ? strings(proposal.path("additionalViolations")).stream().limit(8).toList()
                : List.of();
        if (!validProposal) corrections.add("proposal_not_object");
        List<String> grounded = requested.stream().filter(supported::contains).distinct().toList();
        if (grounded.size() != requested.size()) {
            corrections.add("unsupported_model_violation_removed");
        }
        LinkedHashSet<String> violations = new LinkedHashSet<>(deterministic.violations());
        violations.addAll(grounded);
        String rationale = validProposal
                && !proposal.path("rationale").asText("").isBlank()
                && proposal.path("rationale").asText().length() <= 500
                ? proposal.path("rationale").asText().trim()
                : deterministic.rationale();
        Audit resolved = new Audit(
                violations.isEmpty(),
                List.copyOf(violations),
                deterministic.checks(),
                modelVerdict,
                rationale);
        return new CriticResolution(
                resolved, List.copyOf(new LinkedHashSet<>(corrections)));
    }

    private DecisionResult withAudit(DecisionResult result, Audit audit) {
        Map<String, Object> metrics = new LinkedHashMap<>(result.metrics());
        metrics.put("criticVerdict", audit.approved() ? "approved" : "vetoed");
        metrics.put("criticViolations", audit.violations());
        metrics.put("criticChecks", audit.checks());
        return new DecisionResult(
                result.requirement(), result.slate(), result.bundles(), result.trace(),
                Map.copyOf(metrics), result.runtime());
    }

    private DecisionResult.ModelRuntime runtime(
            Plan plan,
            String finalMessage,
            Audit audit,
            boolean revised,
            List<DecisionResult.TraceStep> trace
    ) {
        List<DecisionResult.RoleExecution> roleExecutions = trace.stream()
                .filter(step -> step.decision().equals("model_execution"))
                .map(this::roleExecution)
                .toList();
        AgentModelTransport.Description description = model.describe("lead");
        int modelCalls = (int) trace.stream().filter(step -> step.decision().equals("model_budget_consumed")).count();
        int fallbackCount = (int) roleExecutions.stream()
                .filter(item -> item.outcome().equals("fallback")).count();
        int proposalAccepted = (int) roleExecutions.stream()
                .filter(item -> item.outcome().equals("accepted")).count();
        int proposalCorrected = (int) roleExecutions.stream()
                .filter(item -> item.outcome().equals("corrected")).count();
        return new DecisionResult.ModelRuntime(
                model.mode(),
                description.model(),
                modelCalls,
                fallbackCount,
                roleExecutions.stream().mapToInt(
                        DecisionResult.RoleExecution::totalTokens).sum(),
                roleExecutions.stream().mapToLong(
                        DecisionResult.RoleExecution::latencyMs).sum(),
                plan.requirement().retrievalQuery(),
                plan.intent(),
                finalMessage,
                audit.approved() ? "approved" : "vetoed",
                revised,
                description.provider(),
                description.localOnly(),
                proposalAccepted,
                proposalCorrected,
                roleExecutions);
    }

    private DecisionResult.RoleExecution roleExecution(
            DecisionResult.TraceStep step
    ) {
        Map<String, Object> facts = step.facts();
        Object correctionValue = facts.get("corrections");
        List<String> corrections = correctionValue instanceof List<?> values
                ? values.stream().map(String::valueOf).toList()
                : List.of();
        return new DecisionResult.RoleExecution(
                step.stage(),
                textFact(facts, "mode"),
                textFact(facts, "provider"),
                textFact(facts, "model"),
                textFact(facts, "outcome"),
                Boolean.TRUE.equals(facts.get("proposalUsed")),
                corrections,
                longFact(facts, "latencyMs"),
                intFact(facts, "inputTokens"),
                intFact(facts, "outputTokens"),
                intFact(facts, "totalTokens"),
                facts.get("error") == null ? null : String.valueOf(facts.get("error")));
    }

    private String textFact(Map<String, Object> facts, String key) {
        Object value = facts.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private int intFact(Map<String, Object> facts, String key) {
        Object value = facts.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private long longFact(Map<String, Object> facts, String key) {
        Object value = facts.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String renderMessage(DecisionBuild decision, Audit audit, Plan plan) {
        if (!audit.approved()) {
            return renderAuditFailure(audit, plan);
        }
        List<String> lines = new ArrayList<>();
        boolean comparison = plan.intent().equals("compare");
        lines.add(comparison ? "对比候选：" : "购买方案：");
        for (int index = 0; index < decision.selectedItems().size(); index++) {
            JsonNode item = decision.selectedItems().get(index);
            lines.add((index + 1) + ". " + item.path("title").asText()
                    + "｜¥" + item.path("price").decimalValue().stripTrailingZeros().toPlainString()
                    + (requiresSponsoredDisclosure(item) ? "｜赞助" : ""));
            lines.add("推荐依据：" + recommendationReason(item, decision.reviews(), plan)
                    + "；取舍：" + tradeoff(item, decision.reviews()) + "。");
        }
        if (comparison) {
            String conclusion = comparisonConclusion(
                    decision.selectedItems(), decision.reviews(), plan.requirement().useCases());
            if (!conclusion.isBlank()) lines.add(conclusion);
        }
        if (!plan.requirement().sponsoredAllowed()) {
            lines.add("广告政策：广告通道已关闭；结果仅来自自然搜索和推荐，不接受竞价加权。");
        }
        if (!comparison) {
            BigDecimal total = decision.selectedItems().stream().map(item -> item.path("price").decimalValue())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String budgetText = "预算校验：通过";
            if (plan.budgetMax() != null) {
                BigDecimal utilization = total.multiply(BigDecimal.valueOf(100))
                        .divide(plan.budgetMax(), 1, RoundingMode.HALF_UP);
                budgetText += "；预算上限：¥" + plan.budgetMax().stripTrailingZeros().toPlainString()
                        + "；预算利用率：" + utilization.stripTrailingZeros().toPlainString() + "%";
            }
            lines.add("套装合计：¥" + total.stripTrailingZeros().toPlainString() + "；" + budgetText + "。");
            String compatibility = compatibilityEvidence(decision);
            if (!compatibility.isBlank()) lines.add("兼容依据：" + compatibility + "。");
        }
        lines.add(evidenceSourceSummary(decision));
        lines.add("价格批次：" + decision.quotes().path("quote_batch_id").asText()
                + "；确认前仍需刷新库存与报价。");
        lines.add("交易边界：本次只生成购买建议，没有创建订单，也没有发起支付；任何购买动作都需要用户再次确认。");
        return String.join("\n", lines);
    }

    private String renderAuditFailure(Audit audit, Plan plan) {
        List<String> reasons = audit.violations().stream()
                .map(this::userFacingViolation)
                .distinct()
                .toList();
        StringBuilder message = new StringBuilder("当前没有生成可确认的购买方案。原因：")
                .append(String.join("、", reasons)).append("。");
        if (plan.budgetMax() != null
                && (audit.violations().contains("has_primary_product")
                        || audit.violations().contains("requested_category_coverage"))) {
            message.append("当前预算上限为¥")
                    .append(plan.budgetMax().stripTrailingZeros().toPlainString())
                    .append("；可以提高预算，或放宽品牌、容量、性能等要求后重试。");
        } else {
            message.append("请调整预算或商品要求后重试。");
        }
        return message.toString();
    }

    private String userFacingViolation(String code) {
        return switch (code) {
            case "has_primary_product" -> "未找到符合要求的主商品";
            case "requested_category_coverage" -> "所需商品品类未配齐";
            case "all_items_in_stock" -> "部分商品当前无库存";
            case "budget_respected" -> "方案总价超过预算";
            case "budget_utilization_reasonable" -> "方案与预算档位差距过大";
            case "offer_quote_grounded", "live_quote_coverage" -> "部分商品缺少可验证报价";
            case "catalog_provenance_versioned" -> "部分商品目录来源不可验证";
            case "quote_version_consistent", "live_quote_version_applied" -> "商品报价批次不一致";
            case "single_supported_currency" -> "报价币种不受支持或不一致";
            case "compatibility_verified", "ambiguous_compatibility" -> "商品兼容性尚未确认";
            case "compatibility_graph_grounded" -> "兼容性缺少规则依据";
            case "price_quote_provenance_versioned" -> "报价来源不可验证";
            case "review_evidence_coverage" -> "部分商品缺少评论证据";
            case "review_evidence_versioned" -> "评论证据来源不可验证";
            case "sponsored_disclosed" -> "赞助商品标识不完整";
            case "sponsored_top3_cap" -> "赞助商品展示数量超过限制";
            case "requested_channels_executed" -> "必要检索渠道未完整执行";
            case "insufficient_review_confidence" -> "评论证据置信度不足";
            case "weak_use_case_match" -> "候选商品与使用需求匹配不足";
            default -> "其他购买约束未满足";
        };
    }

    private boolean requiresSponsoredDisclosure(JsonNode item) {
        return item.path("sponsored").asBoolean()
                || item.path("disclosure").asText().equals("赞助");
    }

    private String resolveFinalMessage(
            String fallback,
            boolean approved,
            DecisionBuild decision,
            ModelPortAgentBridge.RoleCall call
    ) {
        if (!call.success()) return fallback;
        String message = call.text("message");
        if (message == null || call.proposal().path("approved").asBoolean(!approved) != approved) return fallback;
        if (Pattern.compile("已支付|支付成功|已下单|订单已创建").matcher(message).find()) return fallback;
        if (countOccurrences(fallback, "｜赞助") > countOccurrences(message, "赞助")) return fallback;
        if (fallback.contains("快充依据：") && !hasChargingEvidence(message)) return fallback;
        for (String evidenceToken : List.of("目录证据", "评论聚合", "提及")) {
            if (countOccurrences(fallback, evidenceToken)
                    > countOccurrences(message, evidenceToken)) return fallback;
        }
        for (String dimension : decision.result().requirement().useCases()) {
            if (fallback.contains(dimension + "：")
                    && !message.contains(dimension + "：")) return fallback;
        }
        for (String required : List.of(
                "对比候选", "购买方案", "推荐依据", "取舍", "对比结论", "套装合计",
                "预算利用率", "兼容依据", "接口协议依据", "广告政策", "广告通道已关闭",
                "自然搜索", "证据来源", "样本量",
                "交易边界", "没有创建订单", "没有发起支付")) {
            if (fallback.contains(required) && !message.contains(required)) return fallback;
        }
        Set<BigDecimal> allowed = new LinkedHashSet<>();
        Matcher fallbackNumbers = NUMBER.matcher(fallback);
        while (fallbackNumbers.find()) {
            allowed.add(new BigDecimal(fallbackNumbers.group()).stripTrailingZeros());
        }
        Matcher matcher = NUMBER.matcher(message);
        while (matcher.find()) {
            if (!allowed.contains(new BigDecimal(matcher.group()).stripTrailingZeros())) return fallback;
        }
        return message;
    }

    private FinalResolution resolveFinalProposal(
            String fallback,
            boolean approved,
            DecisionBuild decision,
            JsonNode proposal
    ) {
        ModelPortAgentBridge.RoleCall synthetic = new ModelPortAgentBridge.RoleCall(
                "lead", true, true, proposal, 0, 0, null);
        String message = resolveFinalMessage(fallback, approved, decision, synthetic);
        String proposed = proposal == null ? null : proposal.path("message").asText(null);
        List<String> corrections = proposed != null && message.equals(proposed)
                ? List.of()
                : List.of("unsafe_or_invalid_final_message_fallback");
        return new FinalResolution(message, corrections);
    }

    private ObjectNode finalDecisionPayload(
            String message,
            boolean approved,
            DecisionBuild decision
    ) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("message", message);
        payload.put("approved", approved);
        payload.put("bundleCount", decision.result().bundles().size());
        payload.put("slateSize", decision.result().slate().size());
        return payload;
    }

    private ObjectNode discoveryRequest(Plan plan, String channel, List<JsonNode> primaryProducts) {
        ObjectNode request = mapper.createObjectNode();
        request.put("query", plan.requirement().retrievalQuery());
        request.put("domain_pack_id", plan.domainPackId());
        ArrayNode categories = request.putArray("requested_categories");
        plan.requirement().requiredCategories().forEach(categories::add);
        ArrayNode useCases = request.putArray("use_cases");
        plan.requirement().useCases().forEach(useCases::add);
        ArrayNode preferredBrands = request.putArray("preferred_brands");
        plan.requirement().preferredBrands().forEach(preferredBrands::add);
        request.set("excluded_brands", mapper.valueToTree(plan.requirement().excludedBrands()));
        ArrayNode primary = request.putArray("primary_product_ids");
        primaryProducts.forEach(item -> primary.add(item.path("product_id").asText()));
        if (plan.budgetMax() == null) request.putNull("max_price");
        else request.put("max_price", plan.budgetMax());
        request.put("limit", plan.candidateBudget().get(channel));
        request.put("sponsored_allowed", plan.requirement().sponsoredAllowed());
        DiscoveryContext context = plan.discoveryContext();
        request.put("identity_id", context.identityId());
        request.put("session_id", context.sessionId());
        request.put("personalization_enabled", context.personalizationEnabled());
        ArrayNode recent = request.putArray("recent_product_ids");
        context.recentProductIds().forEach(recent::add);
        ArrayNode excluded = request.putArray("excluded_product_ids");
        context.excludedProductIds().forEach(excluded::add);
        ArrayNode exposures = request.putArray("ad_exposure_product_ids");
        context.adExposureProductIds().forEach(exposures::add);
        return request;
    }

    private Candidate candidate(JsonNode item) {
        String id = item.path("sku_id").asText() + "@" + item.path("offer_id").asText();
        List<String> sources = strings(item.path("sources"));
        if (sources.isEmpty() && !item.path("channel").asText().isBlank()) {
            sources = List.of(item.path("channel").asText());
        }
        String source = item.path("data_source").path("source").asText("local_snapshot");
        String sourceVersion = item.path("data_source").path("source_version").asText("unknown");
        String provider = item.path("data_source").path("provider_id").asText("unknown");
        Product product = new Product(
                id,
                item.path("title").asText(),
                item.path("category").asText(),
                item.path("brand").asText(),
                item.path("price").decimalValue(),
                strings(item.path("tags")),
                item.path("normalized_score").asDouble(),
                item.path("normalized_score").asDouble(),
                item.path("sponsored").asBoolean(),
                item.path("ad_bid").asDouble(0),
                "universal",
                item.path("stock").asInt(),
                source,
                sourceVersion,
                provider,
                item.path("product_id").asText(),
                item.path("sku_id").asText(),
                item.path("offer_id").asText(),
                item.path("currency").asText("CNY"),
                item.path("catalog_version").asText(sourceVersion),
                item.path("quote_version").asText(sourceVersion),
                item.path("quote_valid_until").asText(""),
                strings(item.path("connectors")),
                strings(item.path("protocols")),
                item.path("max_power_watts").isNumber()
                        ? item.path("max_power_watts").asInt()
                        : null);
        Map<String, Double> channelScores = new LinkedHashMap<>();
        if (!item.path("channel").asText().isBlank()) {
            channelScores.put(item.path("channel").asText(), item.path("channel_score").asDouble());
        }
        return new Candidate(
                product,
                item.path("normalized_score").asDouble(),
                item.path("sponsored").asBoolean(),
                sources,
                Map.copyOf(channelScores),
                strings(item.path("reasons")));
    }

    private DecisionResult.BundleProposal bundle(
            JsonNode node,
            Map<String, Candidate> slate,
            Plan plan,
            JsonNode fused,
            JsonNode reviews,
            JsonNode quotes
    ) {
        List<String> skuIds = strings(node.path("sku_ids"));
        List<Product> items = skuIds.stream().map(slate::get).filter(java.util.Objects::nonNull)
                .map(Candidate::product).toList();
        BigDecimal total = items.stream()
                .map(Product::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean withinBudget = plan.budgetMax() == null || total.compareTo(plan.budgetMax()) <= 0;
        boolean compatible = array(node.path("compatibility")).stream()
                .allMatch(item -> item.path("status").asText().equals("compatible"));
        List<String> evidence = List.of(
                fused.path("fusion_version").asText(),
                quotes.path("quote_version").asText(),
                reviews.path("review_snapshot_version").asText());
        return new DecisionResult.BundleProposal(
                String.join("+", skuIds), items, total,
                node.path("score").asDouble(), withinBudget, compatible, evidence);
    }

    private List<JsonNode> resolveBundleItems(List<JsonNode> slate, List<String> skuIds) {
        Map<String, JsonNode> bySku = slate.stream().collect(Collectors.toMap(
                item -> item.path("sku_id").asText(), item -> item, (left, right) -> left));
        return skuIds.stream().map(bySku::get).filter(java.util.Objects::nonNull).toList();
    }

    private List<JsonNode> resolveComparisonItems(List<JsonNode> slate, List<JsonNode> bundleNodes) {
        Map<String, JsonNode> bySku = slate.stream().collect(Collectors.toMap(
                item -> item.path("sku_id").asText(), item -> item, (left, right) -> left));
        LinkedHashSet<String> selectedSkuIds = new LinkedHashSet<>();
        bundleNodes.stream().limit(3)
                .flatMap(bundle -> strings(bundle.path("sku_ids")).stream())
                .forEach(selectedSkuIds::add);
        return selectedSkuIds.stream().map(bySku::get)
                .filter(java.util.Objects::nonNull).toList();
    }

    private String recommendationReason(JsonNode item, JsonNode reviews, Plan plan) {
        JsonNode review = productReview(item, reviews);
        List<String> dimensions = plan.requirement().useCases();
        if (plan.intent().equals("compare") && !dimensions.isEmpty()) {
            return dimensions.stream()
                    .map(dimension -> dimensionEvidence(item, review, dimension, true))
                    .collect(Collectors.joining("；"));
        }

        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        String charging = chargingEvidence(item, plan);
        if (!charging.isBlank()) evidence.add(charging);
        String protocol = protocolEvidence(item, plan);
        if (!protocol.isBlank()) evidence.add(protocol);
        for (String dimension : dimensions) {
            String matched = dimensionEvidence(item, review, dimension, false);
            if (!matched.isBlank()) evidence.add(matched);
        }
        if (evidence.isEmpty()) {
            JsonNode catalog = array(item.path("decision_evidence")).stream()
                    .findFirst().orElse(null);
            if (catalog != null) evidence.add(formatCatalogEvidence(catalog));
            JsonNode aspect = review == null ? null : array(review.path("aspects")).stream()
                    .filter(value -> value.path("sentiment").asDouble() >= 0.6)
                    .findFirst().orElse(null);
            if (aspect != null) evidence.add(formatReviewAspect(review, aspect));
        }
        return evidence.isEmpty()
                ? "证据不足：当前目录与评论快照均未提供可验证的能力依据"
                : String.join("；", evidence);
    }

    private String dimensionEvidence(
            JsonNode item,
            JsonNode review,
            String dimension,
            boolean stateMissing
    ) {
        JsonNode catalog = array(item.path("decision_evidence")).stream()
                .filter(value -> dimensionMatches(
                        value.path("dimension").asText(), dimension))
                .findFirst().orElse(null);
        JsonNode aspect = review == null ? null : array(review.path("aspects")).stream()
                .filter(value -> dimensionMatches(
                        reviewDimension(value), dimension))
                .findFirst().orElse(null);
        List<String> parts = new ArrayList<>();
        if (catalog != null) parts.add(formatCatalogEvidence(catalog));
        if (aspect != null) parts.add(formatReviewAspect(review, aspect));
        if (parts.isEmpty()) {
            return stateMissing
                    ? dimension + "：证据不足（目录和评论快照均无该维度）"
                    : "";
        }
        return dimension + "：" + String.join("；", parts);
    }

    private JsonNode productReview(JsonNode item, JsonNode reviews) {
        return array(reviews.path("products")).stream()
                .filter(product -> product.path("product_id").asText()
                        .equals(item.path("product_id").asText()))
                .findFirst().orElse(null);
    }

    private String formatCatalogEvidence(JsonNode evidence) {
        String source = evidence.path("source_ref").asText("unknown");
        return "目录证据（" + catalogSourceLabel(evidence.path("source_type").asText())
                + "，来源 " + source + "，置信度 "
                + decimal(evidence.path("confidence").asDouble()) + "）："
                + evidence.path("statement").asText();
    }

    private String formatReviewAspect(JsonNode review, JsonNode aspect) {
        return "评论聚合（样本量 " + review.path("sample_size").asInt()
                + "，" + aspect.path("aspect").asText() + "提及 "
                + aspect.path("mention_count").asInt() + " 次，情感倾向 "
                + decimal(aspect.path("sentiment").asDouble()) + "，置信度 "
                + decimal(aspect.path("confidence").asDouble()) + "）："
                + aspect.path("summary").asText();
    }

    private String catalogSourceLabel(String sourceType) {
        return switch (sourceType) {
            case "provider_spec" -> "上游商品规格";
            case "platform_derived" -> "平台规则推导";
            case "merchant_claim" -> "商家声明";
            default -> "版本化商品目录";
        };
    }

    private boolean dimensionMatches(String actual, String requested) {
        return !actual.isBlank() && actual.equalsIgnoreCase(requested);
    }

    private String reviewDimension(JsonNode aspect) {
        String dimension = aspect.path("dimension").asText();
        return dimension.isBlank() ? aspect.path("aspect").asText() : dimension;
    }

    private String comparisonConclusion(
            List<JsonNode> items,
            JsonNode reviews,
            List<String> dimensions
    ) {
        List<String> conclusions = new ArrayList<>();
        for (String dimension : dimensions) {
            JsonNode bestItem = null;
            JsonNode bestAspect = null;
            double bestScore = -Double.MAX_VALUE;
            for (JsonNode item : items) {
                JsonNode review = productReview(item, reviews);
                if (review == null) continue;
                JsonNode aspect = array(review.path("aspects")).stream()
                        .filter(value -> dimensionMatches(
                                reviewDimension(value), dimension))
                        .findFirst().orElse(null);
                if (aspect == null) continue;
                double score = aspect.path("sentiment").asDouble()
                        * aspect.path("confidence").asDouble();
                if (score > bestScore) {
                    bestScore = score;
                    bestItem = item;
                    bestAspect = aspect;
                }
            }
            if (bestItem != null) {
                conclusions.add(dimension + "优先可看 " + bestItem.path("title").asText()
                        + "（评论情感倾向 " + decimal(bestAspect.path("sentiment").asDouble())
                        + "，置信度 " + decimal(bestAspect.path("confidence").asDouble()) + "）");
            }
        }
        if (conclusions.isEmpty()) return "";
        return "对比结论（仅按当前证据快照）："
                + String.join("；", conclusions)
                + "。这是证据强度比较，不等同于实验室性能排名。";
    }

    private String evidenceSourceSummary(DecisionBuild decision) {
        String catalogVersions = decision.selectedItems().stream()
                .map(item -> item.path("catalog_version").asText())
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.joining("、"));
        JsonNode source = decision.reviews().path("data_source");
        String reviewKind = source.path("source").asText().equals("remote_provider")
                ? "远程评论服务" : "离线评论聚合";
        String fallbackDisclosure = source.path("fallback").asBoolean(false)
                ? "（第三方评论服务不可用，本次已回退本地快照）" : "";
        return "证据来源：商品能力来自版本化目录 "
                + (catalogVersions.isBlank() ? "unknown" : catalogVersions)
                + "；用户口碑来自" + reviewKind + fallbackDisclosure + "快照 "
                + decision.reviews().path("review_snapshot_version").asText()
                + "。大模型只组织已返回证据，不生成价格、库存或商品能力事实。";
    }

    private String decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private String chargingEvidence(JsonNode item, Plan plan) {
        boolean requested = plan.requirement().useCases().stream()
                .anyMatch(useCase -> useCase.contains("快充"));
        boolean chargingItem = "charger".equals(item.path("category").asText())
                || item.path("max_power_watts").isNumber();
        if (!requested || !chargingItem) return "";

        List<String> facts = new ArrayList<>();
        if (item.path("max_power_watts").isNumber()) {
            facts.add("最大输出" + item.path("max_power_watts").decimalValue()
                    .stripTrailingZeros().toPlainString() + "W");
        }
        List<String> protocols = strings(item.path("protocols")).stream()
                .map(this::chargingProtocolLabel)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (!protocols.isEmpty()) facts.add("支持" + String.join("、", protocols));
        return facts.isEmpty()
                ? "快充依据：商品目录未记录最大输出功率或快充协议，无法确认快充能力"
                : "快充依据：" + String.join("，", facts);
    }

    private String chargingProtocolLabel(String protocol) {
        return switch (protocol) {
            case "usb-pd" -> "USB-PD协议";
            case "pps" -> "PPS快充协议";
            case "xiaomi-fast-charge" -> "小米快充协议";
            default -> "";
        };
    }

    private String protocolEvidence(JsonNode item, Plan plan) {
        List<String> requested = plan.requirement().constraints().stream()
                .filter(constraint -> constraint.status() == Requirement.ConstraintStatus.ACTIVE)
                .filter(constraint -> constraint.field().equals("protocolTerms"))
                .map(constraint -> String.valueOf(constraint.value()))
                .toList();
        if (requested.isEmpty()) return "";

        List<String> structured = new ArrayList<>();
        structured.addAll(strings(item.path("connectors")));
        structured.addAll(strings(item.path("protocols")));
        boolean matched = requested.stream().allMatch(expected -> structured.stream()
                .anyMatch(actual -> capabilityTokenMatches(expected, actual)));
        if (!matched) {
            return "接口协议依据：结构化目录未证明用户指定的"
                    + String.join("、", requested);
        }

        List<String> connectors = strings(item.path("connectors")).stream()
                .map(this::capabilityTokenLabel).distinct().toList();
        List<String> protocols = strings(item.path("protocols")).stream()
                .map(this::capabilityTokenLabel).distinct().toList();
        List<String> facts = new ArrayList<>();
        if (!connectors.isEmpty()) facts.add("接口为" + String.join("、", connectors));
        if (!protocols.isEmpty()) facts.add("协议为" + String.join("、", protocols));
        return "接口协议依据：结构化目录记录" + String.join("，", facts);
    }

    private boolean capabilityTokenMatches(String requested, String actual) {
        String expected = requested.toLowerCase(Locale.ROOT);
        String candidate = actual.toLowerCase(Locale.ROOT);
        return candidate.equals(expected)
                || candidate.startsWith(expected + "-")
                || expected.startsWith(candidate + "-");
    }

    private String capabilityTokenLabel(String token) {
        return switch (token) {
            case "usb-c" -> "USB-C";
            case "bluetooth" -> "蓝牙";
            case "usb-pd" -> "USB-PD";
            case "pps" -> "PPS";
            case "xiaomi-fast-charge" -> "小米快充";
            case "en417-thread" -> "EN417 螺纹接口";
            case "lindal-valve" -> "Lindal 阀门";
            case "bayonet" -> "卡口接口";
            case "butane-cassette" -> "卡式丁烷规范";
            case "pot-support-medium" -> "中型锅架";
            case "pot-support-wide" -> "宽型锅架";
            case "cookware-direct" -> "直连锅具规范";
            default -> token;
        };
    }

    private boolean hasChargingEvidence(String message) {
        boolean hasPower = POWER.matcher(message).find();
        boolean hasProtocol = message.contains("USB-PD")
                || message.contains("PPS")
                || message.contains("小米快充协议");
        return hasPower && hasProtocol;
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
    private String tradeoff(JsonNode item, JsonNode reviews) {
        List<String> tradeoffs = strings(item.path("tradeoffs"));
        if (!tradeoffs.isEmpty()) return "目录记录：" + tradeoffs.get(0);
        JsonNode review = productReview(item, reviews);
        JsonNode negative = review == null ? null : array(review.path("aspects")).stream()
                .filter(aspect -> aspect.path("sentiment").asDouble() < 0.6)
                .findFirst().orElse(null);
        if (negative != null) return formatReviewAspect(review, negative);
        return "证据边界：当前目录与评论快照没有可靠的负向证据，需结合实际体验复核";
    }

    private String compatibilityEvidence(DecisionBuild decision) {
        if (!decision.bundleResponse().path("bundles").isArray()
                || decision.bundleResponse().path("bundles").isEmpty()) return "";
        List<String> evidence = new ArrayList<>();
        for (JsonNode edge : array(decision.bundleResponse().path("bundles").get(0).path("compatibility"))) {
            List<String> tokens = strings(edge.path("paths")).stream()
                    .map(this::compatibilityToken)
                    .filter(value -> !value.isBlank())
                    .distinct().toList();
            if (!tokens.isEmpty()) evidence.add("共享" + String.join("、", tokens));
        }
        return String.join("；", evidence);
    }

    private String compatibilityToken(String path) {
        int start = path.indexOf("->");
        int end = path.indexOf("<-", start + 2);
        if (start < 0 || end < 0) return "";
        String token = path.substring(start + 2, end);
        return switch (token) {
            case "usb-c" -> "USB-C 接口";
            case "bluetooth" -> "蓝牙连接";
            case "usb-pd" -> "USB-PD 协议";
            case "pps" -> "PPS 快充协议";
            case "xiaomi-fast-charge" -> "小米快充协议";
            case "en417-thread" -> "EN417 螺纹接口";
            case "lindal-valve" -> "Lindal 阀门";
            case "bayonet" -> "卡口接口";
            case "butane-cassette" -> "卡式丁烷规范";
            case "pot-support-medium" -> "中型锅架";
            case "pot-support-wide" -> "宽型锅架";
            case "cookware-direct" -> "直连锅具规范";
            default -> token;
        };
    }

    private List<ChannelResult> orderedChannels(
            Plan plan,
            ChannelResult search,
            ChannelResult recommendation,
            ChannelResult ads
    ) {
        Map<String, ChannelResult> values = new LinkedHashMap<>();
        if (search != null) values.put("search", search);
        if (recommendation != null) values.put("recommendation", recommendation);
        if (ads != null) values.put("ads", ads);
        return plan.channels().stream().map(values::get).filter(java.util.Objects::nonNull).toList();
    }

    private Set<String> missingRequestedCategories(
            Plan plan,
            CommerceDomainPack domain,
            List<JsonNode> selected
    ) {
        if (!"bundle".equals(plan.intent())) {
            return Set.of();
        }
        Set<String> present = selected.stream().map(item -> item.path("category").asText())
                .collect(Collectors.toSet());
        return plan.requirement().requiredCategories().stream()
                .filter(category -> !present.contains(category))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean hasFeasibleMissingCategory(
            List<JsonNode> slate,
            Set<String> missing,
            BigDecimal budget,
            BigDecimal currentBundleTotal
    ) {
        return slate.stream().anyMatch(item -> missing.contains(item.path("category").asText())
                && item.path("stock").asInt() > 0
                && (budget == null || currentBundleTotal.add(item.path("price").decimalValue())
                        .compareTo(budget) <= 0));
    }

    private String inferIntent(
            String message,
            CommerceDomainPack domain
    ) {
        String normalized = java.text.Normalizer.normalize(
                message, java.text.Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.contains("对比") || normalized.contains("比较")) return "compare";
        if (normalized.contains("推荐") || normalized.contains("适合")
                || normalized.contains("怎么选") || normalized.contains("帮我选")) {
            return "exploratory";
        }
        boolean protocolMatched = domain.protocolTerms().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
        boolean modelLikeBrandMatched = Pattern.compile("[a-z0-9]")
                .matcher(normalized)
                .find()
                && domain.brands().stream()
                        .flatMap(brand -> brand.terms().stream())
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .anyMatch(normalized::contains);
        if (protocolMatched || modelLikeBrandMatched) return "precise";
        return "catalog";
    }

    private ObjectNode emptyChannel(String channel) {
        ObjectNode response = mapper.createObjectNode();
        response.put("channel", channel);
        response.putArray("items");
        return response;
    }

    private boolean sourceVersioned(JsonNode node) {
        JsonNode source = node.has("data_source") ? node.path("data_source") : node;
        return !source.path("source").asText().isBlank()
                && !source.path("source_version").asText().isBlank()
                && !source.path("provider_id").asText().isBlank();
    }

    private int count(List<ChannelResult> channels, String channel) {
        return channels.stream().filter(item -> item.channel().equals(channel))
                .mapToInt(item -> item.items().size()).findFirst().orElse(0);
    }

    private String productId(JsonNode item) {
        return item.path("sku_id").asText();
    }

    private List<JsonNode> array(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<JsonNode> values = new ArrayList<>();
        node.forEach(values::add);
        return List.copyOf(values);
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (item.isTextual()) values.add(item.asText());
        });
        return List.copyOf(values);
    }

    private double round6(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private String boundedString(JsonNode node, int maxLength) {
        if (node == null || !node.isTextual()) return null;
        String value = node.asText().replaceAll("\\s+", " ").trim();
        return value.isEmpty() || value.length() > maxLength ? null : value;
    }

    private List<String> boundedStringArray(JsonNode node, int maxItems) {
        if (node == null || !node.isArray() || node.size() > maxItems) return null;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) return null;
            String value = item.asText().trim();
            if (!value.isEmpty()) values.add(value);
        }
        return List.copyOf(values);
    }

    private <T> FutureTask<T> async(java.util.concurrent.Callable<T> callable) {
        FutureTask<T> task = new FutureTask<>(callable);
        collaborationExecutor.execute(task);
        return task;
    }

    private <T> FutureTask<T> completedTask(T value) {
        FutureTask<T> task = new FutureTask<>(() -> value);
        task.run();
        return task;
    }

    private <T> T join(
            FutureTask<T> future,
            BoundedCollaborationCoordinator coordinator
    ) {
        try {
            while (true) {
                coordinator.assertActive();
                try {
                    return future.get(50, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Poll so durable cancellation and the run deadline remain observable.
                }
            }
        } catch (CancellationException error) {
            future.cancel(true);
            throw error;
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException("run cancelled");
            cancelled.initCause(error);
            throw cancelled;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (RuntimeException error) {
            future.cancel(true);
            throw error;
        }
    }

    @FunctionalInterface
    public interface ExecutionObserver {
        void onTrace(DecisionResult.TraceStep trace);
    }

    public record DiscoveryContext(
            String identityId,
            String sessionId,
            boolean personalizationEnabled,
            List<String> recentProductIds,
            List<String> excludedProductIds,
            List<String> adExposureProductIds
    ) {
        public DiscoveryContext {
            recentProductIds = List.copyOf(recentProductIds);
            excludedProductIds = List.copyOf(excludedProductIds);
            adExposureProductIds = List.copyOf(adExposureProductIds);
        }
    }

    public record Execution(
            DecisionResult result,
            List<ModelPortAgentBridge.RoleCall> roleCalls,
            List<BoundedCollaborationCoordinator.Task> tasks,
            List<BoundedCollaborationCoordinator.DelegationProposal> proposals,
            boolean revisionApplied,
            List<AgentArtifact> artifacts
    ) {
    }

    private record FusedExecution(ObjectNode response, AgentArtifact artifact) {
    }

    private record BundleExecution(
            ObjectNode response,
            List<JsonNode> primaryItems,
            AgentArtifact artifact
    ) {
    }

    private record EvidenceExecution(ObjectNode response, AgentArtifact artifact) {
    }

    private record CriticExecution(Audit audit, AgentArtifact artifact) {
    }

    private record CriticResolution(Audit audit, List<String> corrections) {
        private CriticResolution {
            corrections = List.copyOf(corrections);
        }
    }

    private record FinalResolution(String message, List<String> corrections) {
        private FinalResolution {
            corrections = List.copyOf(corrections);
        }
    }

    private record PlanExecution(Plan plan, AgentArtifact artifact) {
    }

    record PlanResolution(Plan plan, List<String> corrections) {
        PlanResolution {
            corrections = List.copyOf(corrections);
        }
    }

    record RankingResolution(ObjectNode response, List<String> corrections) {
        RankingResolution {
            corrections = List.copyOf(corrections);
        }
    }

    record Plan(
            String runId,
            String domainPackId,
            Requirement requirement,
            String intent,
            List<String> channels,
            Map<String, Integer> candidateBudget,
            String reason,
            DiscoveryContext discoveryContext
    ) {
        BigDecimal budgetMax() {
            return requirement.budget();
        }

        Plan withRecommendationBudget(int value) {
            Map<String, Integer> budgets = new LinkedHashMap<>(candidateBudget);
            budgets.put("recommendation", value);
            return new Plan(runId, domainPackId, requirement, intent, channels,
                    Map.copyOf(budgets), reason, discoveryContext);
        }
    }

    private record ChannelResult(
            String channel,
            List<JsonNode> items,
            ObjectNode response,
            String taskId,
            String artifactId
    ) {
    }

    private record DecisionBuild(
            DecisionResult result,
            List<JsonNode> slateItems,
            List<JsonNode> selectedItems,
            ObjectNode bundleResponse,
            ObjectNode quotes,
            ObjectNode reviews,
            String parentTaskId
    ) {
    }

    private record Audit(
            boolean approved,
            List<String> violations,
            Map<String, Boolean> checks,
            String modelVerdict,
            String rationale
    ) {
    }
}
