package com.moyuan.buysense.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.domain.Candidate;
import com.moyuan.buysense.domain.DecisionResult;
import com.moyuan.buysense.platform.DomainPackRegistry;
import com.moyuan.buysense.retail.RetailDataGateway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class QualityService {
    private final List<EvaluationCase> cases;
    private final Report report;

    public QualityService(
            IntentParser parser,
            ExecutionRouter router,
            DecisionEngine engine,
            RetailDataGateway retail,
            ObjectMapper mapper
    ) {
        this.cases = loadCases(mapper);
        this.report = evaluate(
                parser,
                router,
                engine,
                retail.localCatalogVersion(DomainPackRegistry.DEFAULT_PACK_ID),
                retail.localSpuCount(DomainPackRegistry.DEFAULT_PACK_ID),
                cases);
    }

    public Report report() {
        return report;
    }

    public List<EvaluationCase> cases() {
        return cases;
    }

    private Report evaluate(
            IntentParser parser,
            ExecutionRouter router,
            DecisionEngine engine,
            String catalogVersion,
            int spuCount,
            List<EvaluationCase> evaluationCases
    ) {
        List<Long> latencies = new ArrayList<>();
        double categoryRecallTotal = 0;
        int categoryCases = 0;
        int routeMatches = 0;
        int completed = 0;
        int actionableCases = 0;
        int truePositiveClarifications = 0;
        int falsePositiveClarifications = 0;
        int falseNegativeClarifications = 0;
        int hardConstraintViolations = 0;
        int adPolicyViolations = 0;

        for (EvaluationCase evaluationCase : evaluationCases) {
            long started = System.nanoTime();
            var requirement = parser.parse(evaluationCase.query());
            var route = router.route(requirement);
            DecisionResult result = engine.decide(requirement);
            latencies.add(Math.max(1, (System.nanoTime() - started) / 1_000_000));

            if (route.mode().name().equals(evaluationCase.expectedMode())) routeMatches++;
            if (route.clarificationRecommended() && evaluationCase.clarification()) {
                truePositiveClarifications++;
            } else if (route.clarificationRecommended()) {
                falsePositiveClarifications++;
            } else if (evaluationCase.clarification()) {
                falseNegativeClarifications++;
            }

            if (!evaluationCase.clarification()) {
                actionableCases++;
                boolean taskCompleted = !result.slate().isEmpty()
                        && (!evaluationCase.bundle() || !result.bundles().isEmpty());
                if (taskCompleted) completed++;
            }

            if (!evaluationCase.expectedCategories().isEmpty() && !evaluationCase.clarification()) {
                List<Candidate> top = result.slate().stream().limit(10).toList();
                Set<String> found = top.stream()
                        .map(candidate -> candidate.product().category())
                        .filter(evaluationCase.expectedCategories()::contains)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                categoryRecallTotal += found.size()
                        / (double) evaluationCase.expectedCategories().size();
                categoryCases++;
            }

            boolean wrongCategory = result.slate().stream().limit(10)
                    .anyMatch(candidate -> !requirement.requiredCategories().isEmpty()
                            && !requirement.requiredCategories().contains(candidate.product().category()));
            boolean invalidBundle = result.bundles().stream()
                    .anyMatch(bundle -> !bundle.budgetSatisfied() || !bundle.compatible());
            boolean overBudgetProduct = requirement.budget() != null
                    && result.slate().stream().anyMatch(candidate ->
                            candidate.product().price().compareTo(requirement.budget()) > 0);
            if (wrongCategory || invalidBundle || overBudgetProduct) {
                hardConstraintViolations++;
            }

            long sponsoredTopThree = result.slate().stream().limit(3)
                    .filter(Candidate::sponsored)
                    .count();
            boolean rejectedAdsPresent = evaluationCase.rejectAds()
                    && result.slate().stream().anyMatch(Candidate::sponsored);
            if (sponsoredTopThree > 1 || rejectedAdsPresent) adPolicyViolations++;
        }

        latencies.sort(Comparator.naturalOrder());
        long p95 = latencies.get(Math.max(0, (int) Math.ceil(latencies.size() * 0.95) - 1));
        double categoryRecall = categoryCases == 0 ? 1.0 : categoryRecallTotal / categoryCases;
        double routeAccuracy = routeMatches / (double) evaluationCases.size();
        double taskCompletionRate = actionableCases == 0 ? 1.0 : completed / (double) actionableCases;
        double clarificationPrecision = ratio(
                truePositiveClarifications,
                truePositiveClarifications + falsePositiveClarifications);
        double clarificationRecall = ratio(
                truePositiveClarifications,
                truePositiveClarifications + falseNegativeClarifications);
        double clarificationF1 = clarificationPrecision + clarificationRecall == 0
                ? 0
                : 2 * clarificationPrecision * clarificationRecall
                        / (clarificationPrecision + clarificationRecall);

        Metrics metrics = new Metrics(
                categoryRecall,
                routeAccuracy,
                taskCompletionRate,
                clarificationPrecision,
                clarificationRecall,
                clarificationF1,
                p95,
                hardConstraintViolations,
                adPolicyViolations);
        boolean passed = evaluationCases.size() >= 40
                && categoryRecall >= 0.9
                && routeAccuracy >= 0.9
                && taskCompletionRate >= 0.9
                && clarificationF1 >= 0.9
                && hardConstraintViolations == 0
                && adPolicyViolations == 0;

        return new Report(
                "buysense-java-human-authored-regression",
                "human_authored_business_cases",
                "40 manually specified scenarios; no labels derived from catalog attributes",
                Instant.now(),
                catalogVersion,
                evaluationCases.size(),
                spuCount,
                metrics,
                passed);
    }

    private static List<EvaluationCase> loadCases(ObjectMapper mapper) {
        try (var input = new ClassPathResource("evaluation-cases.json").getInputStream()) {
            return List.copyOf(mapper.readValue(input, new TypeReference<List<EvaluationCase>>() {
            }));
        } catch (IOException error) {
            throw new IllegalStateException("failed to load evaluation-cases.json", error);
        }
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 1.0 : numerator / (double) denominator;
    }

    public record EvaluationCase(
            String id,
            String query,
            Set<String> expectedCategories,
            String expectedMode,
            boolean clarification,
            boolean bundle,
            boolean rejectAds
    ) {
    }

    public record Report(
            String suite,
            String evaluation_kind,
            String label_provenance,
            Instant generated_at,
            String catalog_version,
            int case_count,
            int spu_count,
            Metrics metrics,
            boolean passed
    ) {
    }

    public record Metrics(
            double category_recall_at_10,
            double route_accuracy,
            double task_completion_rate,
            double clarification_precision,
            double clarification_recall,
            double clarification_f1,
            long p95_latency_ms,
            int hard_constraint_violations,
            int ad_policy_violations
    ) {
    }
}
