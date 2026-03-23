package com.moyuan.buysense.agent;

import com.moyuan.buysense.domain.Candidate;
import com.moyuan.buysense.domain.DecisionResult;
import com.moyuan.buysense.domain.DecisionResult.BundleProposal;
import com.moyuan.buysense.domain.DecisionResult.TraceStep;
import com.moyuan.buysense.domain.Product;
import com.moyuan.buysense.domain.Requirement;
import com.moyuan.buysense.platform.CommerceDomainPack;
import com.moyuan.buysense.platform.DomainPackRegistry;
import com.moyuan.buysense.retail.RetailDataGateway;
import com.moyuan.buysense.retail.RetailDataSnapshot;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class DecisionEngine {
    private static final double RRF_K = 60.0;
    private static final Map<String, Double> CHANNEL_WEIGHTS = Map.of(
            "search", 1.0,
            "recommendation", 0.9,
            "ads", 0.35);

    private final RetailDataGateway retail;
    private final DomainPackRegistry domains;

    public DecisionEngine(RetailDataGateway retail, DomainPackRegistry domains) {
        this.retail = retail;
        this.domains = domains;
    }

    public DecisionResult decide(Requirement requirement) {
        return decide(requirement, DomainPackRegistry.DEFAULT_PACK_ID);
    }

    public DecisionResult decide(Requirement requirement, String domainPackId) {
        long started = System.nanoTime();
        CommerceDomainPack domain = domains.require(domainPackId);
        RetailDataSnapshot snapshot = retail.load(domain.packId());
        List<Product> eligible = snapshot.products().stream()
                .filter(snapshot::inStock)
                .filter(product -> requirement.requiredCategories().isEmpty()
                        || requirement.requiredCategories().contains(product.category()))
                .filter(product -> requirement.budget() == null
                        || product.price().compareTo(requirement.budget()) <= 0)
                .toList();

        List<ScoredProduct> search = rank(
                eligible, product -> lexicalScore(product, requirement), false);
        List<ScoredProduct> recommendation = rank(
                eligible, product -> recommendationScore(product, requirement, snapshot), false);
        List<ScoredProduct> ads = requirement.sponsoredAllowed()
                ? rank(eligible.stream()
                                .filter(Product::sponsored)
                                .filter(product -> product.qualityScore() >= 0.78)
                                .toList(),
                        product -> adScore(product, requirement, snapshot), true)
                : List.of();

        List<Candidate> slate = fuse(search, recommendation, ads);
        List<BundleProposal> bundles = requirement.bundleRequested()
                ? optimizeBundles(requirement, slate, snapshot, domain)
                : List.of();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        List<TraceStep> trace = List.of(
                new TraceStep("intent", "domain_constraints_extracted", Map.of(
                        "domainPackId", domain.packId(),
                        "hardCategories", requirement.requiredCategories(),
                        "constraintCount", requirement.constraints().size())),
                new TraceStep("retrieval", "parallel_channels_completed", Map.of(
                        "search", search.size(),
                        "recommendation", recommendation.size(),
                        "ads", ads.size(),
                        "catalogSource", snapshot.sources().get("catalog"))),
                new TraceStep("evidence", "pricing_reviews_and_compatibility_joined", Map.of(
                        "catalogVersion", snapshot.catalogVersion(),
                        "reviewVersion", snapshot.reviewVersion(),
                        "compatibilityVersion", snapshot.compatibilityVersion(),
                        "pricingSource", snapshot.sources().get("pricing"))),
                new TraceStep("fusion", "weighted_rrf_with_ad_guardrail", Map.of(
                        "rrfK", RRF_K, "top3SponsoredLimit", 1)),
                new TraceStep("bundle", requirement.bundleRequested() ? "global_enumeration" : "not_requested",
                        Map.of("proposalCount", bundles.size())));
        return new DecisionResult(requirement, slate, bundles, trace, Map.ofEntries(
                Map.entry("domainPackId", domain.packId()),
                Map.entry("eligibleProducts", eligible.size()),
                Map.entry("latencyMs", elapsedMs),
                Map.entry("searchCandidates", search.size()),
                Map.entry("recommendationCandidates", recommendation.size()),
                Map.entry("adCandidates", ads.size()),
                Map.entry("sponsoredInTop3", slate.stream().limit(3).filter(Candidate::sponsored).count()),
                Map.entry("catalogVersion", snapshot.catalogVersion()),
                Map.entry("reviewVersion", snapshot.reviewVersion()),
                Map.entry("compatibilityVersion", snapshot.compatibilityVersion()),
                Map.entry("catalogSource", snapshot.sources().get("catalog")),
                Map.entry("pricingSource", snapshot.sources().get("pricing")),
                Map.entry("reviewSource", snapshot.sources().get("reviews"))),
                DecisionResult.offlineRuntime());
    }

    private List<ScoredProduct> rank(List<Product> products, ScoreFunction scoring, boolean ads) {
        return products.stream()
                .map(product -> new ScoredProduct(product, scoring.score(product)))
                .filter(item -> item.score > (ads ? 0.25 : 0.05))
                .sorted(Comparator.comparingDouble(ScoredProduct::score).reversed()
                        .thenComparing(item -> item.product.id()))
                .limit(20)
                .toList();
    }

    private double lexicalScore(Product product, Requirement requirement) {
        String query = requirement.retrievalQuery().toLowerCase(Locale.ROOT);
        double score = 0;
        if (query.contains(product.name().toLowerCase(Locale.ROOT))) score += 0.8;
        if (query.contains(product.brand().toLowerCase(Locale.ROOT))) score += 0.35;
        if (requirement.requiredCategories().contains(product.category())) score += 0.35;
        score += product.tags().stream()
                .filter(tag -> query.contains(tag.toLowerCase(Locale.ROOT)))
                .count() * 0.18;
        score += requirement.useCases().stream()
                .filter(useCase -> matchesUseCase(product, useCase))
                .count() * 0.35;
        return score;
    }

    private double recommendationScore(
            Product product,
            Requirement requirement,
            RetailDataSnapshot snapshot
    ) {
        double useCaseFit = requirement.useCases().isEmpty()
                ? 0.5
                : requirement.useCases().stream().filter(useCase -> matchesUseCase(product, useCase)).count()
                        / (double) requirement.useCases().size();
        double categoryPreference = requirement.preferredCategories().contains(product.category()) ? 1.0 : 0.5;
        double brandPreference = !requirement.preferredBrand().isBlank()
                && product.brand().equalsIgnoreCase(requirement.preferredBrand()) ? 1.0 : 0.0;
        double reviewFit = snapshot.reviewFit(product, requirement.useCases());
        return product.qualityScore() * 0.32
                + product.popularityScore() * 0.18
                + useCaseFit * 0.18
                + categoryPreference * 0.08
                + reviewFit * 0.14
                + brandPreference * 0.10;
    }

    private double adScore(
            Product product,
            Requirement requirement,
            RetailDataSnapshot snapshot
    ) {
        double relevance = Math.max(
                lexicalScore(product, requirement),
                recommendationScore(product, requirement, snapshot));
        return relevance * 0.7 + product.bidScore() * 0.3;
    }

    private static boolean matchesUseCase(Product product, String useCase) {
        Map<String, List<String>> aliases = Map.ofEntries(
                Map.entry("photography", List.of("photography", "拍照", "摄影", "人像")),
                Map.entry("gaming", List.of("gaming", "游戏", "电竞", "高刷", "低延迟")),
                Map.entry("office", List.of("office", "办公", "生产力")),
                Map.entry("travel", List.of("travel", "旅行", "出差", "便携", "续航")),
                Map.entry("commute", List.of("commute", "通勤", "降噪")),
                Map.entry("windproof", List.of("防风")),
                Map.entry("lightweight", List.of("轻量")),
                Map.entry("high_altitude", List.of("高海拔")),
                Map.entry("cold_weather", List.of("低温")),
                Map.entry("two_person", List.of("双人")),
                Map.entry("stable", List.of("稳定")),
                Map.entry("easy_clean", List.of("易清洁")));
        return aliases.getOrDefault(useCase, List.of(useCase)).stream()
                .anyMatch(alias -> product.tags().stream().anyMatch(tag ->
                        tag.equalsIgnoreCase(alias) || tag.toLowerCase(Locale.ROOT)
                                .contains(alias.toLowerCase(Locale.ROOT))));
    }

    private List<Candidate> fuse(
            List<ScoredProduct> search,
            List<ScoredProduct> recommendation,
            List<ScoredProduct> ads
    ) {
        Map<String, MutableCandidate> combined = new LinkedHashMap<>();
        addRrf(combined, "search", search);
        addRrf(combined, "recommendation", recommendation);
        addRrf(combined, "ads", ads);

        List<Candidate> ordered = combined.values().stream()
                .map(MutableCandidate::freeze)
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparing(candidate -> candidate.product().id()))
                .toList();

        List<Candidate> guarded = new ArrayList<>();
        boolean sponsoredInTop3 = false;
        List<Candidate> deferredAds = new ArrayList<>();
        for (Candidate candidate : ordered) {
            if (candidate.sponsored() && guarded.size() < 3) {
                if (sponsoredInTop3) {
                    deferredAds.add(candidate);
                    continue;
                }
                sponsoredInTop3 = true;
            }
            guarded.add(candidate);
        }
        if (guarded.size() >= 3) guarded.addAll(deferredAds);
        return guarded.stream().limit(12).toList();
    }

    private void addRrf(Map<String, MutableCandidate> combined, String channel, List<ScoredProduct> ranked) {
        AtomicInteger rank = new AtomicInteger(1);
        ranked.forEach(item -> {
            int currentRank = rank.getAndIncrement();
            MutableCandidate candidate = combined.computeIfAbsent(
                    item.product.id(), ignored -> new MutableCandidate(item.product));
            candidate.channels.add(channel);
            candidate.channelScores.put(channel, item.score);
            candidate.score += CHANNEL_WEIGHTS.get(channel) / (RRF_K + currentRank);
        });
    }

    private List<BundleProposal> optimizeBundles(
            Requirement requirement,
            List<Candidate> slate,
            RetailDataSnapshot snapshot,
            CommerceDomainPack domain
    ) {
        List<String> categories = new ArrayList<>(requirement.requiredCategories());
        if (categories.isEmpty()) return List.of();

        Map<String, List<Candidate>> byCategory = slate.stream()
                .collect(Collectors.groupingBy(candidate -> candidate.product().category()));
        if (categories.stream().anyMatch(category -> !byCategory.containsKey(category))) return List.of();

        List<List<Product>> combinations = new ArrayList<>();
        enumerate(categories, byCategory, 0, new ArrayList<>(), combinations);
        return combinations.stream()
                .map(items -> bundle(requirement, items, snapshot, domain))
                .filter(BundleProposal::budgetSatisfied)
                .filter(BundleProposal::compatible)
                .sorted(Comparator.comparingDouble(BundleProposal::score).reversed()
                        .thenComparing(BundleProposal::totalPrice))
                .limit(3)
                .toList();
    }

    private void enumerate(
            List<String> categories,
            Map<String, List<Candidate>> byCategory,
            int index,
            List<Product> selected,
            List<List<Product>> output
    ) {
        if (index == categories.size()) {
            output.add(List.copyOf(selected));
            return;
        }
        for (Candidate candidate : byCategory.get(categories.get(index)).stream().limit(5).toList()) {
            selected.add(candidate.product());
            enumerate(categories, byCategory, index + 1, selected, output);
            selected.remove(selected.size() - 1);
        }
    }

    private BundleProposal bundle(
            Requirement requirement,
            List<Product> items,
            RetailDataSnapshot snapshot,
            CommerceDomainPack domain
    ) {
        BigDecimal total = items.stream().map(Product::price).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean budgetSatisfied = requirement.budget() == null
                || total.compareTo(requirement.budget()) <= 0;
        boolean compatible = snapshot.compatible(items, domain.primaryCategory());
        double score = items.stream()
                .mapToDouble(product -> product.qualityScore() + product.popularityScore())
                .average().orElse(0);
        if (requirement.budget() != null && requirement.budget().signum() > 0) {
            BigDecimal utilization = total.divide(requirement.budget(), 4, RoundingMode.HALF_UP);
            score += Math.min(1.0, utilization.doubleValue()) * 0.15;
        }
        String id = items.stream().map(Product::id).collect(Collectors.joining("+"));
        return new BundleProposal(
                id,
                items,
                total,
                score,
                budgetSatisfied,
                compatible,
                List.of(
                        snapshot.catalogVersion(),
                        snapshot.reviewVersion(),
                        snapshot.compatibilityVersion(),
                        snapshot.sources().get("pricing")));
    }

    private interface ScoreFunction {
        double score(Product product);
    }

    private record ScoredProduct(Product product, double score) {
    }

    private static final class MutableCandidate {
        private final Product product;
        private final Set<String> channels = new LinkedHashSet<>();
        private final Map<String, Double> channelScores = new HashMap<>();
        private double score;

        private MutableCandidate(Product product) {
            this.product = product;
        }

        private Candidate freeze() {
            List<String> reasons = channels.stream().map(channel -> "recalled_by_" + channel).toList();
            boolean sponsoredPlacement = channels.size() == 1 && channels.contains("ads");
            return new Candidate(
                    product,
                    score,
                    sponsoredPlacement,
                    List.copyOf(channels),
                    Map.copyOf(channelScores),
                    reasons);
        }
    }
}