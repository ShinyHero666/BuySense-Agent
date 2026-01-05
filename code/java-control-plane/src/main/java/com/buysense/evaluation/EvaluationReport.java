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
        Instant generatedAt,
        String status,
        Aggregate aggregate,
        List<TrialResult> trials,
        List<ManualReviewItem> manualReviewQueue
) {
    public EvaluationReport {
        trials = List.copyOf(trials);
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
            boolean sponsored
    ) {
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
            int pendingManualReviews
    ) {
        public Aggregate {
            failuresByCategory = Map.copyOf(failuresByCategory);
            taskSuccessRateByTag = Map.copyOf(taskSuccessRateByTag);
        }
    }

    public record ManualReviewItem(
            String reviewId,
            String caseId,
            String runId,
            String prompt,
            String rubric,
            String response,
            String label,
            String rationale
    ) {
    }
}
