package com.buysense.sar.control;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record RetailQuery(
        String domainPackId,
        String originalQuery,
        String intent,
        BigDecimal budgetMax,
        List<String> requestedCategories,
        List<String> useCases,
        List<String> preferredBrands,
        boolean sponsoredAllowed
) {
    public RetailQuery {
        domainPackId = Objects.requireNonNull(domainPackId);
        originalQuery = Objects.requireNonNull(originalQuery);
        intent = Objects.requireNonNull(intent);
        requestedCategories = List.copyOf(requestedCategories);
        useCases = List.copyOf(useCases);
        preferredBrands = List.copyOf(preferredBrands);
    }
}
