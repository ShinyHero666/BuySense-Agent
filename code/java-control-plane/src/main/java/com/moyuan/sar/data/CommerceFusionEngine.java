package com.moyuan.sar.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure ranking policy shared by online execution and offline evaluation.
 * Weighted RRF ranks organic channels; sponsored items use a separate gate.
 */
public final class CommerceFusionEngine {
    private static final double RRF_K = 60.0;

    private CommerceFusionEngine() {
    }

    public record CandidateSignals(
            String category,
            double organicScore,
            double sponsoredScore,
            double adQuality,
            boolean sponsored) {
        public CandidateSignals {
            category = Objects.requireNonNull(category);
            if (category.isBlank()
                    || !Double.isFinite(organicScore)
                    || organicScore < 0.0
                    || !Double.isFinite(sponsoredScore)
                    || sponsoredScore < 0.0
                    || !Double.isFinite(adQuality)
                    || adQuality < 0.0
                    || adQuality > 1.0) {
                throw new IllegalArgumentException("invalid fusion candidate signals");
            }
        }
    }

    public record FusionResult(
            List<String> slate,
            List<String> organicRanking,
            Map<String, Double> organicScores,
            Map<String, List<String>> organicSources,
            List<String> sponsoredIds) {
        public FusionResult {
            slate = List.copyOf(slate);
            organicRanking = List.copyOf(organicRanking);
            organicScores = Map.copyOf(organicScores);
            organicSources = Map.copyOf(organicSources);
            sponsoredIds = List.copyOf(sponsoredIds);
        }
    }

    public static FusionResult fuse(
            RrfWeightProfile profile,
            Map<String, List<String>> rankings,
            Map<String, CandidateSignals> signals,
            int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");

        Map<String, Double> organicScores = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> sourceSets = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> channel : rankings.entrySet()) {
            if ("ads".equals(channel.getKey())) continue;
            double weight = profile.weight(channel.getKey());
            List<String> ids = channel.getValue();
            for (int rank = 0; rank < ids.size(); rank++) {
                String id = ids.get(rank);
                requireSignals(signals, id);
                organicScores.merge(
                        id,
                        weight / (RRF_K + rank + 1),
                        Double::sum);
                sourceSets.computeIfAbsent(id, ignored -> new LinkedHashSet<>())
                        .add(channel.getKey());
            }
        }

        List<String> organicRanking = organicScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
        List<String> slate = new ArrayList<>(
                organicRanking.subList(0, Math.min(limit, organicRanking.size())));

        Map<String, Double> bestOrganicByCategory = new LinkedHashMap<>();
        for (String id : organicRanking) {
            CandidateSignals candidate = requireSignals(signals, id);
            bestOrganicByCategory.merge(
                    candidate.category(),
                    candidate.organicScore(),
                    Math::max);
        }

        SponsoredPlacementPolicy policy = profile.sponsoredPolicy();
        List<String> selectedSponsored = new ArrayList<>();
        if (policy.insertionIndex() < limit && !slate.isEmpty()) {
            for (String id : rankings.getOrDefault("ads", List.of())) {
                if (selectedSponsored.size() >= policy.maximumSponsored()) break;
                if (slate.contains(id)) continue;
                CandidateSignals candidate = requireSignals(signals, id);
                Double organicBest = bestOrganicByCategory.get(candidate.category());
                if (!candidate.sponsored()
                        || organicBest == null
                        || candidate.sponsoredScore()
                                < policy.organicRelevanceFloorRatio() * organicBest
                        || candidate.adQuality() < policy.minimumAdQuality()) {
                    continue;
                }
                int index = Math.min(
                        policy.insertionIndex() + selectedSponsored.size(),
                        slate.size());
                slate.add(index, id);
                selectedSponsored.add(id);
                if (slate.size() > limit) slate.remove(slate.size() - 1);
            }
        }

        Map<String, List<String>> organicSources = new LinkedHashMap<>();
        sourceSets.forEach((id, sources) ->
                organicSources.put(id, List.copyOf(sources)));
        return new FusionResult(
                slate,
                organicRanking,
                organicScores,
                organicSources,
                selectedSponsored);
    }

    private static CandidateSignals requireSignals(
            Map<String, CandidateSignals> signals,
            String id) {
        CandidateSignals candidate = signals.get(id);
        if (candidate == null) {
            throw new IllegalArgumentException("missing candidate signals: " + id);
        }
        return candidate;
    }
}
