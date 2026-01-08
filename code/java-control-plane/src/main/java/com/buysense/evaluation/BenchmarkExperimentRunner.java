package com.buysense.evaluation;

import com.buysense.evaluation.EvaluationHarness.DecisionSystem;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runs controlled system variants against the same frozen benchmark partition. */
public final class BenchmarkExperimentRunner {
    private final DeterministicEvaluationGrader grader;

    public BenchmarkExperimentRunner(DeterministicEvaluationGrader grader) {
        this.grader = grader;
    }

    public BenchmarkComparison run(
            EvaluationSuite suite,
            String referenceVariantId,
            List<Variant> variants
    ) {
        validate(referenceVariantId, variants);
        List<EvaluationReport> reports = variants.stream()
                .map(variant -> new EvaluationHarness(
                        variant.variantId(),
                        variant.system(),
                        grader,
                        variant.judge()).run(suite))
                .toList();
        return compare(suite, referenceVariantId, reports);
    }

    public BenchmarkComparison compare(
            EvaluationSuite suite,
            String referenceVariantId,
            List<EvaluationReport> reports
    ) {
        if (reports == null || reports.size() < 2) {
            throw new IllegalArgumentException(
                    "benchmark comparison requires at least two reports");
        }
        Set<String> ids = new HashSet<>();
        for (EvaluationReport report : reports) {
            if (report == null || !ids.add(report.systemVariantId())) {
                throw new IllegalArgumentException(
                        "report variant ids must be non-blank and unique");
            }
            if (!suite.suiteId().equals(report.suiteId())
                    || !suite.protocol().taskSetSha256().equals(
                            report.protocol().taskSetSha256())
                    || !suite.protocol().goldSetSha256().equals(
                            report.protocol().goldSetSha256())) {
                throw new IllegalArgumentException(
                        "report fingerprints do not match evaluation suite");
            }
        }
        EvaluationReport reference = reports.stream()
                .filter(value -> value.systemVariantId().equals(referenceVariantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "reference report is not present"));
        List<VariantDelta> deltas = reports.stream()
                .map(value -> delta(reference, value))
                .toList();
        return new BenchmarkComparison(
                "1.0",
                suite.suiteId(),
                suite.protocol(),
                referenceVariantId,
                Instant.now(),
                reports,
                deltas);
    }

    private static void validate(String referenceVariantId, List<Variant> variants) {
        if (referenceVariantId == null || referenceVariantId.isBlank()) {
            throw new IllegalArgumentException("referenceVariantId is required");
        }
        if (variants == null || variants.size() < 2) {
            throw new IllegalArgumentException("benchmark comparison requires at least two variants");
        }
        Set<String> ids = new HashSet<>();
        for (Variant variant : variants) {
            if (variant == null || variant.variantId().isBlank() || !ids.add(variant.variantId())) {
                throw new IllegalArgumentException("variant ids must be non-blank and unique");
            }
        }
        if (!ids.contains(referenceVariantId)) {
            throw new IllegalArgumentException("reference variant is not present");
        }
    }

    private static VariantDelta delta(EvaluationReport reference, EvaluationReport value) {
        EvaluationReport.Aggregate base = reference.aggregate();
        EvaluationReport.Aggregate candidate = value.aggregate();
        return new VariantDelta(
                value.systemVariantId(),
                round(candidate.taskSuccessRate() - base.taskSuccessRate()),
                round(candidate.compositeSuccessRate() - base.compositeSuccessRate()),
                round(candidate.passPowerK() - base.passPowerK()),
                candidate.p90LatencyMs() - base.p90LatencyMs(),
                round(candidate.averageModelCalls() - base.averageModelCalls()),
                round(candidate.averageTokens() - base.averageTokens()),
                candidate.redlinePassRate() == 1.0);
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public record Variant(
            String variantId,
            DecisionSystem system,
            EvaluationModelJudge judge
    ) {
        public Variant {
            if (variantId == null) variantId = "";
            if (system == null) throw new IllegalArgumentException("variant system is required");
            judge = judge == null ? EvaluationModelJudge.disabled() : judge;
        }
    }

    public record BenchmarkComparison(
            String schemaVersion,
            String suiteId,
            EvaluationSuite.Protocol protocol,
            String referenceVariantId,
            Instant generatedAt,
            List<EvaluationReport> reports,
            List<VariantDelta> deltas
    ) {
        public BenchmarkComparison {
            reports = List.copyOf(reports);
            deltas = List.copyOf(deltas);
        }
    }

    public record VariantDelta(
            String variantId,
            double deterministicSuccessDelta,
            double compositeSuccessDelta,
            double passPowerKDelta,
            long p90LatencyDeltaMs,
            double averageModelCallsDelta,
            double averageTokensDelta,
            boolean redlinesPreserved
    ) {
    }
}
