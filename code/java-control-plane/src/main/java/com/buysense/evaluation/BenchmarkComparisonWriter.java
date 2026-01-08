package com.buysense.evaluation;

import com.buysense.evaluation.BenchmarkExperimentRunner.BenchmarkComparison;
import com.buysense.evaluation.BenchmarkExperimentRunner.VariantDelta;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Persists the comparison plus each replayable per-variant report. */
public final class BenchmarkComparisonWriter {
    private final ObjectMapper mapper;
    private final EvaluationReportWriter reportWriter;

    public BenchmarkComparisonWriter(ObjectMapper mapper) {
        this.mapper = mapper;
        this.reportWriter = new EvaluationReportWriter(mapper);
    }

    public void write(BenchmarkComparison comparison, Path directory) {
        createDirectory(directory);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    directory.resolve("benchmark-comparison.json").toFile(), comparison);
            Files.writeString(
                    directory.resolve("benchmark-comparison.md"),
                    markdown(comparison),
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("cannot write benchmark comparison", error);
        }
        for (EvaluationReport report : comparison.reports()) {
            String name = safe(report.systemVariantId());
            reportWriter.writeJson(report, directory.resolve(name + "-report.json"));
            reportWriter.writeMarkdown(report, directory.resolve(name + "-report.md"));
        }
    }

    private static String markdown(BenchmarkComparison comparison) {
        Map<String, VariantDelta> deltas = comparison.deltas().stream()
                .collect(Collectors.toMap(VariantDelta::variantId, Function.identity()));
        StringBuilder value = new StringBuilder();
        value.append("# BuySense Controlled Benchmark\n\n");
        value.append("- Suite: `").append(comparison.suiteId()).append("`\n");
        value.append("- Partition: `").append(comparison.protocol().partition()).append("`\n");
        value.append("- Reference: `").append(comparison.referenceVariantId()).append("`\n");
        value.append("- Task SHA-256: `").append(comparison.protocol().taskSetSha256()).append("`\n");
        value.append("- Gold SHA-256: `").append(comparison.protocol().goldSetSha256()).append("`\n\n");
        value.append("| Variant | Deterministic | Composite | Pass^k | P90 | Avg calls | Avg tokens | Redlines |\n");
        value.append("|---|---:|---:|---:|---:|---:|---:|---|\n");
        for (EvaluationReport report : comparison.reports()) {
            EvaluationReport.Aggregate aggregate = report.aggregate();
            value.append("| ").append(report.systemVariantId())
                    .append(" | ").append(percent(aggregate.taskSuccessRate()))
                    .append(" | ").append(percent(aggregate.compositeSuccessRate()))
                    .append(" | ").append(percent(aggregate.passPowerK()))
                    .append(" | ").append(aggregate.p90LatencyMs()).append(" ms")
                    .append(" | ").append(aggregate.averageModelCalls())
                    .append(" | ").append(aggregate.averageTokens())
                    .append(" | ").append(aggregate.redlinePassRate() == 1.0 ? "PASS" : "FAIL")
                    .append(" |\n");
        }
        value.append("\n## Delta From Reference\n\n");
        value.append("| Variant | Deterministic | Composite | Pass^k | P90 | Avg calls | Avg tokens |\n");
        value.append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (EvaluationReport report : comparison.reports()) {
            VariantDelta delta = deltas.get(report.systemVariantId());
            value.append("| ").append(delta.variantId())
                    .append(" | ").append(points(delta.deterministicSuccessDelta()))
                    .append(" | ").append(points(delta.compositeSuccessDelta()))
                    .append(" | ").append(points(delta.passPowerKDelta()))
                    .append(" | ").append(signed(delta.p90LatencyDeltaMs())).append(" ms")
                    .append(" | ").append(signed(delta.averageModelCallsDelta()))
                    .append(" | ").append(signed(delta.averageTokensDelta()))
                    .append(" |\n");
        }
        return value.toString();
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100.0);
    }

    private static String points(double value) {
        return String.format(Locale.ROOT, "%+.2f pp", value * 100.0);
    }

    private static String signed(double value) {
        return String.format(Locale.ROOT, "%+.2f", value);
    }

    private static String safe(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    private static void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory.toAbsolutePath());
        } catch (IOException error) {
            throw new IllegalStateException("cannot create benchmark report directory", error);
        }
    }
}
