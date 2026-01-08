package com.buysense.evaluation;

import com.buysense.agent.BoundedCollaborationCoordinator;
import com.buysense.domain.DecisionResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EvaluationReport(
        String schemaVersion,
        String evaluatorVersion,
        String suiteId,
        String systemVersion,
        String systemVariantId,
        EvaluationSuite.Protocol protocol,
        Instant generatedAt,
        String status,
        Aggregate aggregate,
        List<TrialResult> trials,
        List<ModelJudgment> modelJudgments,
        List<ManualReviewItem> manualReviewQueue
) {
    public EvaluationReport {
        systemVariantId = systemVariantId == null || systemVariantId.isBlank()
                ? "configured-system"
                : systemVariantId;
        protocol = protocol == null ? EvaluationSuite.Protocol.legacy() : protocol;
        trials = List.copyOf(trials);
        modelJudgments = modelJudgments == null ? List.of() : List.copyOf(modelJudgments);
        manualReviewQueue = List.copyOf(manualReviewQueue);
    }

    public enum Severity {
        REDLINE,
        CORE,
        OBSERVATION
    }

    public enum Dimension {
        RESULT,
        PROCESS,
        EFFICIENCY,
        SAFETY
    }

    public enum FailureCategory {
        ROUTING,
        TOOL,
        PARAMETER,
        CONSTRAINT,
        EVIDENCE,
        SAFETY,
        PROCESS,
        OUTCOME,
        LATENCY,
        HARNESS
    }

    public record AssertionResult(
            String code,
            Dimension dimension,
            FailureCategory failureCategory,
            Severity severity,
            boolean passed,
            Object expected,
            Object actual,
            String detail
    ) {
    }

    public record SelectedProduct(
            String id,
            String productId,
            String skuId,
            String name,
            String category,
            String brand,
            String price,
            List<String> tags,
            List<String> connectors,
            List<String> protocols,
            Integer maxPowerWatts,
            boolean sponsored
    ) {
        public SelectedProduct {
            tags = List.copyOf(tags);
            connectors = List.copyOf(connectors);
            protocols = List.copyOf(protocols);
        }
    }

    public record TrialResult(
            String runId,
            String caseId,
            String domainPackId,
            List<String> tags,
            int trialIndex,
            boolean passed,
            boolean redlinePassed,
            String verdict,
            String intent,
            String response,
            long wallLatencyMs,
            int taskCount,
            int coordinatorModelCalls,
            int totalTokens,
            List<SelectedProduct> selectedProducts,
            List<AssertionResult> assertions,
            List<FailureCategory> failureCategories,
            List<DecisionResult.TraceStep> trace,
            List<BoundedCollaborationCoordinator.Task> tasks,
            List<BoundedCollaborationCoordinator.DelegationProposal> proposals
    ) {
        public TrialResult {
            tags = List.copyOf(tags);
            selectedProducts = List.copyOf(selectedProducts);
            assertions = List.copyOf(assertions);
            failureCategories = List.copyOf(failureCategories);
            trace = List.copyOf(trace);
            tasks = List.copyOf(tasks);
            proposals = List.copyOf(proposals);
        }
    }

    public record Aggregate(
            int caseCount,
            int trialCount,
            int passedTrials,
            double taskSuccessRate,
            double passAtOne,
            double passAtK,
            double passPowerK,
            double graderPassRate,
            double redlinePassRate,
            long p50LatencyMs,
            long p90LatencyMs,
            double averageTaskCount,
            double averageModelCalls,
            double averageTokens,
            Map<String, Long> failuresByCategory,
            Map<String, Double> taskSuccessRateByTag,
            int compositePassedTrials,
            double compositeSuccessRate,
            double compositePassAtOne,
            double compositePassAtK,
            double compositePassPowerK,
            double taskSuccessCi95Low,
            double taskSuccessCi95High,
            double modelJudgePassRate,
            double averageJudgeTokens,
            int pendingModelReviews,
            int pendingManualReviews
    ) {
        public Aggregate {
            failuresByCategory = Map.copyOf(failuresByCategory);
            taskSuccessRateByTag = Map.copyOf(taskSuccessRateByTag);
        }
    }

    public record ModelJudgment(
            String reviewId,
            String runId,
            String caseId,
            String rubricId,
            String criterion,
            EvaluationCase.RubricEnforcement enforcement,
            int minimumScore,
            String modelId,
            EvaluationModelJudge.Label label,
            int score,
            double confidence,
            String rationale,
            long latencyMs,
            int totalTokens,
            String error
    ) {
        public ModelJudgment {
            reviewId = reviewId == null ? "" : reviewId;
            modelId = modelId == null ? "" : modelId;
            label = label == null ? EvaluationModelJudge.Label.UNKNOWN : label;
            rationale = rationale == null ? "" : rationale;
            error = error == null ? "" : error;
        }

        public boolean blocking() {
            return enforcement == EvaluationCase.RubricEnforcement.BLOCKING;
        }
    }

    public record ManualReviewItem(
            String reviewId,
            String caseId,
            String runId,
            String prompt,
            Map<String, Object> context,
            String rubric,
            String response,
            String label,
            String rationale
    ) {
        public ManualReviewItem {
            context = Map.copyOf(context);
        }
    }
}
