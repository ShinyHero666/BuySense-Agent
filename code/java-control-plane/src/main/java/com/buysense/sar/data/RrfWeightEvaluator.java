package com.buysense.sar.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic offline evaluator that calls the same fusion engine as runtime. */
public final class RrfWeightEvaluator {
    private RrfWeightEvaluator() {
    }

    public record BenchmarkCase(
            String id,
            Map<String, List<String>> rankings,
            Map<String, CommerceFusionEngine.CandidateSignals> candidates,
            Set<String> relevant) {
        public BenchmarkCase {
            rankings = copyRankings(rankings);
            candidates = Map.copyOf(candidates);
            relevant = Set.copyOf(relevant);
        }

        public Set<String> sponsored() {
            return candidates.entrySet().stream()
                    .filter(entry -> entry.getValue().sponsored())
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    public record Metrics(
            double recallAtK,
            double meanReciprocalRank,
            double sponsoredMisleadingRate,
            double objective) {
    }

    public static Metrics evaluate(
            RrfWeightProfile profile,
            List<BenchmarkCase> cases,
            int k) {
        if (cases.isEmpty()) throw new IllegalArgumentException("benchmark cases must not be empty");
        double recall = 0.0;
        double mrr = 0.0;
        double misleading = 0.0;
        for (BenchmarkCase benchmark : cases) {
            List<String> ranked = rank(profile, benchmark, k);
            int hits = 0;
            int firstRelevantRank = 0;
            int misleadingSponsored = 0;
            for (int index = 0; index < ranked.size(); index++) {
                String id = ranked.get(index);
                if (benchmark.relevant().contains(id)) {
                    hits++;
                    if (firstRelevantRank == 0) firstRelevantRank = index + 1;
                }
                if (benchmark.sponsored().contains(id)
                        && !benchmark.relevant().contains(id)) {
                    misleadingSponsored++;
                }
            }
            recall += benchmark.relevant().isEmpty()
                    ? 1.0
                    : (double) hits / benchmark.relevant().size();
            mrr += firstRelevantRank == 0 ? 0.0 : 1.0 / firstRelevantRank;
            misleading += (double) misleadingSponsored / Math.max(1, ranked.size());
        }
        double count = cases.size();
        double recallAtK = recall / count;
        double meanReciprocalRank = mrr / count;
        double sponsoredMisleadingRate = misleading / count;
        double objective = 0.60 * recallAtK
                + 0.35 * meanReciprocalRank
                - 0.05 * sponsoredMisleadingRate;
        return new Metrics(
                round(recallAtK),
                round(meanReciprocalRank),
                round(sponsoredMisleadingRate),
                round(objective));
    }

    public static RrfWeightProfile chooseBest(
            List<RrfWeightProfile> candidates,
            List<BenchmarkCase> cases,
            int k) {
        if (candidates.isEmpty()) throw new IllegalArgumentException("weight candidates must not be empty");
        return candidates.stream()
                .max(Comparator.comparingDouble(
                        profile -> evaluate(profile, cases, k).objective()))
                .orElseThrow();
    }

    public static List<String> rank(
            RrfWeightProfile profile,
            BenchmarkCase benchmark,
            int k) {
        return CommerceFusionEngine.fuse(
                profile,
                benchmark.rankings(),
                benchmark.candidates(),
                k).slate();
    }

    public static String renderReport(
            List<RrfWeightProfile> candidates,
            List<BenchmarkCase> cases,
            int k) {
        List<String> lines = new ArrayList<>();
        lines.add("# Organic Weighted RRF and Sponsored Safety Offline Check");
        lines.add("");
        lines.add("Dataset: commerce-organic-golden-v2; runtime-equivalent fusion; "
                + "metric: 0.60 Recall@K + 0.35 MRR - 0.05 sponsored misleading rate.");
        lines.add("");
        lines.add("| profile | search | recommendation | sponsored policy | Recall@K | MRR | misleading | objective |");
        lines.add("|---|---:|---:|---|---:|---:|---:|---:|");
        for (RrfWeightProfile profile : candidates) {
            Metrics metrics = evaluate(profile, cases, k);
            lines.add(String.format(java.util.Locale.ROOT,
                    "| %s | %.2f | %.2f | %s | %.3f | %.3f | %.3f | %.3f |",
                    profile.profileId(),
                    profile.weight("search"),
                    profile.weight("recommendation"),
                    profile.sponsoredPolicy().policyId(),
                    metrics.recallAtK(),
                    metrics.meanReciprocalRank(),
                    metrics.sponsoredMisleadingRate(),
                    metrics.objective()));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static Map<String, List<String>> copyRankings(Map<String, List<String>> source) {
        Map<String, List<String>> copied = new LinkedHashMap<>();
        source.forEach((key, value) -> copied.put(key, List.copyOf(value)));
        return Map.copyOf(copied);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
