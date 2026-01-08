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
    private static final String BUDGET_AMOUNT =
            "(?:人民币|[¥￥])?\\s*(\\d{1,3}(?:,\\d{3})+|\\d{1,9}(?:\\.\\d{1,2})?)\\s*(万|千|[kKwW])?";
    private static final List<Pattern> BUDGET_PATTERNS = List.of(
            Pattern.compile(
                    "(?:总预算|预算|总价|价格(?:上限)?|上限|不超过|控制在|最多(?:花|出)?)"
                            + "\\s*(?:大约|约|只有|为|是|最多)?[^\\d¥￥]{0,4}"
                            + BUDGET_AMOUNT),
            Pattern.compile(
                    BUDGET_AMOUNT
                            + "\\s*(?:元|块(?:钱)?)?\\s*(?:以内|以下|内|封顶|上限|预算)"));
    private static final List<String> BUNDLE_TERMS = List.of("搭配", "套装", "一套", "配一个");
    private static final List<String> AD_OPT_OUT_TERMS = List.of(
            "不要广告", "不要竞价广告", "不看广告", "无广告", "拒绝广告", "关闭广告",
            "排除广告", "不接受广告", "不要赞助", "不看赞助", "只看自然", "只要自然结果");
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
        LinkedHashSet<String> matchedUseCases = new LinkedHashSet<>();
        domain.useCases().stream()
                .filter(normalized::contains)
                .forEach(matchedUseCases::add);
        domain.useCaseAliases().forEach((term, canonical) -> {
            if (normalized.contains(term.toLowerCase(Locale.ROOT))) {
                matchedUseCases.add(canonical);
            }
        });
        List<String> useCases = domain.useCases().stream()
                .filter(matchedUseCases::contains)
                .toList();
        List<String> protocolTerms = domain.protocolTerms().stream()
                .filter(term -> normalized.contains(term.toLowerCase(Locale.ROOT)))
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
        for (int index = 0; index < protocolTerms.size(); index++) {
            constraints.add(constraint(
                    "constraint-protocol-" + index,
                    "protocolTerms",
                    protocolTerms.get(index),
                    ConstraintSource.EXPLICIT_USER,
                    ConstraintStrength.HARD,
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
        for (Pattern pattern : BUDGET_PATTERNS) {
            Matcher matcher = pattern.matcher(message);
            if (!matcher.find()) continue;
            BigDecimal value = new BigDecimal(matcher.group(1).replace(",", ""));
            String unit = matcher.group(2);
            if (unit == null) return value;
            return switch (unit.toLowerCase(Locale.ROOT)) {
                case "千", "k" -> value.multiply(BigDecimal.valueOf(1_000));
                case "万", "w" -> value.multiply(BigDecimal.valueOf(10_000));
                default -> value;
            };
        }
        return null;
    }
}
