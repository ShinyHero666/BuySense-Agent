package com.buysense.sar.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime vocabulary exposed by a business asset pack. The planner only
 * depends on this contract, so adding a new domain does not change routing.
 */
public record DomainPackInfo(
        String packId,
        String displayName,
        String description,
        String workflowId,
        String capabilityProfileId,
        String defaultCategory,
        String primaryCategory,
        List<String> defaultBundleCategories,
        Map<String, List<String>> categoryTerms,
        List<String> useCases,
        Map<String, List<String>> brandTerms,
        List<String> protocolTerms,
        List<String> exampleQueries) {

    public DomainPackInfo {
        defaultBundleCategories = List.copyOf(defaultBundleCategories);
        categoryTerms = copyTerms(categoryTerms);
        useCases = List.copyOf(useCases);
        brandTerms = copyTerms(brandTerms);
        protocolTerms = List.copyOf(protocolTerms);
        exampleQueries = List.copyOf(exampleQueries);
    }

    public List<String> allTerms() {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        categoryTerms.values().forEach(terms::addAll);
        terms.addAll(useCases);
        brandTerms.values().forEach(terms::addAll);
        terms.addAll(protocolTerms);
        return terms.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }

    public List<String> categories() {
        return new ArrayList<>(categoryTerms.keySet());
    }

    private static Map<String, List<String>> copyTerms(Map<String, List<String>> source) {
        Map<String, List<String>> copied = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> copied.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(copied);
    }
}
