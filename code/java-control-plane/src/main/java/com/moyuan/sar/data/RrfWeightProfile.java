package com.moyuan.sar.data;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned organic-fusion configuration selected by an offline relevance
 * ablation. Sponsored placement is governed by a separate policy.
 */
public record RrfWeightProfile(
        String profileId,
        String calibrationVersion,
        Map<String, Double> weights,
        SponsoredPlacementPolicy sponsoredPolicy) {

    public static final RrfWeightProfile CALIBRATED_V1 = new RrfWeightProfile(
            "commerce-organic-rrf-v2",
            "offline-organic-golden-v2",
            Map.of(
                    "search", 1.00,
                    "recommendation", 0.90),
            SponsoredPlacementPolicy.SAFE_V1);

    public RrfWeightProfile {
        profileId = Objects.requireNonNull(profileId);
        calibrationVersion = Objects.requireNonNull(calibrationVersion);
        weights = Map.copyOf(weights);
        if (!weights.keySet().equals(Set.of("search", "recommendation"))
                || weights.values().stream().anyMatch(value ->
                        value == null || !Double.isFinite(value) || value <= 0.0)) {
            throw new IllegalArgumentException(
                    "RRF weights must define positive search and recommendation values");
        }
        sponsoredPolicy = Objects.requireNonNull(sponsoredPolicy);
    }

    public RrfWeightProfile(
            String profileId,
            String calibrationVersion,
            Map<String, Double> weights) {
        this(profileId, calibrationVersion, weights, SponsoredPlacementPolicy.SAFE_V1);
    }

    public double weight(String channel) {
        Double value = weights.get(channel);
        if (value == null) {
            throw new IllegalArgumentException("unsupported organic channel: " + channel);
        }
        return value;
    }
}
