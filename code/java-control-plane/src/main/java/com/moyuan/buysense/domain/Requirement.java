package com.moyuan.buysense.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public record Requirement(
        String originalQuery,
        String retrievalQuery,
        BigDecimal budget,
        Set<String> requiredCategories,
        Set<String> preferredCategories,
        List<String> useCases,
        List<Constraint> constraints,
        boolean sponsoredAllowed,
        boolean bundleRequested
) {
    public enum ConstraintSource {
        USER, MODEL, SYSTEM, CATALOG
    }

    public enum ConstraintStrength {
        HARD, SOFT
    }

    public record Constraint(
            String field,
            String operator,
            Object value,
            ConstraintSource source,
            ConstraintStrength strength,
            double confidence
    ) {
    }
}
