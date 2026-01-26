package com.moyuan.buysense.agent;

import com.moyuan.buysense.domain.Requirement;
import com.moyuan.buysense.domain.Requirement.Constraint;
import com.moyuan.buysense.domain.Requirement.ConstraintSource;
import com.moyuan.buysense.domain.Requirement.ConstraintStrength;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
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
            "(?:预算|不超过|控制在|价格(?:在)?|budget)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)\\s*(万|千|元|块|k)?",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, List<String>> CATEGORY_TERMS = Map.ofEntries(
            Map.entry("phone", List.of("手机", "iphone", "安卓", "phone", "smartphone")),
            Map.entry("headphones", List.of("耳机", "降噪", "headphone", "earbuds")),
            Map.entry("charger", List.of("充电器", "充电头", "充电线", "charger")),
            Map.entry("laptop", List.of("电脑", "笔记本", "laptop", "notebook")),
            Map.entry("mouse", List.of("鼠标", "mouse")),
            Map.entry("keyboard", List.of("键盘", "keyboard")),
            Map.entry("tablet", List.of("平板", "tablet", "ipad")),
            Map.entry("monitor", List.of("显示器", "屏幕", "monitor")),
            Map.entry("camera", List.of("相机", "微单", "camera")),
            Map.entry("smartwatch", List.of("手表", "智能表", "smartwatch"))
    );

    private final DomainProperties domain;

    public IntentParser(DomainProperties domain) {
        this.domain = domain;
    }

    public Requirement parse(String message) {
        String normalized = message.toLowerCase(Locale.ROOT).trim();
        BigDecimal budget = parseBudget(normalized);
        boolean bundleRequested = containsAny(normalized,
                List.of("一套", "套装", "整套", "搭配", "配上", "配套", "组合", "bundle"));
        boolean sponsoredAllowed = !containsAny(normalized,
                List.of("不要广告", "无广告", "不看广告", "排除广告", "no ads"));
        Set<String> required = new LinkedHashSet<>();
        Set<String> preferred = new LinkedHashSet<>();
        List<Constraint> constraints = new ArrayList<>();

        CATEGORY_TERMS.forEach((category, terms) -> {
            if (containsAny(normalized, terms)) {
                required.add(category);
                constraints.add(new Constraint(
                        "category",
                        "contains",
                        category,
                        ConstraintSource.USER,
                        ConstraintStrength.HARD,
                        1.0));
            }
        });

        if (bundleRequested && required.size() <= 1) {
            domain.defaultBundle().forEach(category -> {
                required.add(category);
                constraints.add(new Constraint(
                        "category",
                        "contains",
                        category,
                        ConstraintSource.SYSTEM,
                        ConstraintStrength.HARD,
                        1.0));
            });
        }

        List<String> useCases = new ArrayList<>();
        inferUseCase(normalized, "photography", List.of("拍照", "摄影", "相机", "人像"),
                useCases, preferred, constraints);
        inferUseCase(normalized, "gaming", List.of("游戏", "电竞", "高刷", "低延迟"),
                useCases, preferred, constraints);
        inferUseCase(normalized, "office", List.of("办公", "生产力", "会议", "文档"),
                useCases, preferred, constraints);
        inferUseCase(normalized, "travel", List.of("出差", "旅行", "便携", "续航"),
                useCases, preferred, constraints);
        inferUseCase(normalized, "commute", List.of("通勤", "地铁", "降噪"),
                useCases, preferred, constraints);

        if (budget != null) {
            constraints.add(new Constraint(
                    "totalPrice",
                    "<=",
                    budget,
                    ConstraintSource.USER,
                    ConstraintStrength.HARD,
                    1.0));
        }
        if (!sponsoredAllowed) {
            constraints.add(new Constraint(
                    "sponsored",
                    "=",
                    false,
                    ConstraintSource.USER,
                    ConstraintStrength.HARD,
                    1.0));
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
                bundleRequested);
    }

    /**
     * Adds model-provided retrieval vocabulary without allowing the model to
     * change the user's budget, category, ad or bundle constraints.
     */
    public Requirement enrich(Requirement original, String modelRewrite) {
        if (modelRewrite == null || modelRewrite.isBlank()) return original;

        Requirement inferred = parse(modelRewrite);
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

        return new Requirement(
                original.originalQuery(),
                original.retrievalQuery() + " " + modelRewrite.trim(),
                original.budget(),
                original.requiredCategories(),
                Set.copyOf(preferred),
                List.copyOf(useCases),
                List.copyOf(constraints),
                original.sponsoredAllowed(),
                original.bundleRequested());
    }

    private void inferUseCase(
            String query,
            String useCase,
            List<String> terms,
            List<String> useCases,
            Set<String> preferred,
            List<Constraint> constraints
    ) {
        if (!containsAny(query, terms)) return;
        useCases.add(useCase);
        if (useCase.equals("gaming") || useCase.equals("commute")) {
            preferred.add("headphones");
        }
        constraints.add(new Constraint(
                "useCase",
                "matches",
                useCase,
                ConstraintSource.MODEL,
                ConstraintStrength.SOFT,
                0.9));
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
        return terms.stream().anyMatch(term -> containsTerm(value, term));
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
}
