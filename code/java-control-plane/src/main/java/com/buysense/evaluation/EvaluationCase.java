package com.buysense.evaluation;

import java.math.BigDecimal;
import java.util.List;

public record EvaluationCase(
        String caseId,
        String domainPackId,
        String prompt,
        List<String> tags,
        int trials,
        ExpectedBehavior expected,
        Discovery discovery,
        List<String> manualReviewRubrics
) {
    public EvaluationCase {
        tags = tags == null ? List.of() : List.copyOf(tags);
        trials = trials < 1 ? 1 : trials;
        discovery = discovery == null ? Discovery.defaults(caseId) : discovery;
        manualReviewRubrics = manualReviewRubrics == null
                ? List.of()
                : List.copyOf(manualReviewRubrics);
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
            Long maxLatencyMs
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
        }

        private static List<String> copy(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
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
