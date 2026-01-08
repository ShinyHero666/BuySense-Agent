package com.buysense.evaluation;

import com.buysense.evaluation.EvaluationModelJudge.Label;
import com.buysense.evaluation.EvaluationReport.ManualReviewItem;
import com.buysense.evaluation.EvaluationReport.ModelJudgment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Measures model-judge agreement against blinded human labels. */
public final class HumanCalibrationAnalyzer {
    public CalibrationReport analyze(
            EvaluationReport report,
            String reviewerId,
            List<HumanDecision> decisions
    ) {
        Map<String, ManualReviewItem> queue = new LinkedHashMap<>();
        report.manualReviewQueue().stream()
                .filter(HumanCalibrationAnalyzer::calibrationItem)
                .forEach(value -> queue.put(value.reviewId(), value));
        validate(queue, decisions);

        int agreements = 0;
        int comparable = 0;
        int humanPasses = 0;
        int modelPasses = 0;
        int uncertain = 0;
        List<Disagreement> disagreements = new ArrayList<>();
        for (HumanDecision decision : decisions) {
            ManualReviewItem item = queue.get(decision.reviewId());
            ModelJudgment judgment = judgment(report, item);
            if (decision.label() == HumanLabel.UNCERTAIN || judgment.label() == Label.UNKNOWN) {
                uncertain++;
                continue;
            }
            comparable++;
            boolean humanPass = decision.label() == HumanLabel.PASS;
            boolean modelPass = judgment.label() == Label.PASS;
            if (humanPass) humanPasses++;
            if (modelPass) modelPasses++;
            if (humanPass == modelPass) {
                agreements++;
            } else {
                disagreements.add(new Disagreement(
                        item.reviewId(),
                        item.caseId(),
                        item.runId(),
                        String.valueOf(item.context().get("judgeRubricId")),
                        item.rubric(),
                        item.prompt(),
                        item.response(),
                        judgment.label(),
                        judgment.score(),
                        judgment.rationale(),
                        decision.label(),
                        decision.rationale()));
            }
        }
        double agreementRate = ratio(agreements, comparable);
        double expectedAgreement = expectedAgreement(
                comparable, humanPasses, modelPasses);
        boolean kappaDefined = expectedAgreement < 1.0;
        Double kappa = kappaDefined
                ? round((agreementRate - expectedAgreement) / (1.0 - expectedAgreement))
                : null;
        return new CalibrationReport(
                "1.0",
                report.suiteId(),
                report.systemVariantId(),
                reviewerId == null ? "" : reviewerId,
                Instant.now(),
                queue.size(),
                decisions.size(),
                comparable,
                uncertain,
                agreements,
                agreementRate,
                kappaDefined,
                kappa,
                disagreements);
    }

    private static boolean calibrationItem(ManualReviewItem value) {
        return "BLINDED_JUDGE_CALIBRATION".equals(value.context().get("reviewType"));
    }

    private static void validate(
            Map<String, ManualReviewItem> queue,
            List<HumanDecision> decisions
    ) {
        if (decisions == null) throw new IllegalArgumentException("human decisions are required");
        Set<String> ids = new HashSet<>();
        for (HumanDecision value : decisions) {
            if (value == null || value.reviewId().isBlank() || !ids.add(value.reviewId())) {
                throw new IllegalArgumentException("human review ids must be non-blank and unique");
            }
            if (!queue.containsKey(value.reviewId())) {
                throw new IllegalArgumentException("unknown calibration review: " + value.reviewId());
            }
        }
    }

    private static ModelJudgment judgment(
            EvaluationReport report,
            ManualReviewItem item
    ) {
        String rubricId = String.valueOf(item.context().get("judgeRubricId"));
        return report.modelJudgments().stream()
                .filter(value -> value.runId().equals(item.runId()))
                .filter(value -> value.rubricId().equals(rubricId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "model judgment missing for calibration review: " + item.reviewId()));
    }

    private static double expectedAgreement(int total, int humanPasses, int modelPasses) {
        if (total == 0) return 0.0;
        double humanPassRate = (double) humanPasses / total;
        double modelPassRate = (double) modelPasses / total;
        return humanPassRate * modelPassRate
                + (1.0 - humanPassRate) * (1.0 - modelPassRate);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : round((double) numerator / denominator);
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public enum HumanLabel {
        PASS,
        FAIL,
        UNCERTAIN;

        public static HumanLabel parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("human label must be PASS, FAIL, or UNCERTAIN");
            }
        }
    }

    public record HumanDecision(
            String reviewId,
            HumanLabel label,
            String rationale
    ) {
        public HumanDecision {
            reviewId = reviewId == null ? "" : reviewId;
            label = label == null ? HumanLabel.UNCERTAIN : label;
            rationale = rationale == null ? "" : rationale;
        }
    }

    public record CalibrationReport(
            String schemaVersion,
            String suiteId,
            String systemVariantId,
            String reviewerId,
            Instant generatedAt,
            int queueSize,
            int submittedCount,
            int comparableCount,
            int uncertainCount,
            int agreementCount,
            double agreementRate,
            boolean cohensKappaDefined,
            Double cohensKappa,
            List<Disagreement> disagreements
    ) {
        public CalibrationReport {
            disagreements = List.copyOf(disagreements);
        }
    }

    public record Disagreement(
            String reviewId,
            String caseId,
            String runId,
            String rubricId,
            String rubric,
            String prompt,
            String response,
            Label modelLabel,
            int modelScore,
            String modelRationale,
            HumanLabel humanLabel,
            String humanRationale
    ) {
    }
}
