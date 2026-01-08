package com.buysense.evaluation;

import com.buysense.evaluation.EvaluationCase.JudgeRubric;
import com.buysense.evaluation.EvaluationReport.AssertionResult;
import com.buysense.evaluation.EvaluationReport.SelectedProduct;
import java.util.List;

/** Independent qualitative grader; deterministic business rules remain outside this interface. */
public interface EvaluationModelJudge {
    boolean enabled();

    String modelId();

    JudgeBatch judge(JudgeRequest request, List<JudgeRubric> rubrics);

    static EvaluationModelJudge disabled() {
        return DisabledJudge.INSTANCE;
    }

    enum Label {
        PASS,
        FAIL,
        UNKNOWN
    }

    record JudgeRequest(
            String runId,
            String caseId,
            String domainPackId,
            String prompt,
            String response,
            List<SelectedProduct> selectedProducts,
            List<AssertionResult> deterministicAssertions,
            List<String> traceEvents
    ) {
        public JudgeRequest {
            selectedProducts = List.copyOf(selectedProducts);
            deterministicAssertions = List.copyOf(deterministicAssertions);
            traceEvents = List.copyOf(traceEvents);
        }
    }

    record Judgment(
            String rubricId,
            Label label,
            int score,
            double confidence,
            String rationale
    ) {
        public Judgment {
            label = label == null ? Label.UNKNOWN : label;
            score = Math.max(0, Math.min(5, score));
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            rationale = rationale == null ? "" : rationale;
        }
    }

    record JudgeBatch(
            String modelId,
            long latencyMs,
            int totalTokens,
            List<Judgment> judgments,
            String error
    ) {
        public JudgeBatch {
            modelId = modelId == null ? "" : modelId;
            judgments = judgments == null ? List.of() : List.copyOf(judgments);
            error = error == null ? "" : error;
        }
    }

    final class DisabledJudge implements EvaluationModelJudge {
        private static final DisabledJudge INSTANCE = new DisabledJudge();

        private DisabledJudge() {
        }

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public String modelId() {
            return "disabled";
        }

        @Override
        public JudgeBatch judge(JudgeRequest request, List<JudgeRubric> rubrics) {
            List<Judgment> pending = rubrics.stream()
                    .map(rubric -> new Judgment(
                            rubric.rubricId(),
                            Label.UNKNOWN,
                            0,
                            0.0,
                            "independent judge not configured"))
                    .toList();
            return new JudgeBatch(modelId(), 0, 0, pending, "judge_not_configured");
        }
    }
}
