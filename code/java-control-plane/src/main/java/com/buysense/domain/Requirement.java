package com.buysense.domain;

import java.math.BigDecimal;
import java.util.List;

public record Requirement(
        String originalQuery,
        String retrievalQuery,
        BigDecimal budget,
        List<String> requiredCategories,
        List<String> preferredBrands,
        List<String> useCases,
        List<Constraint> constraints,
        boolean sponsoredAllowed,
        boolean bundleRequested
) {
    public Requirement {
        requiredCategories = List.copyOf(requiredCategories);
        preferredBrands = List.copyOf(preferredBrands);
        useCases = List.copyOf(useCases);
        constraints = List.copyOf(constraints);
    }

    public enum ConstraintSource {
        EXPLICIT_USER, INFERRED_MODEL, CATALOG, SYSTEM
    }

    public enum ConstraintStrength {
        HARD, SOFT
    }

    public enum ConstraintStatus {
        ACTIVE, SUPERSEDED, CONFLICTED
    }

    public record Constraint(
            String constraintId,
            String field,
            Object value,
            ConstraintSource source,
            ConstraintStrength strength,
            double confidence,
            String turnId,
            ConstraintStatus status
    ) {
    }
}
