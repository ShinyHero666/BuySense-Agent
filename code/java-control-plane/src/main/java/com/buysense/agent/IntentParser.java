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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class IntentParser {
    private static final String BUDGET_AMOUNT =
            "(?:人民币|[¥￥])?\\s*(\\d{1,3}(?:,\\d{3})+|\\d{1,9}(?:\\.\\d{1,2})?|[零〇一二两三四五六七八九十百千万]+)\\s*(万|千|[kKwW])?";
    private static final List<Pattern> BUDGET_PATTERNS = List.of(
            Pattern.compile(
                    "(?:总预算|预算|总价|价格(?:上限)?|上限|不超过|控制在|最多(?:花|出)?)"
                            + "\\s*(?:上限)?\\s*(?:大约|约|只有|为|是|最多)?\\s*"
                            + BUDGET_AMOUNT),
            Pattern.compile(
                    BUDGET_AMOUNT
                            + "\\s*(?:元|块(?:钱)?)?\\s*(?:以内|以下|内|封顶|上限|预算)"));
    private static final List<String> BUNDLE_TERMS = List.of("搭配", "套装", "一套", "配一个");
    private static final List<String> AD_OPT_OUT_TERMS = List.of(
            "不要广告", "不要竞价广告", "不看广告", "无广告", "拒绝广告", "关闭广告",
            "排除广告", "不接受广告", "不要赞助", "不看赞助", "只看自然", "只要自然结果");
    private static final String TURN_ID = "turn-current";
    private static final Pattern MONEY = Pattern.compile(BUDGET_AMOUNT + "\\s*(?:元|块(?:钱)?)");
    private static final String REJECT = "(?:不要|不买|不选|不用|不看|拒绝|排除|屏蔽|不接受|别推荐|别给我|不想看)";

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
                .filter(category -> categoryMentioned(normalized, category, domain))
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
                .filter(brand -> brand.terms().stream().noneMatch(term -> rejected(normalized, term)))
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
        List<BigDecimal> budgets = budgets(normalized);
        BigDecimal budget = budgets.size() == 1 ? budgets.get(0) : null;
        boolean sponsoredAllowed = AD_OPT_OUT_TERMS.stream().noneMatch(normalized::contains)
                && List.of("广告", "赞助", "付费推广", "商家花钱买的位置").stream()
                        .noneMatch(term -> rejected(normalized, term));

        List<Constraint> constraints = new ArrayList<>();
        if (budgets.size() > 1) {
            constraints.add(clarification("budget-conflict", "提到了多个预算，请确认这次选购的总预算上限。"));
        }
        if (budget == null && normalized.contains("预算")
                && !normalized.matches(".*(?:不限预算|预算不限|没有预算限制|预算还没定).*")) {
            constraints.add(clarification("budget-missing", "请明确预算上限，例如总预算5000元；不设上限也可以说明。"));
        }
        for (CommerceDomainPack.BrandDefinition brand : domain.brands()) {
            if (brand.terms().stream().anyMatch(term -> rejected(normalized, term))) {
                constraints.add(constraint("exclude-brand-" + brand.name(), "excludedBrands", brand.name(),
                        ConstraintSource.EXPLICIT_USER, ConstraintStrength.HARD, 1));
            }
        }
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

    private static List<BigDecimal> budgets(String message) {
        LinkedHashSet<BigDecimal> values = new LinkedHashSet<>();
        for (Pattern pattern : BUDGET_PATTERNS) {
            Matcher matcher = pattern.matcher(message);
            while (matcher.find()) {
                BigDecimal value = amount(matcher.group(1), matcher.group(2));
                if (value != null) values.add(value.stripTrailingZeros());
            }
        }
        return List.copyOf(values);
    }

    private static BigDecimal amount(String text, String unit) {
        BigDecimal value;
        if (text.matches("[0-9,.]+")) {
            value = new BigDecimal(text.replace(",", ""));
        } else {
            // Reject ambiguous colloquial amounts such as "三千五"; the user can clarify.
            if (text.matches(".*[百千万][一二两三四五六七八九]$")
                    || text.matches(".*[一二两三四五六七八九]{2}.*")) return null;
            long total = 0, section = 0, digit = 0;
            for (char c : text.toCharArray()) {
                int number = "零一二三四五六七八九".indexOf(c);
                if (c == '两') number = 2;
                if (c == '〇') number = 0;
                if (number >= 0) {
                    digit = number;
                } else {
                    int multiplier = switch (c) {
                        case '十' -> 10; case '百' -> 100; case '千' -> 1000; case '万' -> 10000;
                        default -> 0;
                    };
                    if (multiplier == 0) return null;
                    if (multiplier == 10000) {
                        total += (section + digit) * multiplier;
                        section = 0;
                    } else section += (digit == 0 ? 1 : digit) * multiplier;
                    digit = 0;
                }
            }
            value = BigDecimal.valueOf(total + section + digit);
        }
        if (unit != null) value = value.multiply(BigDecimal.valueOf(
                Set.of("千", "k").contains(unit.toLowerCase(Locale.ROOT)) ? 1000 : 10000));
        return value.signum() < 0 || value.compareTo(new BigDecimal("10000000")) > 0 ? null : value;
    }

    static boolean rejected(String message, String term) {
        String literal = Pattern.quote(term.toLowerCase(Locale.ROOT));
        return Pattern.compile(REJECT + "(?:任何|给我|推荐|买|用|选|的|品牌|来自|再)*\\s*" + literal
                        + "|" + literal + "(?:的|品牌|产品|手机|的东西|这种)?\\s*" + REJECT)
                .matcher(message.toLowerCase(Locale.ROOT)).find();
    }

    private static boolean categoryMentioned(String message, CommerceDomainPack.CategoryDefinition category,
                                             CommerceDomainPack domain) {
        if (Pattern.compile("(?<![a-z])" + Pattern.quote(category.id()) + "(?![a-z])").matcher(message).find()) return true;
        return category.terms().stream()
                .filter(term -> !List.of("快充", "音质", "降噪").contains(term))
                .map(term -> term.toLowerCase(Locale.ROOT))
                .anyMatch(term -> {
                    int offset = message.indexOf(term);
                    while (offset >= 0) {
                        int at = offset;
                        boolean covered = domain.categories().stream().flatMap(c -> c.terms().stream())
                                .map(t -> t.toLowerCase(Locale.ROOT))
                                .filter(t -> t.length() > term.length() && t.contains(term))
                                .anyMatch(t -> {
                                    int start = at - t.indexOf(term);
                                    return start >= 0 && message.startsWith(t, start);
                                });
                        if (!covered) return true;
                        offset = message.indexOf(term, offset + term.length());
                    }
                    return false;
                });
    }

    private static Constraint clarification(String id, String question) {
        return new Constraint(id, "clarification", question, ConstraintSource.SYSTEM,
                ConstraintStrength.HARD, 1, TURN_ID, ConstraintStatus.CONFLICTED);
    }

    Requirement resolveGrounded(Requirement baseline, JsonNode proposal, CommerceDomainPack domain) {
        List<Constraint> constraints = new ArrayList<>(baseline.constraints());
        List<String> categories = baseline.requiredCategories();
        BigDecimal budget = baseline.budget();
        boolean sponsored = baseline.sponsoredAllowed();
        if (proposal != null && proposal.isObject()) {
            JsonNode evidence = proposal.path("evidence");
            String source = groundedSpan(baseline.originalQuery(), evidence.path("budgetMax"));
            List<BigDecimal> extracted = source == null ? List.of() : budgets(source);
            if (extracted.isEmpty() && source != null) {
                Matcher money = MONEY.matcher(source);
                if (money.find()) {
                    BigDecimal value = amount(money.group(1), money.group(2));
                    if (value != null) extracted = List.of(value);
                }
            }
            if (budget == null && baseline.clarificationQuestions().isEmpty()
                    && extracted.size() == 1 && proposal.path("budgetMax").isNumber()
                    && extracted.get(0).compareTo(proposal.path("budgetMax").decimalValue()) == 0) {
                budget = extracted.get(0);
                constraints.add(constraint("model-budget", "budgetMax", budget,
                        ConstraintSource.INFERRED_MODEL, ConstraintStrength.HARD, 1));
            }
            boolean explicitCategory = constraints.stream().anyMatch(c -> c.field().equals("requestedCategories")
                    && c.source() == ConstraintSource.EXPLICIT_USER);
            if (!explicitCategory && proposal.path("requestedCategories").isArray()) {
                LinkedHashSet<String> grounded = new LinkedHashSet<>();
                proposal.path("requestedCategories").forEach(category -> {
                    String id = category.asText();
                    if (domain.categories().stream().anyMatch(c -> c.id().equals(id))
                            && groundedSpan(baseline.originalQuery(), evidence.path("requestedCategories").path(id)) != null) {
                        grounded.add(id);
                    }
                });
                if (!grounded.isEmpty()) {
                    categories = List.copyOf(grounded);
                    constraints.removeIf(c -> c.field().equals("requestedCategories"));
                    for (String category : categories) constraints.add(constraint("model-category-" + category,
                            "requestedCategories", category, ConstraintSource.INFERRED_MODEL, ConstraintStrength.HARD, 0.8));
                }
            }
            String adSpan = groundedSpan(baseline.originalQuery(), evidence.path("sponsoredAllowed"));
            if (proposal.path("sponsoredAllowed").isBoolean() && !proposal.path("sponsoredAllowed").asBoolean()
                    && adSpan != null && List.of("广告", "赞助", "付费推广", "商家花钱买的位置").stream()
                    .anyMatch(term -> rejected(adSpan, term))) sponsored = false;
            for (CommerceDomainPack.BrandDefinition brand : domain.brands()) {
                String span = groundedSpan(baseline.originalQuery(), evidence.path("excludedBrands").path(brand.name()));
                if (span != null && brand.terms().stream().anyMatch(term -> rejected(span, term))
                        && !baseline.excludedBrands().contains(brand.name())) {
                    constraints.add(constraint("model-exclude-" + brand.name(), "excludedBrands", brand.name(),
                            ConstraintSource.INFERRED_MODEL, ConstraintStrength.HARD, 0.8));
                }
            }
        }
        boolean identifiedCategory = constraints.stream().anyMatch(c -> c.field().equals("requestedCategories")
                && c.source() != ConstraintSource.SYSTEM);
        if (!identifiedCategory && baseline.useCases().isEmpty()) {
            constraints.add(clarification("category-missing", "请说明要购买的商品类别；当前入口支持"
                    + domain.categories().stream().map(CommerceDomainPack.CategoryDefinition::label)
                            .collect(java.util.stream.Collectors.joining("、")) + "。"));
        }
        List<String> excluded = constraints.stream().filter(c -> c.field().equals("excludedBrands"))
                .map(c -> String.valueOf(c.value())).toList();
        return new Requirement(baseline.originalQuery(), baseline.originalQuery(), budget, categories,
                baseline.preferredBrands().stream().filter(b -> !excluded.contains(b)).toList(),
                baseline.useCases(), constraints, sponsored, baseline.bundleRequested() || categories.size() > 1);
    }

    static String groundedSpan(String message, JsonNode value) {
        if (!value.isTextual()) return null;
        String span = Normalizer.normalize(value.asText(), Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
        return !span.isBlank() && span.length() <= 160 && message.contains(span) ? span : null;
    }
}
