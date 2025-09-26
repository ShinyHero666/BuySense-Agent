package com.moyuan.buysense.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionEngineBenchmark {
    private static final int WARMUP_ROUNDS = 5;
    private static final int MEASURED_ROUNDS = 20;

    private final AgentTestFixture fixture = new AgentTestFixture();

    @Test
    void benchmarksCurrentJavaDecisionPath() throws Exception {
        List<Scenario> scenarios = scenarios();

        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            scenarios.forEach(this::execute);
        }

        long[] latencies = new long[scenarios.size() * MEASURED_ROUNDS];
        int measuredRuns = 0;
        int emptyResults = 0;
        int categoryViolations = 0;
        int budgetViolations = 0;
        int adPolicyViolations = 0;

        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            for (Scenario scenario : scenarios) {
                long startedAt = System.nanoTime();
                var result = execute(scenario);
                latencies[measuredRuns] = System.nanoTime() - startedAt;
                measuredRuns++;

                if (result.slate().isEmpty()) {
                    emptyResults++;
                    continue;
                }
                categoryViolations += result.slate().stream()
                        .anyMatch(candidate -> !scenario.category().equals(
                                candidate.product().category())) ? 1 : 0;
                budgetViolations += result.slate().stream()
                        .anyMatch(candidate -> candidate.product().price()
                                .compareTo(result.requirement().budget()) > 0) ? 1 : 0;
                long sponsoredTopThree = result.slate().stream().limit(3)
                        .filter(candidate -> candidate.sponsored()).count();
                if (sponsoredTopThree > 1 || (scenario.rejectAds()
                        && result.slate().stream().anyMatch(candidate -> candidate.sponsored()))) {
                    adPolicyViolations++;
                }
            }
        }

        assertThat(scenarios).hasSize(120);
        assertThat(measuredRuns).isEqualTo(2_400);
        assertThat(emptyResults).isZero();
        assertThat(categoryViolations).isZero();
        assertThat(budgetViolations).isZero();
        assertThat(adPolicyViolations).isZero();

        Arrays.sort(latencies);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("benchmark", "buysense-java-decision-v1");
        report.put("generated_at", Instant.now().toString());
        report.put("domain_pack", "normal-3c-v1");
        report.put("local_products", fixture.retail.localProductCount("normal-3c-v1"));
        report.put("local_spus", fixture.retail.localSpuCount("normal-3c-v1"));
        report.put("unique_queries", scenarios.size());
        report.put("warmup_rounds", WARMUP_ROUNDS);
        report.put("measured_rounds", MEASURED_ROUNDS);
        report.put("measured_runs", measuredRuns);
        report.put("non_empty_result_rate", 1.0 - (double) emptyResults / measuredRuns);
        report.put("category_violations", categoryViolations);
        report.put("budget_violations", budgetViolations);
        report.put("ad_policy_violations", adPolicyViolations);
        report.put("latency_p50_ms", milliseconds(percentile(latencies, 0.50)));
        report.put("latency_p95_ms", milliseconds(percentile(latencies, 0.95)));
        report.put("latency_max_ms", milliseconds(latencies[latencies.length - 1]));
        report.put("latency_scope", "IntentParser plus in-memory Java DecisionEngine; excludes LLM, network and database I/O");
        report.put("java_version", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put("available_processors", Runtime.getRuntime().availableProcessors());

        ObjectMapper mapper = fixture.mapper;
        String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        System.out.println("BUY_SENSE_BENCHMARK=" + output);

        String reportPath = System.getProperty("buysense.benchmark.report", "").trim();
        if (!reportPath.isEmpty()) {
            Path path = Path.of(reportPath).toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());
            Files.writeString(path, output + System.lineSeparator());
        }
    }

    private com.moyuan.buysense.domain.DecisionResult execute(Scenario scenario) {
        return fixture.engine.decide(fixture.parser.parse(scenario.query()));
    }

    private static long percentile(long[] sorted, double quantile) {
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static double milliseconds(long nanoseconds) {
        return Math.round(nanoseconds / 1_000.0) / 1_000.0;
    }

    private static List<Scenario> scenarios() {
        Map<String, List<String>> categoryPhrases = new LinkedHashMap<>();
        categoryPhrases.put("phone", List.of("手机", "拍照手机", "安卓手机", "iPhone", "phone"));
        categoryPhrases.put("headphones", List.of("耳机", "降噪耳机", "蓝牙耳机", "headphone", "earbuds"));
        categoryPhrases.put("charger", List.of("充电器", "充电头", "快充", "快充充电器", "charger"));
        categoryPhrases.put("laptop", List.of("电脑", "游戏电脑", "办公电脑", "笔记本", "laptop"));
        categoryPhrases.put("mouse", List.of("鼠标", "游戏鼠标", "办公鼠标", "无线鼠标", "mouse"));
        categoryPhrases.put("keyboard", List.of("键盘", "游戏键盘", "办公键盘", "机械键盘", "keyboard"));

        List<Scenario> scenarios = new ArrayList<>();
        for (var category : categoryPhrases.entrySet()) {
            for (String phrase : category.getValue()) {
                for (String useCase : List.of("游戏", "办公")) {
                    for (boolean rejectAds : List.of(false, true)) {
                        String query = "预算10000元，推荐一款" + phrase + "，主要用于" + useCase
                                + (rejectAds ? "，不要广告" : "");
                        scenarios.add(new Scenario(query, category.getKey(), rejectAds));
                    }
                }
            }
        }
        return List.copyOf(scenarios);
    }

    private record Scenario(String query, String category, boolean rejectAds) {
    }
}
