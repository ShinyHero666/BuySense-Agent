package com.moyuan.buysense.evaluation;

import com.moyuan.buysense.agent.SearchAdsRecsLeadService;
import com.moyuan.buysense.evaluation.EvaluationReport.Aggregate;
import com.moyuan.buysense.evaluation.EvaluationReport.AssertionResult;
import com.moyuan.buysense.evaluation.EvaluationReport.FailureCategory;
import com.moyuan.buysense.evaluation.EvaluationReport.ManualReviewItem;
import com.moyuan.buysense.evaluation.EvaluationReport.Severity;
import com.moyuan.buysense.evaluation.EvaluationReport.TrialResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class EvaluationHarness {
    public static final String EVALUATOR_VERSION = "buysense-task-eval-v1";

    private final SearchAdsRecsLeadService agent;
    private final DeterministicEvaluationGrader grader;

    public EvaluationHarness(
            SearchAdsRecsLeadService agent,
            DeterministicEvaluationGrader grader
    ) {
        this.agent = agent;
        this.grader = grader;
    }

    public EvaluationReport run(EvaluationSuite suite) {
        List<TrialResult> trials = new ArrayList<>();
        List<ManualReviewItem> manualReviewQueue = new ArrayList<>();

        for (EvaluationCase evaluationCase : suite.cases()) {
            for (int trialIndex = 1; trialIndex <= evaluationCase.trials(); trialIndex++) {
                String runId = runId(suite.suiteId(), evaluationCase.caseId(), trialIndex);
                long started = System.nanoTime();
                TrialResult trial;
                try {
                    SearchAdsRecsLeadService.Execution execution = agent.decide(
                            runId,
                            evaluationCase.prompt(),
                            evaluationCase.domainPackId(),
                            discovery(evaluationCase.discovery()));
                    long latencyMs = elapsedMs(started);
                    trial = grader.grade(runId, evaluationCase, trialIndex, latencyMs, execution);
                } catch (RuntimeException error) {
                    long latencyMs = elapsedMs(started);
                    trial = grader.executionFailure(
                            runId, evaluationCase, trialIndex, latencyMs, error);
                }
                trials.add(trial);

                if (trialIndex == 1) {
                    for (int rubricIndex = 0;
                            rubricIndex < evaluationCase.manualReviewRubrics().size();
                            rubricIndex++) {
                        manualReviewQueue.add(new ManualReviewItem(
                                evaluationCase.caseId() + ":rubric-" + (rubricIndex + 1),
                                evaluationCase.caseId(),
                                runId,
                                evaluationCase.prompt(),
                                evaluationCase.manualReviewRubrics().get(rubricIndex),
                                trial.response(),
                                "PENDING",
                                ""));
                    }
                }
            }
        }

        Aggregate aggregate = aggregate(suite, trials, manualReviewQueue.size());
        String status;
        if (aggregate.passedTrials() != aggregate.trialCount()
                || aggregate.redlinePassRate() < 1.0) {
            status = "FAILED";
        } else if (!manualReviewQueue.isEmpty()) {
            status = "MANUAL_REVIEW_REQUIRED";
        } else {
            status = "PASSED";
        }
        return new EvaluationReport(
                "1.0",
                EVALUATOR_VERSION,
                suite.suiteId(),
                suite.systemVersion(),
                Instant.now(),
                status,
                aggregate,
                trials,
                manualReviewQueue);
    }

    private Aggregate aggregate(
            EvaluationSuite suite,
            List<TrialResult> trials,
            int manualReviewCount
    ) {
        int passedTrials = (int) trials.stream().filter(TrialResult::passed).count();
        Map<String, List<TrialResult>> byCase = trials.stream()
                .collect(Collectors.groupingBy(
                        TrialResult::caseId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        double passAtOne = ratio(
                byCase.values().stream().filter(values -> values.get(0).passed()).count(),
                byCase.size());
        double passAtK = ratio(
                byCase.values().stream().filter(values -> values.stream().anyMatch(TrialResult::passed)).count(),
                byCase.size());
        double passPowerK = ratio(
                byCase.values().stream().filter(values -> values.stream().allMatch(TrialResult::passed)).count(),
                byCase.size());

        List<AssertionResult> blockingAssertions = trials.stream()
                .flatMap(value -> value.assertions().stream())
                .filter(value -> value.severity() != Severity.OBSERVATION)
                .toList();
        long passedAssertions = blockingAssertions.stream().filter(AssertionResult::passed).count();
        long redlineTrials = trials.stream().filter(TrialResult::redlinePassed).count();
        List<Long> latencies = trials.stream().map(TrialResult::wallLatencyMs).sorted().toList();

        Map<String, Long> failuresByCategory = trials.stream()
                .flatMap(value -> value.assertions().stream())
                .filter(Predicate.not(AssertionResult::passed))
                .filter(value -> value.severity() != Severity.OBSERVATION)
                .collect(Collectors.groupingBy(
                        value -> value.failureCategory().name(),
                        LinkedHashMap::new,
                        Collectors.counting()));
        for (FailureCategory category : FailureCategory.values()) {
            failuresByCategory.putIfAbsent(category.name(), 0L);
        }

        Map<String, List<TrialResult>> byTag = new LinkedHashMap<>();
        for (TrialResult trial : trials) {
            for (String tag : trial.tags()) {
                byTag.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(trial);
            }
        }
        Map<String, Double> rateByTag = new LinkedHashMap<>();
        byTag.forEach((tag, values) -> rateByTag.put(
                tag,
                ratio(values.stream().filter(TrialResult::passed).count(), values.size())));

        return new Aggregate(
                suite.cases().size(),
                trials.size(),
                passedTrials,
                ratio(passedTrials, trials.size()),
                passAtOne,
                passAtK,
                passPowerK,
                ratio(passedAssertions, blockingAssertions.size()),
                ratio(redlineTrials, trials.size()),
                percentile(latencies, 0.50),
                percentile(latencies, 0.90),
                average(trials.stream().mapToInt(TrialResult::taskCount).boxed().toList()),
                average(trials.stream().mapToInt(TrialResult::coordinatorModelCalls).boxed().toList()),
                average(trials.stream().mapToInt(TrialResult::totalTokens).boxed().toList()),
                failuresByCategory,
                rateByTag,
                manualReviewCount);
    }

    private static SearchAdsRecsLeadService.DiscoveryContext discovery(EvaluationCase.Discovery value) {
        return new SearchAdsRecsLeadService.DiscoveryContext(
                value.identityId(),
                value.sessionId(),
                value.personalizationEnabled(),
                value.recentProductIds(),
                value.excludedProductIds(),
                value.adExposureProductIds());
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String runId(String suiteId, String caseId, int trialIndex) {
        return "eval-" + safe(suiteId) + "-" + safe(caseId) + "-t" + trialIndex;
    }

    private static String safe(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator == 0) return 0.0;
        return round((double) numerator / denominator);
    }

    private static double average(List<Integer> values) {
        return values.isEmpty()
                ? 0.0
                : round(values.stream().mapToInt(Integer::intValue).average().orElse(0.0));
    }

    private static long percentile(List<Long> sortedValues, double quantile) {
        if (sortedValues.isEmpty()) return 0L;
        int index = (int) Math.ceil(quantile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
