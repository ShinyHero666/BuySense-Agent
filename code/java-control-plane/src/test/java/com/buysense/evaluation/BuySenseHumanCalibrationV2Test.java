package com.buysense.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.buysense.evaluation.HumanCalibrationAnalyzer.CalibrationReport;
import com.buysense.evaluation.HumanCalibrationAnalyzer.HumanDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
        named = "BUYSENSE_HUMAN_CALIBRATION_V2",
        matches = "true")
class BuySenseHumanCalibrationV2Test {
    private static final String LABELS =
            "evaluation/human/buysense-capability-holdout-v2.full-bounded-agent.labels.json";
    private static final Path OUTPUT_DIRECTORY = Path.of(
            "target", "evaluation", "controlled-benchmark-v2");
    private static final Path REPORT =
            OUTPUT_DIRECTORY.resolve("full-bounded-agent-report.json");

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void analyzesBlindedHumanLabelsAgainstTheSavedV2JudgeResults() throws Exception {
        EvaluationReport report = mapper.readValue(
                Files.readString(REPORT),
                EvaluationReport.class);
        LabelsFile labels;
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(LABELS)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "missing human calibration labels: " + LABELS);
            }
            labels = mapper.readValue(stream, LabelsFile.class);
        }
        assertThat(labels.suiteId()).isEqualTo(report.suiteId());
        assertThat(labels.systemVariantId())
                .isEqualTo(report.systemVariantId());

        CalibrationReport calibration =
                new HumanCalibrationAnalyzer().analyze(
                        report,
                        labels.reviewerId(),
                        labels.decisions());
        assertThat(calibration.submittedCount()).isEqualTo(4);
        assertThat(calibration.comparableCount()).isEqualTo(4);
        assertThat(calibration.uncertainCount()).isZero();
        assertThat(calibration.agreementRate()).isEqualTo(1.0);
        assertThat(calibration.cohensKappaDefined()).isFalse();
        assertThat(calibration.cohensKappa()).isNull();

        Files.createDirectories(OUTPUT_DIRECTORY);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                OUTPUT_DIRECTORY.resolve(
                        "full-bounded-agent-human-calibration.json").toFile(),
                calibration);
        Files.writeString(
                OUTPUT_DIRECTORY.resolve(
                        "full-bounded-agent-human-calibration.md"),
                markdown(calibration),
                StandardCharsets.UTF_8);
    }

    private static String markdown(CalibrationReport report) {
        String kappa = report.cohensKappaDefined()
                ? "%.4f".formatted(report.cohensKappa())
                : "N/A (single-class labels)";
        return """
                # BuySense Human Calibration

                - Suite: %s
                - Variant: %s
                - Reviewer: %s
                - Submitted / comparable: %d / %d
                - Agreement: %.2f%%
                - Cohen's Kappa: %s
                - Disagreements: %d
                """.formatted(
                report.suiteId(),
                report.systemVariantId(),
                report.reviewerId(),
                report.submittedCount(),
                report.comparableCount(),
                report.agreementRate() * 100.0,
                kappa,
                report.disagreements().size());
    }

    private record LabelsFile(
            String schemaVersion,
            String suiteId,
            String systemVariantId,
            String reviewerId,
            List<HumanDecision> decisions
    ) {
        private LabelsFile {
            decisions = List.copyOf(decisions);
        }
    }
}
