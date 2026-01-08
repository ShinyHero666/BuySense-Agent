package com.buysense.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record DecisionResult(
        Requirement requirement,
        List<Candidate> slate,
        List<BundleProposal> bundles,
        List<TraceStep> trace,
        Map<String, Object> metrics,
        ModelRuntime runtime
) {
    public DecisionResult withRuntime(ModelRuntime value) {
        return new DecisionResult(requirement, slate, bundles, trace, metrics, value);
    }

    public DecisionResult appendTrace(TraceStep step) {
        ArrayList<TraceStep> updated = new ArrayList<>(trace);
        updated.add(step);
        return new DecisionResult(requirement, slate, bundles, List.copyOf(updated), metrics, runtime);
    }

    public static ModelRuntime offlineRuntime() {
        return new ModelRuntime(
                "replay",
                "deterministic-replay",
                0,
                0,
                0,
                0,
                null,
                null,
                null,
                null,
                false,
                "buysense-replay",
                true,
                0,
                0,
                List.of());
    }

    public record BundleProposal(
            String id,
            List<Product> items,
            BigDecimal totalPrice,
            double score,
            boolean budgetSatisfied,
            boolean compatible,
            List<String> evidence
    ) {
    }

    public record TraceStep(String stage, String decision, Map<String, Object> facts) {
    }

    public record ModelRuntime(
            String mode,
            String model,
            int modelCalls,
            int fallbackCount,
            int totalTokens,
            long latencyMs,
            String rewrittenQuery,
            String intent,
            String explanation,
            String criticVerdict,
            boolean revisionApplied,
            String provider,
            boolean localOnly,
            int proposalAccepted,
            int proposalCorrected,
            List<RoleExecution> roleExecutions
    ) {
        public ModelRuntime {
            roleExecutions = roleExecutions == null
                    ? List.of() : List.copyOf(roleExecutions);
        }
    }

    public record RoleExecution(
            String role,
            String mode,
            String provider,
            String model,
            String outcome,
            boolean proposalUsed,
            List<String> corrections,
            long latencyMs,
            int inputTokens,
            int outputTokens,
            int totalTokens,
            String error
    ) {
        public RoleExecution {
            corrections = corrections == null ? List.of() : List.copyOf(corrections);
        }
    }
}
