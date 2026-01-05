package com.buysense.agent;

import com.buysense.domain.Requirement;
import com.buysense.domain.Requirement.Constraint;
import com.buysense.domain.Requirement.ConstraintSource;
import com.buysense.domain.Requirement.ConstraintStatus;
import com.buysense.domain.Requirement.ConstraintStrength;
import com.buysense.platform.CommerceDomainPack;
import com.buysense.platform.DomainPackRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IntentParser {
    private static final Pattern BUDGET = Pattern.compile(
            "(?:预算|总价)[^\\d]{0,8}(\\d{1,9})|(?:不超过|控制在)\\s*(\\d{1,9})");
    private static final List<String> BUNDLE_TERMS = List.of("搭配", "套装", "一套", "配一个");
    private static final List<String> AD_OPT_OUT_TERMS = List.of("不要广告", "不看广告", "无广告");
    private static final String TURN_ID = "turn-current";

    private final DomainPackRegistry domains;

    public IntentParser(DomainPackRegistry domains) {
        this.domains = domains;
    }

    public Requirement parse(String message) {
        return parse(message, DomainPackRegistry.DEFAULT_PACK_ID);
    }

    public Requirement parse(String message, String domainPackId) {
        CommerceDomainPack domain = domains.require(domainPackId);
        String normalized = Normalizer.normalize(message, Normalizer.Form.NFKC)
                .trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) throw new IllegalArgumentException("message must not be empty");

        List<String> explicitCategories = domain.categories().stream()
                .filter(category -> category.terms().stream()
                        .map(term -> term.toLowerCase(Locale.ROOT))
                        .anyMatch(normalized::contains))
                .map(CommerceDomainPack.CategoryDefinition::id)
                .distinct()
                .toList();
        boolean bundle = BUNDLE_TERMS.stream().anyMatch(normalized::contains)
                || explicitCategories.size() > 1;
        List<String> requested = new ArrayList<>(explicitCategories);
        if (requested.isEmpty()) {
            if (bundle) requested.addAll(domain.defaultBundleCategories());
            else requested.add(domain.defaultCategory());
        } else if (bundle && !requested.contains(domain.primaryCategory())) {
            requested.add(0, domain.primaryCategory());
        }

        List<String> preferredBrands = domain.brands().stream()
                .filter(brand -> brand.terms().stream()
                        .map(term -> term.toLowerCase(Locale.ROOT))
                        .anyMatch(normalized::contains))
                .map(CommerceDomainPack.BrandDefinition::name)
                .distinct()
                .toList();
        List<String> useCases = domain.useCases().stream()
                .filter(normalized::contains)
                .distinct()
                .toList();
        BigDecimal budget = budget(normalized);
        boolean sponsoredAllowed = AD_OPT_OUT_TERMS.stream().noneMatch(normalized::contains);

        List<Constraint> constraints = new ArrayList<>();
        if (budget != null) {
            constraints.add(constraint(
                    "constraint-budget", "budgetMax", budget,
                    ConstraintSource.EXPLICIT_USER, ConstraintStrength.HARD, 1));
        }
        for (int index = 0; index < requested.size(); index++) {
            String category = requested.get(index);
            boolean explicit = explicitCategories.contains(category);
            constraints.add(constraint(
                    "constraint-category-" + index,
                    "requestedCategories",
                    category,
                    explicit ? ConstraintSource.EXPLICIT_USER : ConstraintSource.SYSTEM,
                    ConstraintStrength.HARD,
                    explicit ? 1 : 0.7));
        }
        for (int index = 0; index < preferredBrands.size(); index++) {
            constraints.add(constraint(
                    "constraint-brand-" + index,
                    "preferredBrands",
                    preferredBrands.get(index),
                    ConstraintSource.EXPLICIT_USER,
                    ConstraintStrength.SOFT,
                    1));
        }
        for (int index = 0; index < useCases.size(); index++) {
            constraints.add(constraint(
                    "constraint-use-case-" + index,
                    "useCases",
                    useCases.get(index),
                    ConstraintSource.EXPLICIT_USER,
                    ConstraintStrength.SOFT,
                    1));
        }
        return new Requirement(
                normalized,
                normalized,
                budget,
                requested,
                preferredBrands,
                useCases,
                constraints,
                sponsoredAllowed,
                bundle);
    }

    private static Constraint constraint(
            String id,
            String field,
            Object value,
            ConstraintSource source,
            ConstraintStrength strength,
            double confidence
    ) {
        return new Constraint(
                id, field, value, source, strength, confidence, TURN_ID, ConstraintStatus.ACTIVE);
    }

    private static BigDecimal budget(String message) {
        Matcher matcher = BUDGET.matcher(message);
        if (!matcher.find()) return null;
        String value = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
        return value == null ? null : new BigDecimal(value);
    }
}
