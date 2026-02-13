package com.moyuan.buysense.evaluation;

import java.util.List;

public record EvaluationSuite(
        String schemaVersion,
        String suiteId,
        String description,
        String systemVersion,
        List<EvaluationCase> cases
) {
    public EvaluationSuite {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
