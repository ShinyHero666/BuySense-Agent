package com.buysense.evaluation;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EvaluationCase(
        String caseId,
        String domainPackId,
        String prompt,
        List<String> tags,
        int trials,
        ExpectedBehavior expected,
        Discovery discovery,
        List<String> manualReviewRubrics,
        List<JudgeRubric> judgeRubrics
) {
    public EvaluationCase {
        tags = tags == null ? List.of() : List.copyOf(tags);
        trials = trials < 1 ? 1 : trials;
        discovery = discovery == null ? Discovery.defaults(caseId) : discovery;
        manualReviewRubrics = manualReviewRubrics == null
                ? List.of()
                : List.copyOf(manualReviewRubrics);
        judgeRubrics = judgeRubrics == null ? List.of() : List.copyOf(judgeRubrics);
    }

    public EvaluationCase(
            String caseId,
            String domainPackId,
            String prompt,
            List<String> tags,
            int trials,
            ExpectedBehavior expected,
            Discovery discovery,
            List<String> manualReviewRubrics
    ) {
        this(
                caseId,
                domainPackId,
                prompt,
                tags,
                trials,
                expected,
                discovery,
                manualReviewRubrics,
                List.of());
    }

    public enum RubricEnforcement {
        BLOCKING,
        ADVISORY
    }

    public record JudgeRubric(
            String rubricId,
            String criterion,
            Integer minimumScore,
            RubricEnforcement enforcement,
            boolean humanCalibrationRequired
    ) {
        public JudgeRubric {
            minimumScore = minimumScore == null ? 4 : minimumScore;
            enforcement = enforcement == null ? RubricEnforcement.BLOCKING : enforcement;
        }
    }

    public record ExpectedBehavior(
            String expectedVerdict,
            String expectedIntent,
            BigDecimal expectedBudget,
            List<String> expectedParsedCategories,
            List<String> requiredSelectedCategories,
            List<String> expectedUseCases,
            List<String> expectedBrands,
            List<String> forbiddenProductIds,
            Boolean sponsoredAllowed,
            Integer maxSponsoredTop3,
            Integer minSelectedItems,
            BigDecimal minBudgetUtilization,
            Integer minDistinctProducts,
            Integer minDistinctBrands,
            List<String> requiredAuditChecks,
            List<String> requiredTraceEvents,
            List<String> requiredResponsePhrases,
            List<String> forbiddenResponsePhrases,
            Integer maxTasks,
            Integer maxModelCalls,
            Integer maxRevisionAttempts,
            Long maxLatencyMs,
            List<String> requiredSelectedProductIds,
            List<String> requiredSelectedBrands,
            Map<String, List<String>> requiredTagsByCategory,
            Map<String, List<String>> requiredConnectorsByCategory,
            Map<String, List<String>> requiredProtocolsByCategory,
            Map<String, Integer> minimumPowerWattsByCategory,
            Integer minSponsoredSelectedItems
    ) {
        public ExpectedBehavior {
            expectedParsedCategories = copy(expectedParsedCategories);
            requiredSelectedCategories = copy(requiredSelectedCategories);
            expectedUseCases = copy(expectedUseCases);
            expectedBrands = copy(expectedBrands);
            forbiddenProductIds = copy(forbiddenProductIds);
            requiredAuditChecks = copy(requiredAuditChecks);
            requiredTraceEvents = copy(requiredTraceEvents);
            requiredResponsePhrases = copy(requiredResponsePhrases);
            forbiddenResponsePhrases = copy(forbiddenResponsePhrases);
            requiredSelectedProductIds = copy(requiredSelectedProductIds);
            requiredSelectedBrands = copy(requiredSelectedBrands);
            requiredTagsByCategory = copyLists(requiredTagsByCategory);
            requiredConnectorsByCategory = copyLists(requiredConnectorsByCategory);
            requiredProtocolsByCategory = copyLists(requiredProtocolsByCategory);
            minimumPowerWattsByCategory = minimumPowerWattsByCategory == null
                    ? Map.of()
                    : Map.copyOf(minimumPowerWattsByCategory);
        }

        private static List<String> copy(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }

        private static Map<String, List<String>> copyLists(Map<String, List<String>> values) {
            if (values == null) return Map.of();
            Map<String, List<String>> copy = new LinkedHashMap<>();
            values.forEach((key, value) -> copy.put(key, copy(value)));
            return Map.copyOf(copy);
        }
    }

    public record Discovery(
            String identityId,
            String sessionId,
            boolean personalizationEnabled,
            List<String> recentProductIds,
            List<String> excludedProductIds,
            List<String> adExposureProductIds
    ) {
        public Discovery {
            recentProductIds = copy(recentProductIds);
            excludedProductIds = copy(excludedProductIds);
            adExposureProductIds = copy(adExposureProductIds);
        }

        static Discovery defaults(String caseId) {
            String suffix = caseId == null || caseId.isBlank() ? "case" : caseId;
            return new Discovery(
                    "eval-user-" + suffix,
                    "eval-session-" + suffix,
                    false,
                    List.of(),
                    List.of(),
                    List.of());
        }

        private static List<String> copy(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }
}
