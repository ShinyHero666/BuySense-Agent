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
        boolean bundleRequested,
        String preferredBrand
) {
    public Requirement {
        requiredCategories = Set.copyOf(requiredCategories);
        preferredCategories = Set.copyOf(preferredCategories);
        useCases = List.copyOf(useCases);
        constraints = List.copyOf(constraints);
        preferredBrand = preferredBrand == null ? "" : preferredBrand.trim();
    }

    public Requirement(
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
        this(originalQuery, retrievalQuery, budget, requiredCategories, preferredCategories,
                useCases, constraints, sponsoredAllowed, bundleRequested, "");
    }

    public Requirement withPreferredBrand(String brand) {
        return new Requirement(
                originalQuery, retrievalQuery, budget, requiredCategories, preferredCategories,
                useCases, constraints, sponsoredAllowed, bundleRequested, brand);
    }

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