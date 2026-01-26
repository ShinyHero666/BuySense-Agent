package com.moyuan.buysense.agent;

import com.moyuan.buysense.catalog.CatalogRepository;
import com.moyuan.buysense.domain.Candidate;
import com.moyuan.buysense.domain.DecisionResult;
import com.moyuan.buysense.domain.DecisionResult.BundleProposal;
import com.moyuan.buysense.domain.DecisionResult.TraceStep;
import com.moyuan.buysense.domain.Product;
import com.moyuan.buysense.domain.Requirement;
import org.springframework.beans.factory.annotation.Autowired;
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
            "ads", 0.35
    );

    private final CatalogRepository catalog;
    private final CommerceDomainPolicy domainPolicy;

    public DecisionEngine(CatalogRepository catalog) {
        this(catalog, new ThreeCDomainPolicy());
    }

    @Autowired
    public DecisionEngine(CatalogRepository catalog, CommerceDomainPolicy domainPolicy) {
        this.catalog = catalog;
        this.domainPolicy = domainPolicy;
    }

    public DecisionResult decide(Requirement requirement) {
        long started = System.nanoTime();
        List<Product> eligible = catalog.findAll().stream()
                .filter(product -> requirement.requiredCategories().isEmpty()
                        || requirement.requiredCategories().contains(product.category()))
                .filter(product -> requirement.sponsoredAllowed() || !product.sponsored())
                .filter(product -> requirement.budget() == null
                        || product.price().compareTo(requirement.budget()) <= 0)
                .toList();

        List<ScoredProduct> search = rank(eligible, product -> lexicalScore(product, requirement), false);
        List<ScoredProduct> recommendation = rank(eligible, product -> recommendationScore(product, requirement), false);
        List<ScoredProduct> ads = requirement.sponsoredAllowed()
                ? rank(eligible.stream()
                                .filter(Product::sponsored)
                                .filter(product -> product.qualityScore() >= 0.78)
                                .toList(),
                        product -> adScore(product, requirement), true)
                : List.of();

        List<Candidate> slate = fuse(search, recommendation, ads);
        List<BundleProposal> bundles = requirement.bundleRequested()
                ? optimizeBundles(requirement, slate)
                : List.of();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        List<TraceStep> trace = List.of(
                new TraceStep("intent", "constraints_extracted", Map.of(
                        "hardCategories", requirement.requiredCategories(),
                        "constraintCount", requirement.constraints().size())),
                new TraceStep("retrieval", "parallel_channels_completed", Map.of(
                        "search", search.size(), "recommendation", recommendation.size(), "ads", ads.size())),
                new TraceStep("fusion", "weighted_rrf_with_ad_guardrail", Map.of(
                        "rrfK", RRF_K, "top3SponsoredLimit", 1)),
                new TraceStep("bundle", requirement.bundleRequested() ? "global_enumeration" : "not_requested",
                        Map.of("proposalCount", bundles.size()))
        );
        return new DecisionResult(requirement, slate, bundles, trace, Map.of(
                "eligibleProducts", eligible.size(),
                "latencyMs", elapsedMs,
                "searchCandidates", search.size(),
                "recommendationCandidates", recommendation.size(),
                "adCandidates", ads.size(),
                "sponsoredInTop3", slate.stream().limit(3).filter(Candidate::sponsored).count()
        ), DecisionResult.offlineRuntime());
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
        score += product.tags().stream().filter(query::contains).count() * 0.18;
        score += requirement.useCases().stream().filter(product.tags()::contains).count() * 0.35;
        return score;
    }

    private double recommendationScore(Product product, Requirement requirement) {
        double useCaseFit = requirement.useCases().isEmpty()
                ? 0.5
                : requirement.useCases().stream().filter(product.tags()::contains).count()
                        / (double) requirement.useCases().size();
        double preferenceFit = requirement.preferredCategories().contains(product.category()) ? 1.0 : 0.5;
        return product.qualityScore() * 0.45
                + product.popularityScore() * 0.25
                + useCaseFit * 0.2
                + preferenceFit * 0.1;
    }

    private double adScore(Product product, Requirement requirement) {
        double relevance = Math.max(lexicalScore(product, requirement), recommendationScore(product, requirement));
        return relevance * 0.7 + product.bidScore() * 0.3;
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
        if (guarded.size() >= 3) {
            guarded.addAll(deferredAds);
        }
        return guarded.stream().limit(12).toList();
    }

    private void addRrf(Map<String, MutableCandidate> combined, String channel, List<ScoredProduct> ranked) {
        AtomicInteger rank = new AtomicInteger(1);
        ranked.forEach(item -> {
            int currentRank = rank.getAndIncrement();
            MutableCandidate candidate = combined.computeIfAbsent(item.product.id(), ignored -> new MutableCandidate(item.product));
            candidate.channels.add(channel);
            candidate.channelScores.put(channel, item.score);
            candidate.score += CHANNEL_WEIGHTS.get(channel) / (RRF_K + currentRank);
        });
    }

    private List<BundleProposal> optimizeBundles(Requirement requirement, List<Candidate> slate) {
        List<String> categories = new ArrayList<>(requirement.requiredCategories());
        if (categories.isEmpty()) return List.of();

        Map<String, List<Candidate>> byCategory = slate.stream()
                .collect(Collectors.groupingBy(candidate -> candidate.product().category()));
        if (categories.stream().anyMatch(category -> !byCategory.containsKey(category))) return List.of();

        List<List<Product>> combinations = new ArrayList<>();
        enumerate(categories, byCategory, 0, new ArrayList<>(), combinations);
        return combinations.stream()
                .map(items -> bundle(requirement, items))
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

    private BundleProposal bundle(Requirement requirement, List<Product> items) {
        BigDecimal total = items.stream().map(Product::price).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean budgetSatisfied = requirement.budget() == null || total.compareTo(requirement.budget()) <= 0;
        boolean compatible = domainPolicy.compatible(items);
        double score = items.stream().mapToDouble(product -> product.qualityScore() + product.popularityScore()).average().orElse(0);
        if (requirement.budget() != null && requirement.budget().signum() > 0) {
            BigDecimal utilization = total.divide(requirement.budget(), 4, RoundingMode.HALF_UP);
            score += Math.min(1.0, utilization.doubleValue()) * 0.15;
        }
        String id = items.stream().map(Product::id).collect(Collectors.joining("+"));
        return new BundleProposal(id, items, total, score, budgetSatisfied, compatible,
                domainPolicy.evidenceSources());
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
            return new Candidate(product, score, product.sponsored(), List.copyOf(channels),
                    Map.copyOf(channelScores), reasons);
        }
    }
}
