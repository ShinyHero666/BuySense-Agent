package com.moyuan.buysense.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.agent.SearchAdsRecsLeadService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BuySenseEvaluationHarnessTest {
    private static final String SUITE = "evaluation/buysense_seed_v1.json";
    private static final Path REPORT_DIRECTORY = Path.of("target", "evaluation");

    @Autowired
    private SearchAdsRecsLeadService agent;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void runsTheSeedSuiteAndPersistsReplayableEvidence() throws Exception {
        EvaluationSuite suite = new EvaluationSuiteLoader(mapper).loadClasspath(SUITE);
        assertThat(suite.cases()).hasSizeBetween(20, 50);

        EvaluationReport report = new EvaluationHarness(
                agent,
                new DeterministicEvaluationGrader()).run(suite);
        EvaluationReportWriter writer = new EvaluationReportWriter(mapper);
        writer.writeJson(report, REPORT_DIRECTORY.resolve("buysense-seed-v1-report.json"));
        writer.writeMarkdown(report, REPORT_DIRECTORY.resolve("buysense-seed-v1-report.md"));

        assertThat(report.aggregate().caseCount()).isEqualTo(suite.cases().size());
        assertThat(report.aggregate().trialCount()).isEqualTo(
                suite.cases().stream().mapToInt(EvaluationCase::trials).sum());
        assertThat(report.trials()).allSatisfy(trial -> {
            assertThat(trial.runId()).isNotBlank();
            assertThat(trial.assertions()).isNotEmpty();
            if ("execution_failed".equals(trial.verdict())) {
                assertThat(trial.assertions())
                        .anyMatch(assertion -> "harness.execution".equals(assertion.code()));
            } else {
                assertThat(trial.trace()).isNotEmpty();
            }
        });
        assertThat(report.aggregate().redlinePassRate()).isEqualTo(1.0);
        assertThat(report.aggregate().taskSuccessRate()).isEqualTo(1.0);
        assertThat(report.status()).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(Files.readString(REPORT_DIRECTORY.resolve("buysense-seed-v1-report.md")))
                .contains("## Failure Attribution")
                .contains("## Slice Results")
                .contains("**Observed response:**")
                .contains("**Decision:** `PENDING`");
    }
}
