package com.moyuan.buysense.agent;

import com.moyuan.buysense.domain.Requirement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ExecutionRouter {
    public Route route(Requirement requirement) {
        List<String> reasons = new ArrayList<>();
        String query = requirement.originalQuery().toLowerCase(Locale.ROOT);
        boolean ambiguousLanguage = List.of(
                        "随便", "哪个好", "怎么选", "不太懂", "看着配", "性价比", "适合我", "帮我选")
                .stream()
                .anyMatch(query::contains);
        boolean clarification = false;
        String clarificationQuestion = null;

        if (requirement.requiredCategories().isEmpty()) {
            reasons.add("missing_product_category");
            clarification = true;
            clarificationQuestion = "你主要想购买哪类数码产品，使用场景和预算分别是什么？";
        }
        if (requirement.bundleRequested() && requirement.budget() == null) {
            reasons.add("bundle_without_budget");
            clarification = true;
            clarificationQuestion = "这套设备的总预算上限是多少？";
        }
        if (requirement.bundleRequested()) reasons.add("cross_category_bundle");
        if (requirement.requiredCategories().size() > 1) reasons.add("multiple_categories");
        if (requirement.useCases().size() > 1) reasons.add("multiple_soft_objectives");
        if (ambiguousLanguage) reasons.add("ambiguous_preference_language");

        boolean hybrid = clarification
                || requirement.bundleRequested()
                || requirement.requiredCategories().size() > 1
                || requirement.useCases().size() > 1
                || ambiguousLanguage;
        if (!hybrid) reasons.add("single_category_deterministic_path");

        return new Route(
                hybrid ? Mode.HYBRID : Mode.WORKFLOW,
                List.copyOf(reasons),
                clarification,
                clarificationQuestion);
    }

    public Route force(Requirement requirement, Mode mode) {
        Route automatic = route(requirement);
        List<String> reasons = new ArrayList<>(automatic.reasons());
        reasons.add("benchmark_override_" + mode.name().toLowerCase(Locale.ROOT));
        return new Route(
                mode,
                List.copyOf(reasons),
                automatic.clarificationRecommended(),
                automatic.clarificationQuestion());
    }

    public enum Mode {
        WORKFLOW, HYBRID
    }

    public record Route(
            Mode mode,
            List<String> reasons,
            boolean clarificationRecommended,
            String clarificationQuestion
    ) {
    }
}
