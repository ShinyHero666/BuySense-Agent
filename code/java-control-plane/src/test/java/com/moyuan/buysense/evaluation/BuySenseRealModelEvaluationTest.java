package com.moyuan.buysense.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.agent.SearchAdsRecsLeadService;
import java.nio.file.Path;
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
@EnabledIfEnvironmentVariable(named = "BUYSENSE_REAL_MODEL_EVAL", matches = "true")
class BuySenseRealModelEvaluationTest {
    private static final String SUITE = "evaluation/buysense_seed_v1.json";
    private static final Path REPORT_DIRECTORY = Path.of("target", "evaluation");
    private static final Set<String> SMOKE_CASES = new LinkedHashSet<>(List.of(
            "3c-bundle-7000-photo-no-ads",
            "3c-phone-compare-no-ads",
            "3c-bundle-3000-budget-floor",
            "3c-false-order-claim",
            "camp-bundle-900-high-altitude",
            "camp-bundle-400-too-low",
            "camp-excluded-trailforge",
            "camp-ad-optout-soft-conflict"));

    @Autowired
    private SearchAdsRecsLeadService agent;

    @Autowired
    private ObjectMapper mapper;

    @DynamicPropertySource
    static void realModelProperties(DynamicPropertyRegistry registry) {
        registry.add("buysense.modelport.enabled", () -> true);
        registry.add("buysense.modelport.base-url", () -> required("MOYUAN_MODELPORT_URL"));
        registry.add("buysense.modelport.api-key", () -> required("MOYUAN_MODELPORT_KEY"));
        registry.add("buysense.modelport.model", () -> required("MOYUAN_MODELPORT_MODEL"));
    }

    @Test
    void runsRepresentativeCasesAgainstTheConfiguredRealModel() {
        EvaluationSuite seed = new EvaluationSuiteLoader(mapper).loadClasspath(SUITE);
        List<EvaluationCase> selected = seed.cases().stream()
                .filter(value -> SMOKE_CASES.contains(value.caseId()))
                .map(BuySenseRealModelEvaluationTest::singleTrial)
                .toList();
        assertThat(selected).hasSize(SMOKE_CASES.size());

        EvaluationSuite smoke = new EvaluationSuite(
                "1.0",
                "buysense-real-model-smoke-v1",
                "Representative live-model smoke suite derived from buysense-seed-v1.",
                seed.systemVersion() + "+" + required("MOYUAN_MODELPORT_MODEL"),
                selected);
        EvaluationReport report = new EvaluationHarness(
                agent,
                new DeterministicEvaluationGrader()).run(smoke);
        EvaluationReportWriter writer = new EvaluationReportWriter(mapper);
        writer.writeJson(report, REPORT_DIRECTORY.resolve(
                "buysense-real-model-smoke-v1-report.json"));
        writer.writeMarkdown(report, REPORT_DIRECTORY.resolve(
                "buysense-real-model-smoke-v1-report.md"));

        assertThat(report.trials()).noneMatch(trial -> "execution_failed".equals(trial.verdict()));
        assertThat(report.aggregate().averageTokens()).isGreaterThan(0.0);
        assertThat(report.aggregate().redlinePassRate()).isEqualTo(1.0);
        assertThat(report.aggregate().taskSuccessRate()).isEqualTo(1.0);
    }

    private static EvaluationCase singleTrial(EvaluationCase value) {
        return new EvaluationCase(
                value.caseId(),
                value.domainPackId(),
                value.prompt(),
                value.tags(),
                1,
                value.expected(),
                value.discovery(),
                value.manualReviewRubrics());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required environment variable: " + name);
        }
        return value;
    }
}
