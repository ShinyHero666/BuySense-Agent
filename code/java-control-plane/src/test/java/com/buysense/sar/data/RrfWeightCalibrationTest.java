package com.buysense.sar.data;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfWeightCalibrationTest {
    private static final List<RrfWeightProfile> PROFILES = List.of(
            RrfWeightProfile.CALIBRATED_V1,
            profile("search-heavy", 1.00, 0.55),
            profile("recommendation-heavy", 1.00, 1.35),
            profile("equal-organic", 1.00, 1.00));

    @Test
    void committedOrganicProfileWinsRegressionSet() {
        List<RrfWeightEvaluator.BenchmarkCase> cases = benchmarkCases();
        RrfWeightProfile selected = RrfWeightEvaluator.chooseBest(PROFILES, cases, 4);
        RrfWeightEvaluator.Metrics calibrated =
                RrfWeightEvaluator.evaluate(RrfWeightProfile.CALIBRATED_V1, cases, 4);

        assertEquals(RrfWeightProfile.CALIBRATED_V1.profileId(), selected.profileId());
        for (RrfWeightProfile candidate : PROFILES.subList(1, PROFILES.size())) {
            assertTrue(calibrated.objective()
                    > RrfWeightEvaluator.evaluate(candidate, cases, 4).objective());
        }
        assertTrue(calibrated.recallAtK() >= 0.8);
        assertEquals(0.0, calibrated.sponsoredMisleadingRate());
    }

    @Test
    void sponsoredPolicyRejectsNoiseAndUsesOnlyTheSecondDisplaySlot() {
        Map<String, List<String>> rankings = new LinkedHashMap<>();
        rankings.put("search", List.of("a", "b", "c", "d"));
        rankings.put("recommendation", List.of("a", "c", "b", "d"));
        rankings.put("ads", List.of("ad-low-quality", "ad-safe"));

        Map<String, CommerceFusionEngine.CandidateSignals> candidates =
                new LinkedHashMap<>(organicSignals(Set.of("a", "b", "c", "d")));
        candidates.put("ad-low-quality", new CommerceFusionEngine.CandidateSignals(
                "default", 0.0, 0.98, 0.20, true));
        candidates.put("ad-safe", new CommerceFusionEngine.CandidateSignals(
                "default", 0.0, 0.90, 0.90, true));

        CommerceFusionEngine.FusionResult result = CommerceFusionEngine.fuse(
                RrfWeightProfile.CALIBRATED_V1, rankings, candidates, 4);

        assertEquals("a", result.slate().get(0));
        assertEquals("ad-safe", result.slate().get(1));
        assertEquals(List.of("ad-safe"), result.sponsoredIds());
        assertFalse(result.slate().contains("ad-low-quality"));
    }

    private static List<RrfWeightEvaluator.BenchmarkCase> benchmarkCases() {
        return List.of(
                benchmark("camera-intent", List.of("a", "b", "c", "d", "e"),
                        List.of("c", "e", "d", "a", "b"), Set.of("b", "c", "d")),
                benchmark("audio-intent", List.of("a", "b", "c", "d", "e"),
                        List.of("d", "c", "e", "b", "a"), Set.of("b", "c", "e")),
                benchmark("bundle-intent", List.of("a", "b", "c", "e", "d"),
                        List.of("c", "d", "e", "a", "b"), Set.of("b", "c", "e")),
                benchmark("compatibility-intent", List.of("a", "b", "c", "e", "d"),
                        List.of("e", "c", "d", "b", "a"), Set.of("b", "c", "d")),
                benchmark("value-intent", List.of("a", "b", "d", "c", "e"),
                        List.of("c", "d", "e", "b", "a"), Set.of("b", "d", "e")),
                benchmark("explore-intent", List.of("a", "b", "d", "c", "e"),
                        List.of("d", "e", "c", "a", "b"), Set.of("b", "c", "d")));
    }

    private static RrfWeightEvaluator.BenchmarkCase benchmark(
            String id,
            List<String> search,
            List<String> recommendation,
            Set<String> relevant
    ) {
        Map<String, List<String>> rankings = new LinkedHashMap<>();
        rankings.put("search", search);
        rankings.put("recommendation", recommendation);
        rankings.put("ads", List.of("ad-noise"));

        LinkedHashSet<String> organicIds = new LinkedHashSet<>(search);
        organicIds.addAll(recommendation);
        Map<String, CommerceFusionEngine.CandidateSignals> candidates =
                new LinkedHashMap<>(organicSignals(organicIds));
        candidates.put("ad-noise", new CommerceFusionEngine.CandidateSignals(
                "default", 0.0, 0.99, 0.10, true));
        return new RrfWeightEvaluator.BenchmarkCase(id, rankings, candidates, relevant);
    }

    private static Map<String, CommerceFusionEngine.CandidateSignals> organicSignals(Set<String> ids) {
        Map<String, CommerceFusionEngine.CandidateSignals> candidates = new LinkedHashMap<>();
        ids.forEach(id -> candidates.put(id,
                new CommerceFusionEngine.CandidateSignals("default", 1.0, 0.0, 0.0, false)));
        return candidates;
    }

    private static RrfWeightProfile profile(String id, double search, double recommendation) {
        return new RrfWeightProfile(
                id,
                "offline-organic-golden-v2",
                Map.of("search", search, "recommendation", recommendation));
    }
}
