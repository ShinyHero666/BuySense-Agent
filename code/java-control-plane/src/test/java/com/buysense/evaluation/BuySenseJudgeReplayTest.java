package com.buysense.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.buysense.evaluation.BenchmarkExperimentRunner.BenchmarkComparison;
import com.buysense.evaluation.OpenAiCompatibleEvaluationJudge.JudgeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
        named = "BUYSENSE_BENCHMARK_JUDGE_REPLAY",
        matches = "true")
class BuySenseJudgeReplayTest {
    private static final String TASKS =
            "evaluation/buysense_capability_holdout_v1.tasks.json";
    private static final String GOLD =
            "evaluation/private/buysense_capability_holdout_v1.gold.json";
    private static final Path SOURCE_DIRECTORY = Path.of(
            "target", "evaluation", "controlled-benchmark-v1");
    private static final Path OUTPUT_DIRECTORY = Path.of(
            "target", "evaluation", "controlled-benchmark-v1-rejudged");
    private static final List<String> VARIANTS = List.of(
            "deterministic-no-model",
            "no-critic-ablation",
            "full-bounded-agent");

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void rejudgesSavedTrialsWithoutRerunningDecisionSystems() throws Exception {
        EvaluationSuite suite = new BenchmarkPartitionLoader(mapper)
                .loadClasspath(TASKS, GOLD);
        EvaluationModelJudge judge = new OpenAiCompatibleEvaluationJudge(
                new JudgeConfig(
                        true,
                        required("BUYSENSE_JUDGE_URL"),
                        required("BUYSENSE_JUDGE_KEY"),
                        required("BUYSENSE_JUDGE_MODEL"),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(90),
                        1200),
                mapper);

        List<EvaluationReport> rejudged = new ArrayList<>();
        for (String variant : VARIANTS) {
            Path source = SOURCE_DIRECTORY.resolve(
                    variant + "-report.json");
            assertThat(source).exists();
            EvaluationReport existing = mapper.readValue(
                    Files.readString(source),
                    EvaluationReport.class);
            EvaluationHarness harness = new EvaluationHarness(
                    variant,
                    (runId, message, domainPackId, discovery) -> {
                        throw new AssertionError(
                                "judge replay must not execute the decision system");
                    },
                    new DeterministicEvaluationGrader(),
                    judge);
            EvaluationReport replayed = harness.rejudge(suite, existing);
            assertThat(replayed.trials()).isEqualTo(existing.trials());
            assertThat(replayed.protocol().taskSetSha256())
                    .isEqualTo(existing.protocol().taskSetSha256());
            assertThat(replayed.protocol().goldSetSha256())
                    .isEqualTo(existing.protocol().goldSetSha256());
            assertThat(replayed.aggregate().pendingModelReviews())
                    .isZero();
            rejudged.add(replayed);
        }

        BenchmarkComparison comparison =
                new BenchmarkExperimentRunner(
                        new DeterministicEvaluationGrader())
                        .compare(
                                suite,
                                "deterministic-no-model",
                                rejudged);
        new BenchmarkComparisonWriter(mapper)
                .write(comparison, OUTPUT_DIRECTORY);
        assertThat(comparison.reports()).hasSize(3);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "missing required environment variable: " + name);
        }
        return value;
    }
}
