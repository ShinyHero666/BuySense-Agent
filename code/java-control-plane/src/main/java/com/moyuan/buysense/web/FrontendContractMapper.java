package com.moyuan.buysense.web;

import com.moyuan.buysense.domain.Candidate;
import com.moyuan.buysense.domain.DecisionResult;
import com.moyuan.buysense.domain.Product;
import com.moyuan.buysense.domain.Requirement;
import com.moyuan.buysense.run.AgentRun;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class FrontendContractMapper {
    public RunView run(AgentRun run) {
        BuyerReply reply;
        if (run.getResult() != null && run.getCartDraft() != null) {
            AgentRun.CartDraftState draft = run.getCartDraft();
            reply = new BuyerReply(
                    "cart_draft",
                    "购物车草案已生成，尚未发起支付。",
                    decision(run.getResult()),
                    new CartDraft(
                            draft.draftId(),
                            draft.totalPrice(),
                            draft.expiresAt(),
                            draft.paymentAuthorized()));
        } else if (run.getResult() != null && "clarification".equals(run.getPhase())) {
            reply = clarification(run.getResult());
        } else if (run.getResult() != null) {
            reply = proposal(run.getResult());
        } else if ("no_pending_decision".equals(run.getPhase())
                && run.getStatus().equals("completed")) {
            reply = new BuyerReply(
                    "no_pending_decision",
                    "当前会话没有可确认的购买方案，请先完成一次决策。",
                    null,
                    null);
        } else {
            reply = null;
        }
        String errorCode = run.getError() == null ? null : "RUN_EXECUTION_FAILED";
        return new RunView(
                run.getRunId(),
                "anonymous:" + run.getSessionId(),
                run.getSessionId(),
                run.getDomainPackId(),
                run.getWorkflowId(),
                run.getStatus(),
                run.getMessage(),
                run.isConfirmationRequested(),
                run.getProposalRunId(),
                reply,
                errorCode,
                run.getCreatedAt(),
                run.getUpdatedAt(),
                run.isCancellationRequested());
    }

    public BuyerReply proposal(DecisionResult result) {
        Decision decision = decision(result);
        return new BuyerReply(
                decision.critique().verdict().equals("approved") ? "proposal" : "needs_replan",
                decision.message(),
                decision,
                null);
    }

    public BuyerReply clarification(DecisionResult result) {
        DecisionResult.ModelRuntime runtime = result.runtime() == null
                ? DecisionResult.offlineRuntime()
                : result.runtime();
        String question = hasText(runtime.clarificationQuestion())
                ? runtime.clarificationQuestion()
                : "请补充产品类别、使用场景和预算上限。";
        return new BuyerReply(
                "clarification",
                question,
                decision(result),
                null);
    }

    private Decision decision(DecisionResult result) {
        Requirement requirement = result.requirement();
        DecisionResult.ModelRuntime modelRuntime = result.runtime() == null
                ? DecisionResult.offlineRuntime()
                : result.runtime();
        double maxScore = result.slate().stream().mapToDouble(Candidate::score).max().orElse(1.0);
        List<CandidateView> slate = result.slate().stream()
                .map(candidate -> candidate(candidate, maxScore))
                .toList();
        BundleView bundle = bundle(result, maxScore);
        Critique critique = critique(result, bundle);
        List<String> channels = new ArrayList<>(List.of("search", "recommendation"));
        if (requirement.sponsoredAllowed()) channels.add("ads");

        List<ConstraintView> constraints = IntStream.range(0, requirement.constraints().size())
                .mapToObj(index -> {
                    Requirement.Constraint constraint = requirement.constraints().get(index);
                    return new ConstraintView(
                            "constraint-" + (index + 1),
                            constraint.field(),
                            constraint.source().name().toLowerCase(),
                            constraint.strength().name().toLowerCase(),
                            constraint.confidence());
                })
                .toList();
        Plan plan = new Plan(
                requirement.originalQuery(),
                hasText(modelRuntime.rewrittenQuery())
                        ? modelRuntime.rewrittenQuery()
                        : requirement.originalQuery(),
                hasText(modelRuntime.intent())
                        ? modelRuntime.intent()
                        : requirement.bundleRequested() ? "bundle_recommendation" : "product_recommendation",
                List.copyOf(channels),
                requirement.sponsoredAllowed(),
                new Requirements(
                        requirement.budget(),
                        requirement.preferredBrand().isBlank()
                                ? List.of()
                                : List.of(requirement.preferredBrand()),
                        requirement.requiredCategories().stream().sorted().toList(),
                        requirement.useCases(),
                        constraints));
        return new Decision(
                hasText(modelRuntime.explanation())
                        ? modelRuntime.explanation()
                        : critique.verdict().equals("approved")
                        ? "已完成候选召回、融合排序和硬约束审核。"
                        : "当前目录中没有找到满足全部硬约束的方案。",
                plan,
                slate,
                bundle,
                critique,
                new RuntimeInfo(
                        modelRuntime.mode(),
                        modelRuntime.model(),
                        modelRuntime.modelCalls(),
                        modelRuntime.fallbackCount(),
                        modelRuntime.totalTokens()));
    }

    private BundleView bundle(DecisionResult result, double maxScore) {
        if (!result.bundles().isEmpty()) {
            DecisionResult.BundleProposal primary = result.bundles().get(0);
            List<CandidateView> items = primary.items().stream()
                    .map(product -> candidateForProduct(product, result.slate(), maxScore))
                    .toList();
            List<Alternative> alternatives = result.bundles().stream().skip(1)
                    .map(proposal -> new Alternative(
                            proposal.items().stream().map(Product::id).toList(),
                            proposal.totalPrice(),
                            proposal.score(),
                            proposal.items().stream().filter(Product::sponsored).count()))
                    .toList();
            return new BundleView(items, primary.totalPrice(), primary.budgetSatisfied(),
                    primary.score(), "global_constraint_enumeration", alternatives);
        }
        if (!result.requirement().bundleRequested() && !result.slate().isEmpty()) {
            Candidate candidate = result.slate().get(0);
            boolean withinBudget = result.requirement().budget() == null
                    || candidate.product().price().compareTo(result.requirement().budget()) <= 0;
            return new BundleView(
                    List.of(candidate(candidate, maxScore)),
                    candidate.product().price(),
                    withinBudget,
                    candidate.score(),
                    "single_item_ranking",
                    List.of());
        }
        return new BundleView(List.of(), BigDecimal.ZERO, false, 0, "no_feasible_bundle", List.of());
    }

    private Critique critique(DecisionResult result, BundleView bundle) {
        Requirement requirement = result.requirement();
        List<String> violations = new ArrayList<>();
        boolean hasCandidates = !result.slate().isEmpty();
        boolean hasRequiredBundle = !requirement.bundleRequested() || !result.bundles().isEmpty();
        boolean budget = !bundle.items().isEmpty() && bundle.withinBudget();
        boolean compatibility = result.bundles().isEmpty()
                || result.bundles().get(0).compatible();
        boolean adPolicy = result.slate().stream().limit(3).filter(Candidate::sponsored).count() <= 1
                && (requirement.sponsoredAllowed()
                || result.slate().stream().noneMatch(Candidate::sponsored));
        if (!hasCandidates) violations.add("no_candidates");
        if (!hasRequiredBundle) violations.add("no_feasible_bundle");
        if (!budget) violations.add("budget_constraint");
        if (!compatibility) violations.add("compatibility_constraint");
        if (!adPolicy) violations.add("ad_policy");

        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("candidate_available", hasCandidates);
        checks.put("required_bundle", hasRequiredBundle);
        checks.put("budget", budget);
        checks.put("compatibility", compatibility);
        checks.put("ad_policy", adPolicy);
        checks.put("inventory", hasCandidates);
        return new Critique(violations.isEmpty() ? "approved" : "vetoed",
                List.copyOf(violations), Map.copyOf(checks));
    }

    private CandidateView candidateForProduct(Product product, List<Candidate> slate, double maxScore) {
        return slate.stream()
                .filter(candidate -> candidate.product().id().equals(product.id()))
                .findFirst()
                .map(candidate -> candidate(candidate, maxScore))
                .orElseGet(() -> new CandidateView(product(product), "bundle",
                        List.of("bundle"), 0, List.of("selected_by_bundle_optimizer"),
                        product.sponsored(), product.sponsored() ? "赞助" : null));
    }

    private CandidateView candidate(Candidate candidate, double maxScore) {
        double normalized = maxScore <= 0 ? 0 : Math.min(1.0, candidate.score() / maxScore);
        String channel = candidate.channels().isEmpty() ? "search" : candidate.channels().get(0);
        return new CandidateView(
                product(candidate.product()),
                channel,
                candidate.channels(),
                normalized,
                candidate.reasons(),
                candidate.sponsored(),
                candidate.sponsored() ? "赞助" : null);
    }

    private ProductView product(Product product) {
        return new ProductView(product.id(), product.id(), product.name(), product.category(),
                product.brand(), product.price(), product.stock(), product.tags(),
                product.source(), product.sourceVersion(), product.providerId());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record RunView(
            String runId,
            String identityId,
            String sessionId,
            String domainPackId,
            String workflowId,
            String status,
            String message,
            boolean confirmed,
            String proposalRunId,
            BuyerReply result,
            String errorCode,
            Instant createdAt,
            Instant updatedAt,
            boolean cancelRequested
    ) {
    }
    public record BuyerReply(String phase, String message, Decision decision, CartDraft cartDraft) {
    }

    public record CartDraft(
            String draftId,
            BigDecimal totalPrice,
            Instant expiresAt,
            boolean paymentAuthorized
    ) {
    }

    public record Decision(
            String message,
            Plan plan,
            List<CandidateView> slate,
            BundleView bundle,
            Critique critique,
            RuntimeInfo runtime
    ) {
    }

    public record Plan(
            String originalQuery,
            String query,
            String intent,
            List<String> channels,
            boolean sponsoredAllowed,
            Requirements requirements
    ) {
    }

    public record Requirements(
            BigDecimal budgetMax,
            List<String> preferredBrands,
            List<String> requestedCategories,
            List<String> useCases,
            List<ConstraintView> constraints
    ) {
    }

    public record ConstraintView(
            String constraintId,
            String field,
            String source,
            String strength,
            double confidence
    ) {
    }

    public record CandidateView(
            ProductView product,
            String channel,
            List<String> sources,
            double normalizedScore,
            List<String> reasons,
            boolean sponsored,
            String disclosure
    ) {
    }

    public record ProductView(
            String productId,
            String skuId,
            String title,
            String category,
            String brand,
            BigDecimal price,
            int stock,
            List<String> tags,
            String source,
            String sourceVersion,
            String providerId
    ) {
    }
    public record BundleView(
            List<CandidateView> items,
            BigDecimal totalPrice,
            boolean withinBudget,
            double score,
            String optimization,
            List<Alternative> alternatives
    ) {
    }

    public record Alternative(
            List<String> skuIds,
            BigDecimal totalPrice,
            double score,
            long sponsoredCount
    ) {
    }

    public record Critique(String verdict, List<String> violations, Map<String, Boolean> checks) {
    }

    public record RuntimeInfo(
            String mode,
            String model,
            int modelCalls,
            int fallbackCount,
            int totalTokens
    ) {
    }
}
