package com.buysense.evaluation;

import com.buysense.agent.SearchAdsRecsLeadService;
import com.buysense.evaluation.EvaluationCase.JudgeRubric;
import com.buysense.evaluation.EvaluationCase.RubricEnforcement;
import com.buysense.evaluation.EvaluationModelJudge.JudgeBatch;
import com.buysense.evaluation.EvaluationModelJudge.JudgeRequest;
import com.buysense.evaluation.EvaluationModelJudge.Judgment;
import com.buysense.evaluation.EvaluationModelJudge.Label;
import com.buysense.evaluation.EvaluationReport.Aggregate;
import com.buysense.evaluation.EvaluationReport.AssertionResult;
import com.buysense.evaluation.EvaluationReport.FailureCategory;
import com.buysense.evaluation.EvaluationReport.ManualReviewItem;
import com.buysense.evaluation.EvaluationReport.ModelJudgment;
import com.buysense.evaluation.EvaluationReport.Severity;
import com.buysense.evaluation.EvaluationReport.TrialResult;
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
    public static final String EVALUATOR_VERSION = "buysense-benchmark-eval-v2";

    private final String systemVariantId;
    private final DecisionSystem system;
    private final DeterministicEvaluationGrader grader;
    private final EvaluationModelJudge modelJudge;

    public EvaluationHarness(
            SearchAdsRecsLeadService agent,
            DeterministicEvaluationGrader grader
    ) {
        this("configured-system", agent::decide, grader, EvaluationModelJudge.disabled());
    }

    public EvaluationHarness(
            String systemVariantId,
            DecisionSystem system,
            DeterministicEvaluationGrader grader,
            EvaluationModelJudge modelJudge
    ) {
        if (systemVariantId == null || systemVariantId.isBlank()) {
            throw new IllegalArgumentException("systemVariantId is required");
        }
        this.systemVariantId = systemVariantId;
        this.system = system;
        this.grader = grader;
        this.modelJudge = modelJudge == null ? EvaluationModelJudge.disabled() : modelJudge;
    }

    @FunctionalInterface
    public interface DecisionSystem {
        SearchAdsRecsLeadService.Execution decide(
                String runId,
                String message,
                String domainPackId,
                SearchAdsRecsLeadService.DiscoveryContext discoveryContext);
    }

    public EvaluationReport run(EvaluationSuite suite) {
        List<TrialResult> trials = new ArrayList<>();
        List<ModelJudgment> modelJudgments = new ArrayList<>();
        List<ManualReviewItem> manualReviewQueue = new ArrayList<>();

        for (EvaluationCase evaluationCase : suite.cases()) {
            for (int trialIndex = 1; trialIndex <= evaluationCase.trials(); trialIndex++) {
                String runId = runId(suite.suiteId(), systemVariantId, evaluationCase.caseId(), trialIndex);
                long started = System.nanoTime();
                TrialResult trial;
                try {
                    SearchAdsRecsLeadService.Execution execution = system.decide(
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
                List<ModelJudgment> trialJudgments = judge(evaluationCase, trial);
                modelJudgments.addAll(trialJudgments);

                if (trialIndex == 1 && trial.passed()) {
                    Map<String, Object> reviewContext = manualReviewContext(evaluationCase, trial);
                    for (int rubricIndex = 0;
                            rubricIndex < evaluationCase.manualReviewRubrics().size();
                            rubricIndex++) {
                        manualReviewQueue.add(new ManualReviewItem(
                                evaluationCase.caseId() + ":rubric-" + (rubricIndex + 1),
                                evaluationCase.caseId(),
                                runId,
                                evaluationCase.prompt(),
                                reviewContext,
                                evaluationCase.manualReviewRubrics().get(rubricIndex),
                                trial.response(),
                                "PENDING",
                                ""));
                    }
                    for (JudgeRubric rubric : evaluationCase.judgeRubrics()) {
                        if (!rubric.humanCalibrationRequired()) continue;
                        Map<String, Object> calibration = new LinkedHashMap<>(reviewContext);
                        calibration.put("reviewType", "BLINDED_JUDGE_CALIBRATION");
                        calibration.put("judgeRubricId", rubric.rubricId());
                        calibration.put("minimumScore", rubric.minimumScore());
                        calibration.put("modelDecisionHiddenUntilHumanLabel", true);
                        manualReviewQueue.add(new ManualReviewItem(
                                evaluationCase.caseId() + ":judge:" + rubric.rubricId(),
                                evaluationCase.caseId(),
                                runId,
                                evaluationCase.prompt(),
                                calibration,
                                rubric.criterion(),
                                trial.response(),
                                "PENDING",
                                ""));
                    }
                }
            }
        }

        return report(suite, trials, modelJudgments, manualReviewQueue);
    }

    public EvaluationReport rejudge(EvaluationSuite suite, EvaluationReport existing) {
        if (!existing.systemVariantId().equals(systemVariantId)) {
            throw new IllegalArgumentException("report variant does not match replay variant");
        }
        if (!suite.suiteId().equals(existing.suiteId())
                || !suite.protocol().taskSetSha256().equals(
                        existing.protocol().taskSetSha256())
                || !suite.protocol().goldSetSha256().equals(
                        existing.protocol().goldSetSha256())) {
            throw new IllegalArgumentException("report fingerprints do not match evaluation suite");
        }

        Map<String, EvaluationCase> cases = suite.cases().stream()
                .collect(Collectors.toMap(
                        EvaluationCase::caseId,
                        value -> value,
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<ModelJudgment> judgments = new ArrayList<>();
        for (TrialResult trial : existing.trials()) {
            EvaluationCase evaluationCase = cases.get(trial.caseId());
            if (evaluationCase == null) {
                throw new IllegalArgumentException(
                        "report contains unknown case: " + trial.caseId());
            }
            judgments.addAll(judge(evaluationCase, trial));
        }
        return report(
                suite,
                existing.trials(),
                judgments,
                existing.manualReviewQueue());
    }

    private EvaluationReport report(
            EvaluationSuite suite,
            List<TrialResult> trials,
            List<ModelJudgment> modelJudgments,
            List<ManualReviewItem> manualReviewQueue
    ) {
        Aggregate aggregate = aggregate(
                suite, trials, modelJudgments, manualReviewQueue.size());
        String status;
        boolean blockingJudgeFailure = modelJudgments.stream()
                .anyMatch(value -> value.blocking() && value.label() == Label.FAIL);
        if (aggregate.passedTrials() != aggregate.trialCount()
                || aggregate.redlinePassRate() < 1.0
                || blockingJudgeFailure) {
            status = "FAILED";
        } else if (aggregate.pendingModelReviews() > 0) {
            status = "MODEL_REVIEW_REQUIRED";
        } else if (!manualReviewQueue.isEmpty()) {
            status = "MANUAL_REVIEW_REQUIRED";
        } else {
            status = "PASSED";
        }
        return new EvaluationReport(
                "2.0",
                EVALUATOR_VERSION,
                suite.suiteId(),
                suite.systemVersion(),
                systemVariantId,
                suite.protocol(),
                Instant.now(),
                status,
                aggregate,
                trials,
                modelJudgments,
                manualReviewQueue);
    }

    private List<ModelJudgment> judge(
            EvaluationCase evaluationCase,
            TrialResult trial
    ) {
        if (evaluationCase.judgeRubrics().isEmpty()) return List.of();

        JudgeBatch batch;
        if (!trial.passed()) {
            List<Judgment> skipped = evaluationCase.judgeRubrics().stream()
                    .map(rubric -> new Judgment(
                            rubric.rubricId(), Label.UNKNOWN, 0, 0.0,
                            "deterministic preconditions failed"))
                    .toList();
            batch = new JudgeBatch(
                    modelJudge.modelId(), 0, 0, skipped,
                    "deterministic_preconditions_failed");
        } else {
            JudgeRequest request = new JudgeRequest(
                    trial.runId(),
                    trial.caseId(),
                    trial.domainPackId(),
                    evaluationCase.prompt(),
                    trial.response(),
                    trial.selectedProducts(),
                    trial.assertions(),
                    trial.trace().stream()
                            .map(step -> step.stage() + ":" + step.decision() + ":" + step.facts())
                            .toList());
            try {
                batch = modelJudge.judge(request, evaluationCase.judgeRubrics());
            } catch (RuntimeException error) {
                List<Judgment> unknown = evaluationCase.judgeRubrics().stream()
                        .map(rubric -> new Judgment(
                                rubric.rubricId(), Label.UNKNOWN, 0, 0.0,
                                "judge failure: " + error.getClass().getSimpleName()))
                        .toList();
                batch = new JudgeBatch(
                        modelJudge.modelId(), 0, 0, unknown,
                        error.getClass().getSimpleName());
            }
        }

        Map<String, Judgment> byRubric = batch.judgments().stream()
                .collect(Collectors.toMap(
                        Judgment::rubricId,
                        value -> value,
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<ModelJudgment> values = new ArrayList<>();
        for (JudgeRubric rubric : evaluationCase.judgeRubrics()) {
            Judgment judgment = byRubric.getOrDefault(
                    rubric.rubricId(),
                    new Judgment(rubric.rubricId(), Label.UNKNOWN, 0, 0.0,
                            "judge omitted this rubric"));
            values.add(new ModelJudgment(
                    trial.runId() + ":" + rubric.rubricId(),
                    trial.runId(),
                    trial.caseId(),
                    rubric.rubricId(),
                    rubric.criterion(),
                    rubric.enforcement(),
                    rubric.minimumScore(),
                    batch.modelId(),
                    judgment.label(),
                    judgment.score(),
                    judgment.confidence(),
                    judgment.rationale(),
                    batch.latencyMs(),
                    batch.totalTokens(),
                    batch.error()));
        }
        return List.copyOf(values);
    }

    private Map<String, Object> manualReviewContext(
            EvaluationCase evaluationCase,
            TrialResult trial
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("domainPackId", evaluationCase.domainPackId());
        context.put("caseTags", evaluationCase.tags());
        context.put("machinePreconditions", "PASSED");
        context.put("personalizationEnabled", evaluationCase.discovery().personalizationEnabled());
        if (!evaluationCase.discovery().recentProductIds().isEmpty()) {
            context.put("recentProductIds", evaluationCase.discovery().recentProductIds());
        }
        if (!evaluationCase.discovery().excludedProductIds().isEmpty()) {
            context.put("excludedProductIds", evaluationCase.discovery().excludedProductIds());
        }
        if (!evaluationCase.discovery().adExposureProductIds().isEmpty()) {
            context.put("adExposureProductIds", evaluationCase.discovery().adExposureProductIds());
        }
        if (evaluationCase.expected().sponsoredAllowed() != null) {
            context.put("sponsoredAllowed", evaluationCase.expected().sponsoredAllowed());
        }
        if (evaluationCase.expected().minSponsoredSelectedItems() != null) {
            context.put(
                    "minimumSponsoredSelectedItems",
                    evaluationCase.expected().minSponsoredSelectedItems());
        }
        context.put(
                "selectedSponsoredItems",
                trial.selectedProducts().stream().filter(EvaluationReport.SelectedProduct::sponsored).count());
        context.put(
                "selectedProductIds",
                trial.selectedProducts().stream()
                        .map(EvaluationReport.SelectedProduct::productId)
                        .toList());
        context.put("selectedProducts", trial.selectedProducts());
        context.put("deterministicAssertions", trial.assertions());
        context.put("executionTrace", trial.trace());
        return Map.copyOf(context);
    }

    private Aggregate aggregate(
            EvaluationSuite suite,
            List<TrialResult> trials,
            List<ModelJudgment> modelJudgments,
            int manualReviewCount
    ) {
        int passedTrials = (int) trials.stream().filter(TrialResult::passed).count();
        Set<String> deterministicallyPassedRuns = trials.stream()
                .filter(TrialResult::passed).map(TrialResult::runId).collect(Collectors.toSet());
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

        Map<String, List<ModelJudgment>> judgmentsByRun = modelJudgments.stream()
                .collect(Collectors.groupingBy(
                        ModelJudgment::runId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        int compositePassedTrials = (int) trials.stream()
                .filter(trial -> compositePassed(trial, judgmentsByRun)).count();
        double compositePassAtOne = ratio(
                byCase.values().stream()
                        .filter(values -> compositePassed(values.get(0), judgmentsByRun)).count(),
                byCase.size());
        double compositePassAtK = ratio(
                byCase.values().stream()
                        .filter(values -> values.stream()
                                .anyMatch(trial -> compositePassed(trial, judgmentsByRun))).count(),
                byCase.size());
        double compositePassPowerK = ratio(
                byCase.values().stream()
                        .filter(values -> values.stream()
                                .allMatch(trial -> compositePassed(trial, judgmentsByRun))).count(),
                byCase.size());
        double[] confidenceInterval = wilson95(passedTrials, trials.size());
        List<ModelJudgment> resolvedJudgments = modelJudgments.stream()
                .filter(value -> value.label() != Label.UNKNOWN)
                .toList();
        long judgePasses = resolvedJudgments.stream()
                .filter(value -> value.label() == Label.PASS).count();
        int pendingModelReviews = (int) modelJudgments.stream()
                .filter(ModelJudgment::blocking)
                .filter(value -> value.label() == Label.UNKNOWN)
                .filter(value -> deterministicallyPassedRuns.contains(value.runId()))
                .count();

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

        Map<String, Integer> judgeTokensByRun = new LinkedHashMap<>();
        modelJudgments.forEach(value -> {
            if (value.totalTokens() > 0) judgeTokensByRun.putIfAbsent(value.runId(), value.totalTokens());
        });

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
                compositePassedTrials,
                ratio(compositePassedTrials, trials.size()),
                compositePassAtOne,
                compositePassAtK,
                compositePassPowerK,
                confidenceInterval[0],
                confidenceInterval[1],
                ratio(judgePasses, resolvedJudgments.size()),
                average(List.copyOf(judgeTokensByRun.values())),
                pendingModelReviews,
                manualReviewCount);
    }

    private static boolean compositePassed(
            TrialResult trial,
            Map<String, List<ModelJudgment>> judgmentsByRun
    ) {
        if (!trial.passed()) return false;
        return judgmentsByRun.getOrDefault(trial.runId(), List.of()).stream()
                .filter(ModelJudgment::blocking)
                .allMatch(value -> value.label() == Label.PASS);
    }

    private static double[] wilson95(long successes, long total) {
        if (total == 0) return new double[]{0.0, 0.0};
        double z = 1.959963984540054;
        double n = total;
        double p = successes / n;
        double denominator = 1.0 + z * z / n;
        double center = (p + z * z / (2.0 * n)) / denominator;
        double margin = z * Math.sqrt((p * (1.0 - p) + z * z / (4.0 * n)) / n)
                / denominator;
        return new double[]{round(Math.max(0.0, center - margin)),
                round(Math.min(1.0, center + margin))};
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

    private static String runId(
            String suiteId,
            String systemVariantId,
            String caseId,
            int trialIndex
    ) {
        return "eval-" + safe(suiteId) + "-" + safe(systemVariantId)
                + "-" + safe(caseId) + "-t" + trialIndex;
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
