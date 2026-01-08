package com.buysense.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.buysense.evaluation.HumanCalibrationAnalyzer.HumanDecision;
import com.buysense.evaluation.HumanCalibrationAnalyzer.HumanLabel;
import com.buysense.agent.SearchAdsRecsLeadService;
import com.buysense.evaluation.EvaluationCase.JudgeRubric;
import com.buysense.evaluation.EvaluationModelJudge.JudgeBatch;
import com.buysense.evaluation.EvaluationModelJudge.JudgeRequest;
import com.buysense.evaluation.EvaluationModelJudge.Judgment;
import com.buysense.evaluation.EvaluationModelJudge.Label;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(
        named = "BUYSENSE_LOCAL_CAPABILITY_BENCHMARK",
        matches = "true")
class BuySenseCapabilityBenchmarkTest {
    private static final String TASKS =
            "evaluation/buysense_capability_holdout_v1.tasks.json";
    private static final String GOLD =
            "evaluation/private/buysense_capability_holdout_v1.gold.json";
    private static final Path REPORT_DIRECTORY = Path.of(
            "target", "evaluation", "capability-holdout-v1-local");

    @Autowired
    private SearchAdsRecsLeadService agent;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void runsFrozenHoldoutAndPersistsCompositeEvidence() throws Exception {
        EvaluationSuite suite = new BenchmarkPartitionLoader(mapper)
                .loadClasspath(TASKS, GOLD);
        EvaluationReport report = new EvaluationHarness(
                "deterministic-local-verification",
                agent::decide,
                new DeterministicEvaluationGrader(),
                new FixedPassingJudge()).run(suite);
        EvaluationReportWriter writer = new EvaluationReportWriter(mapper);
        writer.writeJson(report, REPORT_DIRECTORY.resolve("report.json"));
        writer.writeMarkdown(report, REPORT_DIRECTORY.resolve("report.md"));

        assertThat(report.aggregate().caseCount()).isEqualTo(12);
        assertThat(report.aggregate().trialCount()).isEqualTo(36);
        assertThat(report.trials()).noneMatch(value -> "execution_failed".equals(value.verdict()));
        assertThat(report.aggregate().redlinePassRate()).isGreaterThanOrEqualTo(0.75);
        assertThat(report.aggregate().taskSuccessRate()).isGreaterThanOrEqualTo(0.50);
        assertThat(report.aggregate().compositeSuccessRate())
                .isEqualTo(report.aggregate().taskSuccessRate());
        assertThat(report.aggregate().compositePassPowerK()).isGreaterThanOrEqualTo(0.50);
        assertThat(report.aggregate().taskSuccessCi95Low())
                .isLessThan(report.aggregate().taskSuccessRate());
        assertThat(report.aggregate().taskSuccessCi95High())
                .isGreaterThanOrEqualTo(report.aggregate().taskSuccessRate());
        assertThat(report.modelJudgments()).isNotEmpty();
        assertThat(report.modelJudgments()).anyMatch(value -> value.label() == Label.PASS);
        assertThat(report.modelJudgments()).noneMatch(value -> value.label() == Label.FAIL);
        assertThat(report.manualReviewQueue()).hasSizeGreaterThanOrEqualTo(4)
                .allSatisfy(value -> {
                    assertThat(value.context()).containsKey("selectedProducts");
                    assertThat(value.context()).containsKey("deterministicAssertions");
                    assertThat(value.context()).containsEntry(
                            "modelDecisionHiddenUntilHumanLabel", true);
                });
        List<HumanDecision> humanDecisions = report.manualReviewQueue().stream()
                .map(value -> new HumanDecision(
                        value.reviewId(), HumanLabel.PASS, "test-only human fixture"))
                .toList();
        HumanCalibrationAnalyzer.CalibrationReport calibration =
                new HumanCalibrationAnalyzer().analyze(report, "test-reviewer", humanDecisions);
        assertThat(calibration.submittedCount()).isEqualTo(report.manualReviewQueue().size());
        assertThat(calibration.agreementRate()).isEqualTo(1.0);
        assertThat(calibration.cohensKappaDefined()).isFalse();
        assertThat(calibration.cohensKappa()).isNull();
        assertThat(report.status()).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(Files.readString(REPORT_DIRECTORY.resolve("report.md")))
                .contains("## Dataset Integrity")
                .contains("Composite Pass^k")
                .contains("## Independent Model Judge")
                .contains("modelDecisionHiddenUntilHumanLabel");
    }

    private static final class FixedPassingJudge implements EvaluationModelJudge {
        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public String modelId() {
            return "test-only-fixed-judge";
        }

        @Override
        public JudgeBatch judge(JudgeRequest request, List<JudgeRubric> rubrics) {
            List<Judgment> judgments = rubrics.stream()
                    .map(rubric -> new Judgment(
                            rubric.rubricId(),
                            Label.PASS,
                            rubric.minimumScore(),
                            1.0,
                            "test fixture; not a quality claim"))
                    .toList();
            return new JudgeBatch(modelId(), 1, 10, judgments, "");
        }
    }
}
