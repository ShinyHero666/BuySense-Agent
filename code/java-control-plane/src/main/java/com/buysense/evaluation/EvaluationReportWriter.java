package com.buysense.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.buysense.evaluation.EvaluationReport.AssertionResult;
import com.buysense.evaluation.EvaluationReport.Severity;
import com.buysense.evaluation.EvaluationReport.TrialResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

public final class EvaluationReportWriter {
    private final ObjectMapper mapper;

    public EvaluationReportWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void writeJson(EvaluationReport report, Path output) {
        createParent(output);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        } catch (IOException error) {
            throw new IllegalStateException("cannot write evaluation JSON report: " + output, error);
        }
    }

    public void writeMarkdown(EvaluationReport report, Path output) {
        createParent(output);
        StringBuilder value = new StringBuilder();
        EvaluationReport.Aggregate aggregate = report.aggregate();
        value.append("# BuySense Task Evaluation\n\n");
        value.append("- Suite: `").append(report.suiteId()).append("`\n");
        value.append("- System version: `").append(report.systemVersion()).append("`\n");
        value.append("- Evaluator: `").append(report.evaluatorVersion()).append("`\n");
        value.append("- Status: **").append(report.status()).append("**\n");
        value.append("- Cases / trials: ").append(aggregate.caseCount()).append(" / ")
                .append(aggregate.trialCount()).append("\n\n");

        value.append("## Aggregate\n\n");
        value.append("| Metric | Value |\n|---|---:|\n");
        metric(value, "Task success rate", aggregate.taskSuccessRate());
        metric(value, "Pass@1", aggregate.passAtOne());
        metric(value, "Pass@k", aggregate.passAtK());
        metric(value, "Pass^k", aggregate.passPowerK());
        metric(value, "Blocking grader pass rate", aggregate.graderPassRate());
        metric(value, "Redline pass rate", aggregate.redlinePassRate());
        value.append("| P50 wall latency | ").append(aggregate.p50LatencyMs()).append(" ms |\n");
        value.append("| P90 wall latency | ").append(aggregate.p90LatencyMs()).append(" ms |\n");
        value.append("| Average collaboration tasks | ").append(aggregate.averageTaskCount()).append(" |\n");
        value.append("| Average coordinator model calls | ").append(aggregate.averageModelCalls()).append(" |\n");
        value.append("| Average recorded tokens | ").append(aggregate.averageTokens()).append(" |\n");
        value.append("| Pending manual reviews | ").append(aggregate.pendingManualReviews()).append(" |\n\n");

        value.append("## Failure Attribution\n\n");
        value.append("| Category | Blocking failures |\n|---|---:|\n");
        aggregate.failuresByCategory().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> value.append("| ").append(entry.getKey())
                        .append(" | ").append(entry.getValue()).append(" |\n"));

        value.append("\n## Slice Results\n\n");
        value.append("| Tag | Task success rate |\n|---|---:|\n");
        aggregate.taskSuccessRateByTag().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    value.append("| ").append(entry.getKey()).append(" | ");
                    value.append(String.format(
                            java.util.Locale.ROOT, "%.2f%%", entry.getValue() * 100.0));
                    value.append(" |\n");
                });

        value.append("\n## Trials\n\n");
        value.append("| Case | Trial | Result | Verdict | Latency | Failed checks |\n");
        value.append("|---|---:|---|---|---:|---|\n");
        for (TrialResult trial : report.trials()) {
            List<String> failures = trial.assertions().stream()
                    .filter(Predicate.not(AssertionResult::passed))
                    .filter(item -> item.severity() != Severity.OBSERVATION)
                    .map(AssertionResult::code).toList();
            value.append("| ").append(trial.caseId())
                    .append(" | ").append(trial.trialIndex())
                    .append(" | ").append(trial.passed() ? "PASS" : "FAIL")
                    .append(" | ").append(trial.verdict())
                    .append(" | ").append(trial.wallLatencyMs()).append(" ms")
                    .append(" | ").append(failures.isEmpty() ? "-" : String.join(", ", failures))
                    .append(" |\n");
        }

        if (!report.manualReviewQueue().isEmpty()) {
            value.append("\n## Manual Review Queue\n\n");
            value.append("Use `PASS`, `FAIL`, or `UNCERTAIN`. A final decision requires a short rationale.\n\n");
            report.manualReviewQueue().forEach(item -> {
                value.append("### `").append(item.reviewId()).append("`\n\n");
                value.append("**Prompt:** ").append(item.prompt()).append("\n\n");
                value.append("**Rubric:** ").append(item.rubric()).append("\n\n");
                value.append("**Observed response:**\n\n```text\n")
                        .append(item.response()).append("\n```\n\n");
                value.append("**Decision:** `").append(item.label()).append("`\n\n");
                value.append("**Rationale:** ")
                        .append(item.rationale().isBlank() ? "_pending_" : item.rationale())
                        .append("\n\n");
            });
        }
        try {
            Files.writeString(output, value.toString(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("cannot write evaluation Markdown report: " + output, error);
        }
    }

    private static void metric(StringBuilder value, String name, double ratio) {
        value.append("| ").append(name).append(" | ")
                .append(String.format(java.util.Locale.ROOT, "%.2f%%", ratio * 100.0))
                .append(" |\n");
    }

    private static void createParent(Path output) {
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException error) {
            throw new IllegalStateException("cannot create evaluation report directory", error);
        }
    }
}
