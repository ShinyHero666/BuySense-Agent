package com.buysense.sar.data;

import java.util.Objects;

/** Versioned sponsored-candidate admission and placement policy. */
public record SponsoredPlacementPolicy(
        String policyId,
        double organicRelevanceFloorRatio,
        double minimumAdQuality,
        int insertionIndex,
        int maximumSponsored,
        boolean disclosureRequired) {
    public static final SponsoredPlacementPolicy SAFE_V1 =
            new SponsoredPlacementPolicy(
                    "sponsored-safety-v1",
                    0.85,
                    0.50,
                    1,
                    1,
                    true);

    public SponsoredPlacementPolicy {
        policyId = Objects.requireNonNull(policyId);
        if (policyId.isBlank()
                || !Double.isFinite(organicRelevanceFloorRatio)
                || organicRelevanceFloorRatio < 0.0
                || organicRelevanceFloorRatio > 1.0
                || !Double.isFinite(minimumAdQuality)
                || minimumAdQuality < 0.0
                || minimumAdQuality > 1.0
                || insertionIndex < 1
                || maximumSponsored < 0) {
            throw new IllegalArgumentException("invalid sponsored placement policy");
        }
    }
}
