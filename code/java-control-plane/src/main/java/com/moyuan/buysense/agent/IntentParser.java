package com.moyuan.buysense.agent;

import com.moyuan.buysense.domain.Requirement;
import com.moyuan.buysense.domain.Requirement.Constraint;
import com.moyuan.buysense.domain.Requirement.ConstraintSource;
import com.moyuan.buysense.domain.Requirement.ConstraintStrength;
import com.moyuan.buysense.platform.CommerceDomainPack;
import com.moyuan.buysense.platform.DomainPackRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IntentParser {
    private static final Pattern BUDGET = Pattern.compile(
            "(?:总预算|预算|不超过|控制在|价格(?:在)?|budget)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)\\s*(万|千|元|块|k)?",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, List<String>> USE_CASE_TERMS = useCaseTerms();

    private final DomainPackRegistry domains;

    public IntentParser(DomainPackRegistry domains) {
        this.domains = domains;
    }

    public Requirement parse(String message) {
        return parse(message, DomainPackRegistry.DEFAULT_PACK_ID);
    }

    public Requirement parse(String message, String domainPackId) {
        CommerceDomainPack pack = domains.require(domainPackId);
        String normalized = message.toLowerCase(Locale.ROOT).trim();
        BigDecimal budget = parseBudget(normalized);
        boolean bundleRequested = containsAny(normalized,
                List.of("一套", "套装", "整套", "搭配", "配上", "配套", "组合", "bundle"));
        boolean sponsoredAllowed = !containsAny(normalized,
                List.of("不要广告", "无广告", "不看广告", "排除广告", "no ads"));
        Set<String> required = new LinkedHashSet<>();
        Set<String> preferred = new LinkedHashSet<>();
        List<Constraint> constraints = new ArrayList<>();

        for (CommerceDomainPack.CategoryDefinition category : pack.categories()) {
            if (containsAny(normalized, category.terms())) {
                required.add(category.id());
                constraints.add(new Constraint(
                        "category",
                        "contains",
                        category.id(),
                        ConstraintSource.USER,
                        ConstraintStrength.HARD,
                        1.0));
            }
        }

        if (bundleRequested && required.size() <= 1) {
            for (String category : pack.defaultBundleCategories()) {
                if (required.add(category)) {
                    constraints.add(new Constraint(
                            "category",
                            "contains",
                            category,
                            ConstraintSource.SYSTEM,
                            ConstraintStrength.HARD,
                            1.0));
                }
            }
        }

        List<String> useCases = new ArrayList<>();
        USE_CASE_TERMS.forEach((useCase, terms) -> {
            if (!containsAny(normalized, terms)) return;
            useCases.add(useCase);
            inferPreferredCategory(pack, useCase, preferred);
            constraints.add(new Constraint(
                    "useCase",
                    "matches",
                    useCase,
                    ConstraintSource.MODEL,
                    ConstraintStrength.SOFT,
                    0.9));
        });

        String queryBrand = "";
        for (CommerceDomainPack.BrandDefinition brand : pack.brands()) {
            if (!containsAny(normalized, brand.terms())) continue;
            queryBrand = brand.name();
            constraints.add(new Constraint(
                    "brand",
                    "prefers",
                    brand.name(),
                    ConstraintSource.USER,
                    ConstraintStrength.SOFT,
                    1.0));
            break;
        }

        if (budget != null) {
            constraints.add(new Constraint(
                    "totalPrice", "<=", budget, ConstraintSource.USER, ConstraintStrength.HARD, 1.0));
        }
        if (!sponsoredAllowed) {
            constraints.add(new Constraint(
                    "sponsored", "=", false, ConstraintSource.USER, ConstraintStrength.HARD, 1.0));
        }

        return new Requirement(
                message,
                message,
                budget,
                Set.copyOf(required),
                Set.copyOf(preferred),
                List.copyOf(useCases),
                List.copyOf(constraints),
                sponsoredAllowed,
                bundleRequested,
                queryBrand);
    }

    public Requirement enrich(Requirement original, String modelRewrite) {
        return enrich(original, modelRewrite, DomainPackRegistry.DEFAULT_PACK_ID);
    }

    /**
     * Model rewrites only add retrieval vocabulary and soft preferences. User
     * budget, category, ad and bundle constraints remain authoritative.
     */
    public Requirement enrich(Requirement original, String modelRewrite, String domainPackId) {
        if (modelRewrite == null || modelRewrite.isBlank()) return original;

        Requirement inferred = parse(modelRewrite, domainPackId);
        LinkedHashSet<String> preferred = new LinkedHashSet<>(original.preferredCategories());
        preferred.addAll(inferred.preferredCategories());
        if (original.requiredCategories().isEmpty()) preferred.addAll(inferred.requiredCategories());

        LinkedHashSet<String> useCases = new LinkedHashSet<>(original.useCases());
        useCases.addAll(inferred.useCases());
        List<Constraint> constraints = new ArrayList<>(original.constraints());
        inferred.constraints().stream()
                .filter(constraint -> constraint.field().equals("useCase"))
                .map(constraint -> new Constraint(
                        constraint.field(),
                        constraint.operator(),
                        constraint.value(),
                        ConstraintSource.MODEL,
                        ConstraintStrength.SOFT,
                        Math.min(0.8, constraint.confidence())))
                .filter(candidate -> constraints.stream().noneMatch(existing ->
                        existing.field().equals(candidate.field())
                                && existing.value().equals(candidate.value())))
                .forEach(constraints::add);

        String preferredBrand = original.preferredBrand().isBlank()
                ? inferred.preferredBrand()
                : original.preferredBrand();
        return new Requirement(
                original.originalQuery(),
                original.retrievalQuery() + " " + modelRewrite.trim(),
                original.budget(),
                original.requiredCategories(),
                Set.copyOf(preferred),
                List.copyOf(useCases),
                List.copyOf(constraints),
                original.sponsoredAllowed(),
                original.bundleRequested(),
                preferredBrand);
    }

    private static void inferPreferredCategory(
            CommerceDomainPack pack,
            String useCase,
            Set<String> preferred
    ) {
        if ((useCase.equals("gaming") || useCase.equals("commute"))
                && pack.categories().stream().anyMatch(category -> category.id().equals("headphones"))) {
            preferred.add("headphones");
        }
        if (useCase.equals("cold_weather") || useCase.equals("high_altitude")) {
            if (pack.categories().stream().anyMatch(category -> category.id().equals("camp_stove"))) {
                preferred.add("camp_stove");
            }
        }
    }

    private static BigDecimal parseBudget(String query) {
        Matcher matcher = BUDGET.matcher(query);
        if (!matcher.find()) return null;
        BigDecimal value = new BigDecimal(matcher.group(1));
        String unit = matcher.group(2);
        if ("万".equals(unit)) return value.multiply(BigDecimal.valueOf(10_000));
        if ("千".equals(unit) || "k".equalsIgnoreCase(unit)) {
            return value.multiply(BigDecimal.valueOf(1_000));
        }
        return value;
    }

    private static boolean containsAny(String value, List<String> terms) {
        return terms.stream().anyMatch(term -> containsTerm(value, term.toLowerCase(Locale.ROOT)));
    }

    private static boolean containsTerm(String value, String term) {
        boolean asciiWord = term.chars()
                .allMatch(character -> character < 128 && Character.isLetterOrDigit(character));
        if (!asciiWord) return value.contains(term);
        return Pattern.compile(
                        "(?<![a-z0-9])" + Pattern.quote(term) + "(?![a-z0-9])",
                        Pattern.CASE_INSENSITIVE)
                .matcher(value)
                .find();
    }

    private static Map<String, List<String>> useCaseTerms() {
        Map<String, List<String>> terms = new LinkedHashMap<>();
        terms.put("photography", List.of("拍照", "摄影", "相机", "人像"));
        terms.put("gaming", List.of("游戏", "电竞", "高刷", "低延迟"));
        terms.put("office", List.of("办公", "生产力", "会议", "文档"));
        terms.put("travel", List.of("出差", "旅行", "便携", "续航"));
        terms.put("commute", List.of("通勤", "地铁", "降噪"));
        terms.put("windproof", List.of("防风"));
        terms.put("lightweight", List.of("轻量"));
        terms.put("high_altitude", List.of("高海拔"));
        terms.put("cold_weather", List.of("低温"));
        terms.put("two_person", List.of("双人"));
        terms.put("stable", List.of("稳定"));
        terms.put("easy_clean", List.of("易清洁"));
        return Map.copyOf(terms);
    }
}