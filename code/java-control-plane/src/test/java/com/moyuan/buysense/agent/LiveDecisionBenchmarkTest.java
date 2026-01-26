package com.moyuan.buysense.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.domain.Candidate;
import com.moyuan.buysense.domain.DecisionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_MODEL_BENCHMARK", matches = "true")
class LiveDecisionBenchmarkTest {
    @Autowired AdaptiveDecisionService decisions;
    @Autowired ObjectMapper mapper;

    @Test
    void comparesWorkflowAndHybridOnTheSameBusinessCases() throws Exception {
        List<BenchmarkCase> cases = List.of(
                new BenchmarkCase(
                        "L01",
                        "预算5000元，不要广告，想拍照也想玩游戏，推荐手机",
                        Set.of("phone"),
                        false,
                        true),
                new BenchmarkCase(
                        "L02",
                        "预算1500元，通勤和办公都用的降噪耳机哪个好",
                        Set.of("headphones"),
                        false,
                        false),
                new BenchmarkCase(
                        "L03",
                        "预算7000元，配一套拍照手机、降噪耳机和出差充电器",
                        Set.of("phone", "headphones", "charger"),
                        true,
                        false),
                new BenchmarkCase(
                        "L04",
                        "预算10000元，不要广告，配一套游戏电脑、鼠标和键盘",
                        Set.of("laptop", "mouse", "keyboard"),
                        true,
                        true));

        String benchmarkRunId = UUID.randomUUID().toString();
        List<BenchmarkRow> rows = new ArrayList<>();
        for (BenchmarkCase benchmarkCase : cases) {
            for (ExecutionRouter.Mode mode : ExecutionRouter.Mode.values()) {
                long started = System.nanoTime();
                var execution = decisions.decide(
                        "benchmark-" + benchmarkRunId + "-" + benchmarkCase.id()
                                + "-" + mode.name().toLowerCase(),
                        benchmarkCase.query(),
                        mode);
                long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                DecisionResult result = execution.result();
                rows.add(new BenchmarkRow(
                        benchmarkCase.id(),
                        mode.name().toLowerCase(),
                        latencyMs,
                        completed(result, benchmarkCase.bundle()),
                        categoryRecall(result, benchmarkCase.expectedCategories()),
                        hardConstraintsPass(result, benchmarkCase),
                        result.runtime().modelCalls(),
                        result.runtime().fallbackCount(),
                        result.runtime().totalTokens(),
                        result.runtime().criticVerdict(),
                        result.runtime().replanned()));
            }
        }

        Map<String, Summary> summaries = new LinkedHashMap<>();
        for (ExecutionRouter.Mode mode : ExecutionRouter.Mode.values()) {
            String name = mode.name().toLowerCase();
            summaries.put(name, summarize(rows.stream()
                    .filter(row -> row.mode().equals(name))
                    .toList()));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("suite", "buysense-live-workflow-vs-hybrid");
        report.put("generatedAt", Instant.now());
        report.put("caseCount", cases.size());
        report.put("benchmarkRunId", benchmarkRunId);
        report.put("executionNote", "Offline benchmark only; production routes each request to one path.");
        report.put("summaries", summaries);
        report.put("rows", rows);

        Path output = Path.of("target", "benchmark", "live-decision-comparison.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));

        assertThat(summaries.get("workflow").completionRate()).isEqualTo(1.0);
        assertThat(summaries.get("hybrid").completionRate()).isEqualTo(1.0);
        assertThat(summaries.get("workflow").hardConstraintPassRate()).isEqualTo(1.0);
        assertThat(summaries.get("hybrid").hardConstraintPassRate()).isEqualTo(1.0);
        assertThat(summaries.get("hybrid").modelFallbackCount()).isZero();
        assertThat(summaries.get("hybrid").totalTokens()).isPositive();
    }

    private static Summary summarize(List<BenchmarkRow> rows) {
        List<Long> latencies = rows.stream()
                .map(BenchmarkRow::latencyMs)
                .sorted(Comparator.naturalOrder())
                .toList();
        return new Summary(
                ratio(rows.stream().filter(BenchmarkRow::completed).count(), rows.size()),
                rows.stream().mapToDouble(BenchmarkRow::categoryRecallAt10).average().orElse(0),
                ratio(rows.stream().filter(BenchmarkRow::hardConstraintPass).count(), rows.size()),
                percentile(latencies, 0.50),
                percentile(latencies, 0.95),
                rows.stream().mapToInt(BenchmarkRow::modelCalls).sum(),
                rows.stream().mapToInt(BenchmarkRow::modelFallbacks).sum(),
                rows.stream().mapToInt(BenchmarkRow::totalTokens).sum());
    }

    private static boolean completed(DecisionResult result, boolean bundle) {
        return !result.slate().isEmpty() && (!bundle || !result.bundles().isEmpty());
    }

    private static double categoryRecall(DecisionResult result, Set<String> expected) {
        long found = result.slate().stream()
                .limit(10)
                .map(candidate -> candidate.product().category())
                .filter(expected::contains)
                .distinct()
                .count();
        return expected.isEmpty() ? 1.0 : found / (double) expected.size();
    }

    private static boolean hardConstraintsPass(
            DecisionResult result,
            BenchmarkCase benchmarkCase
    ) {
        boolean categories = result.slate().stream()
                .allMatch(candidate -> benchmarkCase.expectedCategories()
                        .contains(candidate.product().category()));
        boolean ads = !benchmarkCase.rejectAds()
                || result.slate().stream().noneMatch(Candidate::sponsored);
        boolean bundles = result.bundles().stream()
                .allMatch(bundle -> bundle.budgetSatisfied() && bundle.compatible());
        return categories && ads && bundles;
    }

    private static long percentile(List<Long> values, double percentile) {
        int index = Math.max(0, (int) Math.ceil(values.size() * percentile) - 1);
        return values.get(index);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 1.0 : numerator / (double) denominator;
    }

    private record BenchmarkCase(
            String id,
            String query,
            Set<String> expectedCategories,
            boolean bundle,
            boolean rejectAds
    ) {
    }

    private record BenchmarkRow(
            String caseId,
            String mode,
            long latencyMs,
            boolean completed,
            double categoryRecallAt10,
            boolean hardConstraintPass,
            int modelCalls,
            int modelFallbacks,
            int totalTokens,
            String criticVerdict,
            boolean replanned
    ) {
    }

    private record Summary(
            double completionRate,
            double categoryRecallAt10,
            double hardConstraintPassRate,
            long p50LatencyMs,
            long p95LatencyMs,
            int modelCalls,
            int modelFallbackCount,
            int totalTokens
    ) {
    }
}
