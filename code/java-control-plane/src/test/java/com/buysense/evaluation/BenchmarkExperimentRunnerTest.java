package com.buysense.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buysense.evaluation.BenchmarkExperimentRunner.BenchmarkComparison;
import com.buysense.evaluation.BenchmarkExperimentRunner.Variant;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkExperimentRunnerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void isolatesVariantRunIdsAndWritesComparisonArtifacts(@TempDir Path directory)
            throws Exception {
        EvaluationSuite seed = new EvaluationSuiteLoader(mapper)
                .loadClasspath("evaluation/buysense_catalog_v2.json");
        EvaluationCase original = seed.cases().get(0);
        EvaluationCase singleTrial = new EvaluationCase(
                original.caseId(),
                original.domainPackId(),
                original.prompt(),
                original.tags(),
                1,
                original.expected(),
                original.discovery(),
                List.of());
        EvaluationSuite suite = new EvaluationSuite(
                "2.0", "comparison-test", "comparison test", "test", List.of(singleTrial));
        EvaluationHarness.DecisionSystem failing = (runId, prompt, domain, discovery) -> {
            throw new IllegalStateException("fixture failure");
        };

        BenchmarkComparison comparison = new BenchmarkExperimentRunner(
                new DeterministicEvaluationGrader()).run(
                        suite,
                        "baseline",
                        List.of(
                                new Variant("baseline", failing, EvaluationModelJudge.disabled()),
                                new Variant("candidate", failing, EvaluationModelJudge.disabled())));
        new BenchmarkComparisonWriter(mapper).write(comparison, directory);

        assertThat(comparison.reports()).extracting(EvaluationReport::systemVariantId)
                .containsExactly("baseline", "candidate");
        assertThat(comparison.reports().get(0).trials().get(0).runId())
                .contains("baseline")
                .isNotEqualTo(comparison.reports().get(1).trials().get(0).runId());
        assertThat(comparison.deltas()).hasSize(2);
        assertThat(Files.readString(directory.resolve("benchmark-comparison.md")))
                .contains("Delta From Reference")
                .contains("baseline")
                .contains("candidate");
        assertThat(directory.resolve("baseline-report.json")).exists();
        assertThat(directory.resolve("candidate-report.md")).exists();
    }

    @Test
    void rejectsDuplicateOrMissingReferenceVariants() {
        EvaluationHarness.DecisionSystem failing = (runId, prompt, domain, discovery) -> null;
        BenchmarkExperimentRunner runner = new BenchmarkExperimentRunner(
                new DeterministicEvaluationGrader());
        EvaluationSuite suite = new EvaluationSuite(
                "2.0", "empty", "test", "test", List.of());

        assertThatThrownBy(() -> runner.run(
                suite,
                "missing",
                List.of(
                        new Variant("same", failing, null),
                        new Variant("same", failing, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
