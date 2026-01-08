package com.buysense.sar.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned channel configuration. Search and recommendation participate in
 * organic RRF; Ads keeps a declared weight but is placed by a separate policy.
 */
public record RrfWeightProfile(
        String profileId,
        String calibrationVersion,
        Map<String, Double> weights,
        SponsoredPlacementPolicy sponsoredPolicy) {

    public static final double DEFAULT_SPONSORED_WEIGHT = 0.55;

    public static final RrfWeightProfile CALIBRATED_V1 = new RrfWeightProfile(
            "commerce-organic-rrf-v2",
            "offline-organic-golden-v2",
            Map.of(
                    "search", 1.00,
                    "recommendation", 0.90,
                    "ads", DEFAULT_SPONSORED_WEIGHT),
            SponsoredPlacementPolicy.SAFE_V1);

    public RrfWeightProfile {
        profileId = Objects.requireNonNull(profileId);
        calibrationVersion = Objects.requireNonNull(calibrationVersion);
        weights = Map.copyOf(weights);
        if (!weights.keySet().equals(Set.of("search", "recommendation", "ads"))
                || weights.values().stream().anyMatch(value ->
                        value == null || !Double.isFinite(value) || value <= 0.0)) {
            throw new IllegalArgumentException(
                    "channel weights must define positive search, recommendation and ads values");
        }
        sponsoredPolicy = Objects.requireNonNull(sponsoredPolicy);
    }

    public RrfWeightProfile(
            String profileId,
            String calibrationVersion,
            Map<String, Double> weights) {
        this(profileId, calibrationVersion, withSponsoredWeight(weights),
                SponsoredPlacementPolicy.SAFE_V1);
    }

    public double weight(String channel) {
        Double value = weights.get(channel);
        if (value == null) {
            throw new IllegalArgumentException("unsupported fusion channel: " + channel);
        }
        return value;
    }

    private static Map<String, Double> withSponsoredWeight(Map<String, Double> weights) {
        Map<String, Double> expanded = new LinkedHashMap<>(weights);
        expanded.putIfAbsent("ads", DEFAULT_SPONSORED_WEIGHT);
        return Map.copyOf(expanded);
    }
}
