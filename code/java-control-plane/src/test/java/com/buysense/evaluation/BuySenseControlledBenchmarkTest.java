package com.buysense.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.buysense.agent.IntentParser;
import com.buysense.agent.ModelPortAgentBridge;
import com.buysense.agent.ModelPortProperties;
import com.buysense.agent.SearchAdsRecsLeadService;
import com.buysense.evaluation.BenchmarkExperimentRunner.BenchmarkComparison;
import com.buysense.evaluation.BenchmarkExperimentRunner.Variant;
import com.buysense.evaluation.OpenAiCompatibleEvaluationJudge.JudgeConfig;
import com.buysense.platform.DomainPackRegistry;
import com.buysense.retail.ReviewEvidenceGateway;
import com.buysense.sar.data.JavaRetailDataPlane;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "BUYSENSE_BENCHMARK_EVAL", matches = "true")
class BuySenseControlledBenchmarkTest {
    private static final String TASKS =
            "evaluation/buysense_capability_holdout_v2.tasks.json";
    private static final String GOLD =
            "evaluation/private/buysense_capability_holdout_v2.gold.json";
    private static final Path REPORT_DIRECTORY = Path.of(
            "target", "evaluation", "controlled-benchmark-v2");

    @Autowired
    private SearchAdsRecsLeadService fullSystem;

    @Autowired
    private ModelPortProperties modelProperties;

    @Autowired
    private IntentParser parser;

    @Autowired
    private DomainPackRegistry domains;

    @Autowired
    private JavaRetailDataPlane dataPlane;

    @Autowired
    private ReviewEvidenceGateway reviewEvidence;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    @Qualifier("collaborationExecutor")
    private Executor collaborationExecutor;

    @DynamicPropertySource
    static void realModelProperties(DynamicPropertyRegistry registry) {
        registry.add("buysense.modelport.enabled", () -> true);
        registry.add("buysense.modelport.base-url", () -> required("BUYSENSE_MODELPORT_URL"));
        registry.add("buysense.modelport.api-key", () -> required("BUYSENSE_MODELPORT_KEY"));
        registry.add("buysense.modelport.model", () -> required("BUYSENSE_MODELPORT_MODEL"));
    }

    @Test
    void comparesFullSystemAgainstBaselineAndNoCriticAblation() {
        enforceIndependentJudge();
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

        ModelPortProperties baselineProperties = modelProperties.copy();
        baselineProperties.setEnabled(false);
        SearchAdsRecsLeadService baseline = agent(baselineProperties);

        ModelPortProperties noCriticProperties = modelProperties.copy();
        noCriticProperties.setDisabledRoles(Set.of("critic"));
        SearchAdsRecsLeadService noCritic = agent(noCriticProperties);

        BenchmarkComparison comparison = new BenchmarkExperimentRunner(
                new DeterministicEvaluationGrader()).run(
                        suite,
                        "deterministic-no-model",
                        List.of(
                                new Variant("deterministic-no-model", baseline::decide, judge),
                                new Variant("no-critic-ablation", noCritic::decide, judge),
                                new Variant("full-bounded-agent", fullSystem::decide, judge)));
        new BenchmarkComparisonWriter(mapper).write(comparison, REPORT_DIRECTORY);

        assertThat(comparison.reports()).hasSize(3)
                .allSatisfy(report -> {
                    assertThat(report.protocol().taskSetSha256()).matches("[0-9a-f]{64}");
                    assertThat(report.protocol().goldSetSha256()).matches("[0-9a-f]{64}");
                    assertThat(report.trials()).noneMatch(
                            trial -> "execution_failed".equals(trial.verdict()));
                });
        EvaluationReport baselineReport = report(comparison, "deterministic-no-model");
        EvaluationReport noCriticReport = report(comparison, "no-critic-ablation");
        EvaluationReport fullReport = report(comparison, "full-bounded-agent");
        assertThat(baselineReport.aggregate().averageTokens()).isEqualTo(0.0);
        assertThat(noCriticReport.aggregate().averageTokens()).isGreaterThan(0.0);
        assertThat(fullReport.aggregate().averageTokens()).isGreaterThan(0.0);
        assertThat(comparison.deltas()).hasSize(3);
    }

    private SearchAdsRecsLeadService agent(ModelPortProperties properties) {
        return new SearchAdsRecsLeadService(
                parser,
                domains,
                dataPlane,
                reviewEvidence,
                new ModelPortAgentBridge(properties, mapper),
                mapper,
                collaborationExecutor);
    }

    private static EvaluationReport report(BenchmarkComparison value, String variantId) {
        return value.reports().stream()
                .filter(report -> report.systemVariantId().equals(variantId))
                .findFirst()
                .orElseThrow();
    }

    private static void enforceIndependentJudge() {
        String target = required("BUYSENSE_MODELPORT_MODEL");
        String judge = required("BUYSENSE_JUDGE_MODEL");
        boolean override = Boolean.parseBoolean(System.getenv(
                "BUYSENSE_ALLOW_SAME_MODEL_JUDGE"));
        if (target.equalsIgnoreCase(judge) && !override) {
            throw new IllegalStateException(
                    "judge model must differ from target model; set explicit override only for provisional evaluation");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required environment variable: " + name);
        }
        return value;
    }
}
