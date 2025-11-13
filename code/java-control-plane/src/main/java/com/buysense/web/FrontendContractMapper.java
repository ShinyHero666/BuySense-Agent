package com.buysense.web;

import com.buysense.domain.Candidate;
import com.buysense.domain.DecisionResult;
import com.buysense.domain.Product;
import com.buysense.domain.Requirement;
import com.buysense.run.AgentRun;
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
        return new RunView(
                run.getRunId(),
                run.getIdentityId(),
                run.getSessionId(),
                run.getDomainPackId(),
                run.getWorkflowId(),
                run.getStatus(),
                run.getMessage(),
                run.isConfirmationRequested(),
                run.getProposalRunId(),
                run.getIdempotencyKey(),
                reply,
                run.getErrorCode(),
                run.getCreatedAt(),
                run.getUpdatedAt(),
                run.isCancellationRequested());
    }

    public BuyerReply proposal(DecisionResult result) {
        Decision decision = decision(result);
        return new BuyerReply(
                Boolean.TRUE.equals(result.metrics().get("clarificationRequired")) ? "clarification"
                        : decision.critique().verdict().equals("approved") ? "proposal" : "needs_replan",
                decision.message(),
                decision,
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
        List<String> channels = result.trace().stream()
                .filter(trace -> "task_completed".equals(trace.decision()))
                .map(trace -> trace.stage())
                .filter(Set.of("search", "recommendation", "ads")::contains)
                .distinct().toList();

        List<ConstraintView> constraints = requirement.constraints().stream()
                .map(constraint -> new ConstraintView(
                        constraint.constraintId(),
                        constraint.field(),
                        constraint.value(),
                        constraint.source().name().toLowerCase(),
                        constraint.strength().name().toLowerCase(),
                        constraint.confidence(),
                        constraint.turnId(),
                        constraint.status().name().toLowerCase()))
                .toList();
        Plan plan = new Plan(
                requirement.originalQuery(),
                hasText(modelRuntime.rewrittenQuery())
                        ? modelRuntime.rewrittenQuery()
                        : requirement.originalQuery(),
                hasText(modelRuntime.intent())
                        ? modelRuntime.intent()
                        : requirement.bundleRequested() ? "bundle" : "catalog",
                List.copyOf(channels),
                requirement.sponsoredAllowed(),
                new Requirements(
                        requirement.budget(),
                        requirement.preferredBrands(),
                        requirement.requiredCategories(),
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
                        modelRuntime.totalTokens(),
                        modelRuntime.provider(),
                        modelRuntime.localOnly(),
                        modelRuntime.proposalAccepted(),
                        modelRuntime.proposalCorrected(),
                        modelRuntime.roleExecutions()));
    }

    private BundleView bundle(DecisionResult result, double maxScore) {
        if (!result.bundles().isEmpty()) {
            DecisionResult.BundleProposal primary = result.bundles().get(0);
            List<CandidateView> items = primary.items().stream()
                    .map(product -> candidateForProduct(product, result.slate(), maxScore))
                    .toList();
            List<Alternative> alternatives = result.bundles().stream().skip(1)
                    .map(proposal -> new Alternative(
                            proposal.items().stream().map(Product::skuId).toList(),
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
        if (Boolean.TRUE.equals(result.metrics().get("clarificationRequired"))) {
            return new Critique("vetoed", List.of("requirement_clarification_required"), Map.of());
        }
        Map<String, Boolean> checks = new LinkedHashMap<>();
        Object rawChecks = result.metrics().get("criticChecks");
        if (rawChecks instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if (key != null && value instanceof Boolean passed) {
                    checks.put(key.toString(), passed);
                }
            });
        }
        List<String> violations = new ArrayList<>();
        Object rawViolations = result.metrics().get("criticViolations");
        if (rawViolations instanceof List<?> values) {
            values.stream().filter(String.class::isInstance).map(String.class::cast)
                    .forEach(violations::add);
        }
        String verdict = String.valueOf(result.metrics().getOrDefault(
                "criticVerdict", violations.isEmpty() ? "approved" : "vetoed"));
        if (checks.isEmpty()) {
            boolean candidateAvailable = !result.slate().isEmpty();
            boolean budget = !bundle.items().isEmpty() && bundle.withinBudget();
            checks.put("candidate_available", candidateAvailable);
            checks.put("budget_respected", budget);
            if (!candidateAvailable) violations.add("candidate_available");
            if (!budget) violations.add("budget_respected");
            verdict = violations.isEmpty() ? "approved" : "vetoed";
        }
        return new Critique(verdict, List.copyOf(violations), Map.copyOf(checks));
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
        return new ProductView(product.productId(), product.skuId(), product.name(), product.category(),
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
            String idempotencyKey,
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
            Object value,
            String source,
            String strength,
            double confidence,
            String turnId,
            String status
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
            int totalTokens,
            String provider,
            boolean localOnly,
            int proposalAccepted,
            int proposalCorrected,
            List<DecisionResult.RoleExecution> roleExecutions
    ) {
    }
}
