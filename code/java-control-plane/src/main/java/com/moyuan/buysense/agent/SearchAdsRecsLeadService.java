package com.moyuan.buysense.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moyuan.buysense.domain.Candidate;
import com.moyuan.buysense.domain.DecisionResult;
import com.moyuan.buysense.domain.Product;
import com.moyuan.buysense.domain.Requirement;
import com.moyuan.buysense.platform.CommerceDomainPack;
import com.moyuan.buysense.platform.DomainPackRegistry;
import com.moyuan.sar.data.JavaRetailDataPlane;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
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
    private static final Set<String> CHANNELS = Set.of("search", "recommendation", "ads");

    private final IntentParser parser;
    private final DomainPackRegistry domains;
    private final JavaRetailDataPlane dataPlane;
    private final ModelPortAgentBridge model;
    private final ObjectMapper mapper;
    private final Executor collaborationExecutor;

    public SearchAdsRecsLeadService(
            IntentParser parser,
            DomainPackRegistry domains,
            JavaRetailDataPlane dataPlane,
            ModelPortAgentBridge model,
            ObjectMapper mapper,
            @Qualifier("collaborationExecutor") Executor collaborationExecutor
    ) {
        this.parser = parser;
        this.domains = domains;
        this.dataPlane = dataPlane;
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
        CommerceDomainPack domain = domains.require(domainPackId);
        List<DecisionResult.TraceStep> trace = java.util.Collections.synchronizedList(new ArrayList<>());
        BoundedCollaborationCoordinator coordinator = new BoundedCollaborationCoordinator(
                runId,
                (role, event, detail) -> trace.add(new DecisionResult.TraceStep(role, event, detail)));
        List<ModelPortAgentBridge.RoleCall> roleCalls = new java.util.concurrent.CopyOnWriteArrayList<>();

        trace.add(new DecisionResult.TraceStep("lead", "run_started", Map.of(
                "messageChars", message.length(),
                "domainPackId", domain.packId(),
                "workflowId", domain.workflowId(),
                "capabilityProfileId", domain.capabilityProfileId())));

        Plan plan = coordinator.delegate(
                "lead", "intent_router", "understand_and_route", runId, 1, 0,
                () -> resolvePlan(runId, message, domain, discoveryContext, coordinator, roleCalls));

        CompletableFuture<ChannelResult> searchFuture = async(() -> runChannel(
                coordinator, roleCalls, plan, "search", "lead", runId,
                List.of(), false, true));
        CompletableFuture<ChannelResult> adsFuture = plan.channels().contains("ads")
                ? async(() -> runChannel(
                        coordinator, roleCalls, plan, "ads", "lead", runId,
                        List.of(), false, true))
                : CompletableFuture.completedFuture(null);

        ChannelResult search = join(searchFuture);
        List<JsonNode> primaryProducts = search.items().stream()
                .filter(item -> item.path("category").asText().equals(domain.primaryCategory()))
                .toList();

        BoundedCollaborationCoordinator.DelegationProposal handoff = null;
        ModelPortAgentBridge.RoleCall searchCall = roleCalls.stream()
                .filter(call -> call.role().equals("search"))
                .findFirst().orElse(null);
        if (searchCall != null && searchCall.success()
                && searchCall.proposal().path("handoff").asBoolean(false)) {
            handoff = coordinator.proposeDelegation(
                    "search", "recommendation", "recommendation_strategy_and_retrieval",
                    search.taskId(), "primary_product_grounding");
        }
        BoundedCollaborationCoordinator.DelegationProposal approvedHandoff = coordinator.takeApprovedProposal(
                "search", "recommendation", "recommendation_strategy_and_retrieval");
        if (plan.channels().contains("recommendation")) {
            trace.add(new DecisionResult.TraceStep(
                    approvedHandoff == null ? "lead" : "search",
                    approvedHandoff == null ? "handoff_fallback_scheduled" : "peer_delegated",
                    Map.of(
                            "to", "recommendation",
                            "candidateCount", primaryProducts.size(),
                            "schedulingMode", approvedHandoff == null
                                    ? "retrieval_plan_fallback"
                                    : "model_proposed_coordinator_approved")));
        }

        ChannelResult recommendation = plan.channels().contains("recommendation")
                ? runChannel(
                        coordinator, roleCalls, plan, "recommendation",
                        approvedHandoff == null ? "lead" : "search", search.taskId(),
                        primaryProducts, false, true)
                : null;
        ChannelResult ads = join(adsFuture);

        List<ChannelResult> channels = orderedChannels(plan, search, recommendation, ads);
        DecisionBuild decision = buildDecision(
                coordinator, plan, channels, domain, runId, trace);

        Audit audit = audit(plan, channels, decision, domain);
        DecisionResult withAudit = withAudit(decision.result(), audit);
        DecisionResult criticInput = withAudit;
        ModelPortAgentBridge.RoleCall criticCall = coordinator.delegate(
                "lead", "critic", "independent_decision_audit", decision.parentTaskId(), 2, 0,
                () -> {
                    coordinator.consumeModelCall("critic");
                    ModelPortAgentBridge.RoleCall call = model.reviewDecision(
                            runId, message, criticInput, 5);
                    roleCalls.add(call);
                    return call;
                });
        audit = mergeCritic(audit, criticCall, decision);
        withAudit = withAudit(decision.result(), audit);

        boolean revised = false;
        Set<String> missing = missingRequestedCategories(plan, decision.selectedItems());
        if (audit.violations().contains("requested_category_coverage")
                && plan.channels().contains("recommendation")
                && !missing.isEmpty()
                && hasFeasibleMissingCategory(
                        decision.slateItems(),
                        missing,
                        plan.budgetMax(),
                        decision.result().bundles().stream()
                                .findFirst()
                                .map(DecisionResult.BundleProposal::totalPrice)
                                .orElse(BigDecimal.ZERO))) {
            revised = true;
            trace.add(new DecisionResult.TraceStep("critic", "peer_delegated", Map.of(
                    "to", "recommendation", "attempt", 1,
                    "instruction", "expand_candidates_without_relaxing_hard_constraints")));
            Plan expanded = plan.withRecommendationBudget(
                    Math.min(20, Math.max(4, plan.candidateBudget().get("recommendation") * 2)));
            ChannelResult revisedRecommendation = runChannel(
                    coordinator, roleCalls, expanded, "recommendation", "critic", runId,
                    primaryProducts, true, false);
            channels = channels.stream()
                    .map(item -> item.channel().equals("recommendation") ? revisedRecommendation : item)
                    .toList();
            decision = buildDecision(coordinator, plan, channels, domain, runId, trace);
            audit = audit(plan, channels, decision, domain);
            withAudit = withAudit(decision.result(), audit);
            trace.add(new DecisionResult.TraceStep("critic", "deterministic_reaudit", Map.of(
                    "verdict", audit.approved() ? "approved" : "vetoed", "attempt", 2)));
        }

        String deterministicMessage = renderMessage(decision, audit, plan);
        boolean finalApproved = audit.approved();
        ModelPortAgentBridge.RoleCall leadCall = coordinator.delegate(
                "critic", "lead", "grounded_response_composition", decision.parentTaskId(), 2, 0,
                () -> {
                    coordinator.consumeModelCall("lead");
                    ModelPortAgentBridge.RoleCall call = model.compose(
                            runId, deterministicMessage, finalApproved, 6);
                    roleCalls.add(call);
                    return call;
                });
        String finalMessage = resolveFinalMessage(
                deterministicMessage, finalApproved, decision, leadCall);

        DecisionResult.ModelRuntime runtime = runtime(
                roleCalls, plan, finalMessage, audit, revised, coordinator);
        DecisionResult result = withAudit.withRuntime(runtime);
        trace.add(new DecisionResult.TraceStep("lead", "run_completed", Map.of(
                "verdict", audit.approved() ? "approved" : "vetoed",
                "collaborationTasks", coordinator.tasks().size(),
                "delegationProposals", coordinator.proposals().size(),
                "modelCalls", coordinator.modelCalls())));
        result = new DecisionResult(
                result.requirement(), result.slate(), result.bundles(),
                List.copyOf(trace), result.metrics(), result.runtime());
        return new Execution(
                result,
                List.copyOf(roleCalls),
                coordinator.tasks(),
                coordinator.proposals(),
                revised);
    }

    private Plan resolvePlan(
            String runId,
            String message,
            CommerceDomainPack domain,
            DiscoveryContext discoveryContext,
            BoundedCollaborationCoordinator coordinator,
            List<ModelPortAgentBridge.RoleCall> calls
    ) {
        Requirement baseline = parser.parse(message, domain.packId());
        coordinator.consumeModelCall("intent_router");
        ModelPortAgentBridge.RoleCall call = model.plan(runId, message, 1);
        calls.add(call);

        String proposedQuery = call.success() ? call.text("query") : null;
        String query = proposedQuery == null || proposedQuery.isBlank() || proposedQuery.length() > 240
                ? baseline.retrievalQuery()
                : proposedQuery.replaceAll("\\s+", " ").trim();
        LinkedHashSet<String> useCases = new LinkedHashSet<>(baseline.useCases());
        if (call.success()) {
            call.strings("useCases", 20).stream().filter(domain.useCases()::contains).forEach(useCases::add);
        }
        List<Requirement.Constraint> constraints = new ArrayList<>(baseline.constraints());
        useCases.stream().filter(value -> !baseline.useCases().contains(value)).forEach(value ->
                constraints.add(new Requirement.Constraint(
                        "constraint-inferred-use-case-" + constraints.size(),
                        "useCases",
                        value,
                        Requirement.ConstraintSource.INFERRED_MODEL,
                        Requirement.ConstraintStrength.SOFT,
                        0.65,
                        "turn-current",
                        Requirement.ConstraintStatus.ACTIVE)));
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

        String baselineIntent = effective.bundleRequested() ? "bundle" : inferIntent(message, domain);
        String proposedIntent = call.success() ? call.text("intent") : null;
        String intent = baselineIntent.equals("catalog")
                && proposedIntent != null
                && Set.of("precise", "catalog", "exploratory", "bundle", "compare")
                        .contains(proposedIntent)
                ? proposedIntent : baselineIntent;
        LinkedHashSet<String> channels = new LinkedHashSet<>();
        if (call.success()) {
            call.strings("channels", 3).stream().filter(CHANNELS::contains).forEach(channels::add);
        } else {
            channels.addAll(List.of("search", "recommendation"));
            if (effective.sponsoredAllowed()) channels.add("ads");
        }
        channels.add("search");
        if (baselineIntent.equals("bundle")) channels.add("recommendation");
        if (!effective.sponsoredAllowed()) channels.remove("ads");

        Map<String, Integer> budgets = new LinkedHashMap<>(Map.of(
                "search", 8, "recommendation", 8, "ads", 4));
        JsonNode proposedBudgets = call.success() ? call.proposal().path("candidateBudget") : null;
        for (String channel : CHANNELS) {
            if (proposedBudgets == null || !proposedBudgets.path(channel).isNumber()) continue;
            int limit = channel.equals("ads") ? 4 : 12;
            budgets.put(channel, Math.max(1, Math.min(limit, proposedBudgets.path(channel).asInt())));
        }
        String reason = call.success() && call.text("reason") != null
                ? call.text("reason")
                : "intent=" + intent + "; categories="
                        + String.join(",", effective.requiredCategories()) + "; channels="
                        + String.join(",", channels);
        return new Plan(
                runId,
                domain.packId(),
                effective,
                intent,
                List.copyOf(channels),
                Map.copyOf(budgets),
                reason,
                discoveryContext);
    }

    private ChannelResult runChannel(
            BoundedCollaborationCoordinator coordinator,
            List<ModelPortAgentBridge.RoleCall> calls,
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
                2,
                revision ? 1 : 0,
                () -> {
                    ObjectNode request = discoveryRequest(plan, channel, primaryProducts);
                    Map<String, Object> raw;
                    try {
                        raw = dataPlane.discovery(plan.domainPackId(),
                                channel,
                                request);
                    } catch (RuntimeException error) {
                        if (channel.equals("search")) throw error;
                        return new ChannelResult(channel, List.of(), emptyChannel(channel), parentTaskId);
                    }
                    ObjectNode response = mapper.valueToTree(raw);
                    JsonNode provenance = response.path("data_source");
                    response.withArray("items").forEach(item -> {
                        if (item instanceof ObjectNode object && provenance.isObject()) {
                            object.set("data_source", provenance.deepCopy());
                        }
                    });
                    List<JsonNode> originalItems = array(response.path("items"));
                    String taskId = parentTaskId + ":" + channel;
                    if (!useModel) return new ChannelResult(channel, originalItems, response, taskId);

                    coordinator.consumeModelCall(channel);
                    int ordinal = switch (channel) {
                        case "search" -> 2;
                        case "recommendation" -> 3;
                        case "ads" -> 4;
                        default -> throw new IllegalStateException("unknown channel");
                    };
                    ModelPortAgentBridge.RoleCall call = model.rank(
                            plan.runId(), channel, originalItems.stream().map(this::candidate).toList(), ordinal);
                    calls.add(call);
                    ObjectNode ranked = applyModelRanking(response, call);
                    return new ChannelResult(channel, array(ranked.path("items")), ranked, taskId);
                });
    }

    private DecisionBuild buildDecision(
            BoundedCollaborationCoordinator coordinator,
            Plan plan,
            List<ChannelResult> channels,
            CommerceDomainPack domain,
            String runId,
            List<DecisionResult.TraceStep> trace
    ) {
        ObjectNode fused = coordinator.delegate(
                "lead", "lead", "calibrated_candidate_fusion", runId, 2, 0,
                () -> {
                    ObjectNode request = mapper.createObjectNode();
                    request.put("domain_pack_id", domain.packId());
                    ArrayNode values = request.putArray("channels");
                    channels.forEach(channel -> values.add(channel.response()));
                    request.put("limit", 12);
                    return mapper.valueToTree(dataPlane.fuse(domain.packId(), request));
                });
        List<JsonNode> slateItems = array(fused.path("items"));

        ObjectNode optimized = coordinator.delegate(
                "lead", "compatibility", "constraint_bundle_optimization", runId, 2, 0,
                () -> {
                    ObjectNode request = mapper.createObjectNode();
                    request.put("domain_pack_id", domain.packId());
                    ArrayNode items = request.putArray("items");
                    slateItems.forEach(items::add);
                    ArrayNode requested = request.putArray("requested_categories");
                    plan.requirement().requiredCategories().forEach(requested::add);
                    request.put("intent", plan.intent());
                    if (plan.budgetMax() == null) request.putNull("budget_max");
                    else request.put("budget_max", plan.budgetMax());
                    request.put("top_n", 3);
                    return mapper.valueToTree(dataPlane.bundles(domain.packId(), request));
                });

        List<JsonNode> bundleNodes = array(optimized.path("bundles"));
        List<JsonNode> selectedItems = bundleNodes.isEmpty()
                ? List.of()
                : plan.intent().equals("compare")
                        ? resolveComparisonItems(slateItems, bundleNodes)
                        : resolveBundleItems(slateItems, strings(bundleNodes.get(0).path("sku_ids")));

        CompletableFuture<ObjectNode> quoteFuture = async(() -> coordinator.delegate(
                "compatibility", "pricing", "live_quote_tool", runId, 3, 0,
                () -> {
                    ObjectNode request = mapper.createObjectNode();
                    request.put("domain_pack_id", domain.packId());
                    ArrayNode offerIds = request.putArray("offer_ids");
                    selectedItems.forEach(item -> offerIds.add(item.path("offer_id").asText()));
                    return mapper.valueToTree(dataPlane.quote(domain.packId(), request));
                }));
        CompletableFuture<ObjectNode> reviewFuture = async(() -> coordinator.delegate(
                "compatibility", "review_evidence", "review_aspect_tool", runId, 3, 0,
                () -> {
                    ObjectNode request = mapper.createObjectNode();
                    request.put("domain_pack_id", domain.packId());
                    ArrayNode productIds = request.putArray("product_ids");
                    selectedItems.stream().map(item -> item.path("product_id").asText()).distinct()
                            .forEach(productIds::add);
                    return mapper.valueToTree(dataPlane.reviews(domain.packId(), request));
                }));
        ObjectNode quotes = join(quoteFuture);
        ObjectNode reviews = join(reviewFuture);
        applyLiveQuotes(selectedItems, quotes);

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

        DecisionResult result = new DecisionResult(
                plan.requirement(),
                slate,
                bundles,
                List.copyOf(trace),
                Map.copyOf(metrics),
                DecisionResult.offlineRuntime());
        return new DecisionBuild(result, slateItems, selectedItems, optimized, quotes, reviews, runId);
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
                ? plan.requirement().requiredCategories().stream()
                        .filter(domain.defaultBundleCategories()::contains)
                        .collect(Collectors.toCollection(LinkedHashSet::new))
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
        if (plan.intent().equals("bundle") && plan.budgetMax() != null) {
            BigDecimal utilization = total.divide(plan.budgetMax(), 4, RoundingMode.HALF_UP);
            checks.put("budget_utilization_reasonable",
                    requestsLowCost(plan) || utilization.compareTo(new BigDecimal("0.65")) >= 0);
        }
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

    private Audit mergeCritic(
            Audit deterministic,
            ModelPortAgentBridge.RoleCall critic,
            DecisionBuild decision
    ) {
        if (!critic.success()) return deterministic;
        Set<String> supported = new LinkedHashSet<>();
        double averageConfidence = array(decision.reviews().path("products")).stream()
                .flatMap(product -> array(product.path("aspects")).stream())
                .mapToDouble(aspect -> aspect.path("confidence").asDouble()).average().orElse(0);
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

        LinkedHashSet<String> violations = new LinkedHashSet<>(deterministic.violations());
        critic.strings("additionalViolations", 8).stream().filter(supported::contains)
                .forEach(violations::add);
        String rationale = critic.text("rationale") == null
                ? deterministic.rationale() : critic.text("rationale");
        return new Audit(violations.isEmpty(), List.copyOf(violations), deterministic.checks(),
                critic.text("verdict"), rationale);
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
            List<ModelPortAgentBridge.RoleCall> calls,
            Plan plan,
            String finalMessage,
            Audit audit,
            boolean revised,
            BoundedCollaborationCoordinator coordinator
    ) {
        List<ModelPortAgentBridge.RoleCall> attempted = calls.stream()
                .filter(ModelPortAgentBridge.RoleCall::attempted).toList();
        return new DecisionResult.ModelRuntime(
                model.status().mode(),
                model.status().model(),
                attempted.size(),
                (int) attempted.stream().filter(call -> !call.success()).count(),
                attempted.stream().mapToInt(ModelPortAgentBridge.RoleCall::totalTokens).sum(),
                attempted.stream().mapToLong(ModelPortAgentBridge.RoleCall::latencyMs).sum(),
                plan.requirement().retrievalQuery(),
                plan.intent(),
                finalMessage,
                audit.approved() ? "approved" : "vetoed",
                revised);
    }

    private String renderMessage(DecisionBuild decision, Audit audit, Plan plan) {
        if (!audit.approved()) {
            return "方案未通过独立审核：" + String.join("、", audit.violations()) + "。没有生成可确认草案。";
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
        lines.add("价格批次：" + decision.quotes().path("quote_batch_id").asText()
                + "；确认前仍需刷新库存与报价。");
        lines.add("交易边界：本次只生成购买建议，没有创建订单，也没有发起支付；任何购买动作都需要用户再次确认。");
        return String.join("\n", lines);
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
        for (String required : List.of(
                "对比候选", "购买方案", "推荐依据", "取舍", "套装合计",
                "预算利用率", "兼容依据", "交易边界", "没有创建订单", "没有发起支付")) {
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

    private ObjectNode applyModelRanking(ObjectNode response, ModelPortAgentBridge.RoleCall call) {
        if (!call.success()) return response;
        List<JsonNode> items = array(response.path("items"));
        Map<String, Integer> rank = new LinkedHashMap<>();
        int index = 0;
        for (String id : call.strings("rankedSkuIds", 50)) {
            if (items.stream().anyMatch(item -> productId(item).equals(id))) rank.putIfAbsent(id, index++);
        }
        if (rank.isEmpty()) return response;
        List<ObjectNode> adjusted = new ArrayList<>();
        for (int originalIndex = 0; originalIndex < items.size(); originalIndex++) {
            ObjectNode item = (ObjectNode) items.get(originalIndex).deepCopy();
            int modelIndex = rank.getOrDefault(productId(item), rank.size() + originalIndex);
            double modelScore = Math.max(0, 1 - modelIndex / (double) Math.max(1, items.size()));
            double score = item.path("normalized_score").asDouble();
            item.put("normalized_score", round6(score * 0.8 + modelScore * 0.2));
            item.put("_model_index", modelIndex);
            adjusted.add(item);
        }
        adjusted.sort(Comparator.comparingInt(item -> item.path("_model_index").asInt()));
        ArrayNode output = response.putArray("items");
        adjusted.forEach(item -> {
            item.remove("_model_index");
            output.add(item);
        });
        return response;
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
                item.path("quote_valid_until").asText(""));
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
        List<String> facts = strings(item.path("decision_facts"));
        String matchedFact = facts.stream()
                .filter(fact -> plan.requirement().useCases().stream().anyMatch(useCase ->
                        fact.contains(useCase) || useCase.contains(fact)))
                .findFirst().orElse(facts.stream().findFirst().orElse(""));
        JsonNode review = array(reviews.path("products")).stream()
                .filter(product -> product.path("product_id").asText()
                        .equals(item.path("product_id").asText()))
                .findFirst().orElse(null);
        List<JsonNode> aspects = review == null ? List.of() : array(review.path("aspects"));
        JsonNode matchedAspect = aspects.stream()
                .filter(aspect -> plan.requirement().useCases().stream().anyMatch(useCase -> {
                    String name = aspect.path("aspect").asText();
                    return name.contains(useCase) || useCase.contains(name);
                }))
                .findFirst()
                .orElse(aspects.stream().filter(aspect -> aspect.path("sentiment").asDouble() >= 0.6)
                        .findFirst().orElse(null));
        List<String> evidence = new ArrayList<>();
        String chargingEvidence = chargingEvidence(item, plan);
        if (!chargingEvidence.isBlank()) evidence.add(chargingEvidence);
        if (!matchedFact.isBlank()) evidence.add(matchedFact);
        if (matchedAspect != null && !matchedAspect.path("summary").asText().isBlank()) {
            evidence.add(matchedAspect.path("summary").asText());
        }
        if (evidence.isEmpty()) {
            List<String> matchedTags = strings(item.path("tags")).stream()
                    .filter(tag -> plan.requirement().useCases().stream().anyMatch(useCase ->
                            tag.contains(useCase) || useCase.contains(tag)))
                    .toList();
            if (!matchedTags.isEmpty()) evidence.add("目录标签与需求匹配：" + String.join("、", matchedTags));
        }
        return evidence.isEmpty() ? "商品快照与评价证据完整，但当前需求没有更细的属性证据"
                : String.join("；", evidence.stream().limit(2).toList());
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
        if (!tradeoffs.isEmpty()) return tradeoffs.get(0);
        return array(reviews.path("products")).stream()
                .filter(product -> product.path("product_id").asText()
                        .equals(item.path("product_id").asText()))
                .flatMap(product -> array(product.path("aspects")).stream())
                .filter(aspect -> aspect.path("sentiment").asDouble() < 0.6)
                .map(aspect -> aspect.path("summary").asText())
                .filter(summary -> !summary.isBlank())
                .findFirst()
                .orElse("结论依赖当前目录、评价和报价快照，未覆盖线下手感与长期耐久差异");
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

    private boolean requestsLowCost(Plan plan) {
        String query = plan.requirement().retrievalQuery().toLowerCase(Locale.ROOT);
        return List.of("便宜", "省钱", "最低价", "性价比", "够用", "低价")
                .stream().anyMatch(query::contains);
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

    private Set<String> missingRequestedCategories(Plan plan, List<JsonNode> selected) {
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

    private String inferIntent(String message, CommerceDomainPack domain) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("对比") || normalized.contains("比较")) return "compare";
        if (normalized.contains("推荐") || normalized.contains("适合")
                || normalized.contains("怎么选") || normalized.contains("帮我选")) return "exploratory";
        if (domain.protocolTerms().stream().anyMatch(normalized::contains)) return "precise";
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

    private <T> CompletableFuture<T> async(java.util.concurrent.Callable<T> callable) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return callable.call();
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, collaborationExecutor);
    }

    private <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw error;
        }
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
            boolean revisionApplied
    ) {
    }

    private record Plan(
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
            String taskId
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
