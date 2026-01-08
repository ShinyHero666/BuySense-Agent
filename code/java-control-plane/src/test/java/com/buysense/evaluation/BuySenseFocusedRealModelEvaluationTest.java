package com.buysense.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.buysense.agent.SearchAdsRecsLeadService;
import com.buysense.evaluation.EvaluationModelJudge.Label;
import com.buysense.evaluation.OpenAiCompatibleEvaluationJudge.JudgeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "BUYSENSE_FOCUSED_REAL_MODEL_EVAL", matches = "true")
class BuySenseFocusedRealModelEvaluationTest {
    private static final String TASKS =
            "evaluation/buysense_capability_holdout_v2.tasks.json";
    private static final String GOLD =
            "evaluation/private/buysense_capability_holdout_v2.gold.json";
    private static final Path REPORT_DIRECTORY = Path.of(
            "target", "evaluation", "focused-real-model-v2");

    @Autowired
    private SearchAdsRecsLeadService agent;

    @Autowired
    private ObjectMapper mapper;

    @DynamicPropertySource
    static void realModelProperties(DynamicPropertyRegistry registry) {
        registry.add("buysense.modelport.enabled", () -> true);
        registry.add("buysense.modelport.base-url", () -> required("BUYSENSE_MODELPORT_URL"));
        registry.add("buysense.modelport.api-key", () -> required("BUYSENSE_MODELPORT_KEY"));
        registry.add("buysense.modelport.model", () -> required("BUYSENSE_MODELPORT_MODEL"));
    }

    @Test
    void rerunsSelectedHoldoutCasesWithAnIndependentJudge() {
        Set<String> selectedIds = new LinkedHashSet<>(Arrays.stream(
                        required("BUYSENSE_FOCUSED_CASE_IDS").split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList());
        int trials = positiveInt("BUYSENSE_FOCUSED_TRIALS", 1);
        EvaluationSuite seed = new BenchmarkPartitionLoader(mapper)
                .loadClasspath(TASKS, GOLD);
        List<EvaluationCase> selected = seed.cases().stream()
                .filter(value -> selectedIds.contains(value.caseId()))
                .map(value -> withTrials(value, trials))
                .toList();
        assertThat(selected).extracting(EvaluationCase::caseId)
                .containsExactlyInAnyOrderElementsOf(selectedIds);

        EvaluationSuite focused = new EvaluationSuite(
                seed.schemaVersion(),
                seed.suiteId() + "-focused-regression",
                "Focused live-model rerun of previously failing holdout cases.",
                seed.systemVersion() + "+" + required("BUYSENSE_MODELPORT_MODEL"),
                selected,
                seed.protocol());
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
        EvaluationReport report = new EvaluationHarness(
                "full-bounded-agent-focused",
                agent::decide,
                new DeterministicEvaluationGrader(),
                judge).run(focused);

        EvaluationReportWriter writer = new EvaluationReportWriter(mapper);
        writer.writeJson(report, REPORT_DIRECTORY.resolve("report.json"));
        writer.writeMarkdown(report, REPORT_DIRECTORY.resolve("report.md"));

        assertThat(report.trials())
                .noneMatch(trial -> "execution_failed".equals(trial.verdict()))
                .allMatch(EvaluationReport.TrialResult::passed);
        assertThat(report.aggregate().redlinePassRate()).isEqualTo(1.0);
        assertThat(report.modelJudgments())
                .isNotEmpty()
                .allMatch(value -> value.label() == Label.PASS);
    }

    private static EvaluationCase withTrials(EvaluationCase value, int trials) {
        return new EvaluationCase(
                value.caseId(),
                value.domainPackId(),
                value.prompt(),
                value.tags(),
                trials,
                value.expected(),
                value.discovery(),
                value.manualReviewRubrics(),
                value.judgeRubrics());
    }

    private static int positiveInt(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
            // Fall through to a clear configuration error.
        }
        throw new IllegalStateException(name + " must be a positive integer");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required environment variable: " + name);
        }
        return value;
    }
}
