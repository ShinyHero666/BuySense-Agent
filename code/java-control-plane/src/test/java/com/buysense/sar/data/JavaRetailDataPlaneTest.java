package com.buysense.sar.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRetailDataPlaneTest {
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final JavaRetailDataPlane plane = new JavaRetailDataPlane(
            mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void loadsBothDomainPacksAndReportsReady() {
        assertEquals("UP", plane.health().get("status"));
        assertEquals(true, plane.health().get("ready"));
        assertEquals("normal-3c-v1", plane.domainPacks().get("defaultPackId"));
        assertEquals(2, ((List<?>) plane.domainPacks().get("packs")).size());
    }

    @Test
    void adsReviewsCompatibilityAndQuoteCarryVersionedEvidence() {
        Map<String, Object> ads = plane.dispatch("/api/v2/discovery/ads", json(
                "{\"domain_pack_id\":\"normal-3c-v1\",\"query\":\"小米手机\","
                        + "\"requested_categories\":[\"phone\"],\"sponsored_allowed\":false}"));
        assertTrue(((List<?>) ads.get("items")).isEmpty());

        Map<String, Object> organic = plane.dispatch("/api/v2/discovery/search", json(
                "{\"domain_pack_id\":\"normal-3c-v1\",\"query\":\"小米手机\","
                        + "\"requested_categories\":[\"phone\"],\"sponsored_allowed\":false}"));
        List<Map<String, Object>> organicItems = asMaps((List<?>) organic.get("items"));
        assertFalse(organicItems.isEmpty());
        assertTrue(organicItems.stream().noneMatch(item -> Boolean.TRUE.equals(item.get("sponsored"))));

        Map<String, Object> reviews = plane.reviews("normal-3c-v1", json(
                "{\"product_ids\":[\"spu-iphone-15\",\"missing-product\"]}"));
        assertEquals("review-aspects-v1", reviews.get("review_snapshot_version"));
        assertEquals(List.of("missing-product"), reviews.get("missing_product_ids"));

        Map<String, Object> graph = plane.compatibility("normal-3c-v1", json(
                "{\"pairs\":[{\"product_sku_id\":\"sku-iphone-15-128-blue\","
                        + "\"accessory_sku_id\":\"sku-apple-charger-20w\"},{"
                        + "\"product_sku_id\":\"unknown\","
                        + "\"accessory_sku_id\":\"sku-apple-charger-20w\"}]}"));
        List<?> graphResults = (List<?>) graph.get("results");
        assertEquals("compatible", ((Map<?, ?>) graphResults.get(0)).get("status"));
        assertEquals("unknown", ((Map<?, ?>) graphResults.get(1)).get("status"));

        Map<String, Object> quote = plane.quote("normal-3c-v1", json(
                "{\"offer_ids\":[\"offer-iphone-15-128-blue-self\",\"missing-offer\"]}"));
        assertEquals("realtime-quote-snapshot-2026-08-01", quote.get("quote_version"));
        assertEquals(NOW.toString(), quote.get("issued_at"));
        List<?> quotes = (List<?>) quote.get("quotes");
        assertEquals("active", ((Map<?, ?>) quotes.get(0)).get("status"));
        assertEquals("unavailable", ((Map<?, ?>) quotes.get(1)).get("status"));
    }

    @Test
    void adOptOutClosesAdsButKeepsANaturalMatchFromTheSearchChannel() {
        JsonNode request = json(
                "{\"query\":\"预算500元，不要广告，推荐双人便携卡式炉\","
                        + "\"requested_categories\":[\"camp_stove\"],"
                        + "\"use_cases\":[\"双人\",\"便携\"],"
                        + "\"max_price\":500,\"sponsored_allowed\":false}");

        Map<String, Object> ads = plane.discovery("outdoor-camping-v1", "ads", request);
        assertTrue(((List<?>) ads.get("items")).isEmpty());

        Map<String, Object> organic = plane.discovery("outdoor-camping-v1", "search", request);
        List<Map<String, Object>> items = asMaps((List<?>) organic.get("items"));
        Map<String, Object> easyFlame = items.stream()
                .filter(item -> "spu-easyflame-cassette-stove".equals(item.get("product_id")))
                .findFirst()
                .orElseThrow();
        assertEquals("search", easyFlame.get("channel"));
        assertEquals(false, easyFlame.get("sponsored"));
    }
    @Test
    void fusionAndGlobalBundleOptimizationProduceACompleteDecision() {
        Map<String, Object> search = plane.discovery("normal-3c-v1", "search", json(
                "{\"query\":\"预算7000元，选拍照手机\",\"requested_categories\":[\"phone\"],"
                        + "\"use_cases\":[\"拍照\"],\"max_price\":7000}"));
        Map<String, Object> recommend = plane.discovery("normal-3c-v1", "recommendation", json(
                "{\"query\":\"手机搭配耳机和充电器\","
                        + "\"requested_categories\":[\"phone\",\"headphones\",\"charger\"],"
                        + "\"use_cases\":[\"通勤\",\"快充\"],\"max_price\":7000}"));
        List<Map<String, Object>> recommendationItems = asMaps((List<?>) recommend.get("items"));
        List<String> recommendationCategories = recommendationItems.stream()
                .map(item -> String.valueOf(item.get("category"))).toList();
        assertTrue(recommendationCategories.containsAll(List.of("headphones", "charger")));
        Map<String, Object> ads = plane.discovery("normal-3c-v1", "ads", json(
                "{\"query\":\"小米手机\",\"requested_categories\":[\"phone\"]}"));

        Map<String, Object> fusionInput = new LinkedHashMap<>();
        fusionInput.put("channels", List.of(search, recommend, ads));
        fusionInput.put("limit", 8);
        Map<String, Object> fused = plane.fuse("normal-3c-v1", mapper.valueToTree(fusionInput));
        assertEquals("organic-weighted-rrf-v3", fused.get("fusion_version"));
        assertEquals("commerce-organic-rrf-v2", fused.get("organic_weight_profile"));
        assertEquals("offline-organic-golden-v2", fused.get("calibration_version"));
        assertEquals(Map.of("search", 1.0, "recommendation", 0.9), fused.get("organic_weights"));
        Map<?, ?> sponsoredPolicy = (Map<?, ?>) fused.get("sponsored_policy");
        assertEquals("sponsored-safety-v1", sponsoredPolicy.get("policy_id"));
        assertEquals(0.85, sponsoredPolicy.get("organic_relevance_floor_ratio"));
        assertEquals(0.5, sponsoredPolicy.get("minimum_ad_quality"));
        assertEquals(2, sponsoredPolicy.get("display_slot"));
        assertFalse(((List<?>) fused.get("items")).isEmpty());
        assertTrue(((Number) fused.get("sponsored_count")).longValue() <= 1);

        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.addAll(asMaps((List<?>) search.get("items")));
        candidates.addAll(asMaps((List<?>) recommend.get("items")));
        Map<String, Object> bundleInput = new LinkedHashMap<>();
        bundleInput.put("items", candidates);
        bundleInput.put("requested_categories", List.of("phone", "headphones", "charger"));
        bundleInput.put("intent", "bundle");
        bundleInput.put("budget_max", 7000);
        Map<String, Object> bundles = plane.bundles(
                "normal-3c-v1", mapper.valueToTree(bundleInput));
        assertEquals("constraint-enumeration-v3-budget-target", bundles.get("optimizer_version"));
        assertEquals(true, bundles.get("complete"));
        List<Map<String, Object>> bundleResults = asMaps((List<?>) bundles.get("bundles"));
        assertFalse(bundleResults.isEmpty());
        double topBundlePrice = ((Number) bundleResults.get(0).get("total_price")).doubleValue();
        assertTrue(topBundlePrice >= 4550 && topBundlePrice <= 7000);
    }

    @Test
    void outdoorPackUsesItsOwnCatalogAndCompatibilityGraph() {
        Map<String, Object> search = plane.discovery("outdoor-camping-v1", "search", json(
                "{\"query\":\"高海拔防风炉具\",\"requested_categories\":[\"camp_stove\"],"
                        + "\"use_cases\":[\"高海拔\",\"防风\"],\"max_price\":900}"));
        assertEquals("outdoor-camping-snapshot-v1", search.get("catalog_version"));
        assertFalse(((List<?>) search.get("items")).isEmpty());

        Map<String, Object> graph = plane.compatibility("outdoor-camping-v1", json(
                "{\"pairs\":[{\"product_sku_id\":\"sku-trailforge-alpine-stove\","
                        + "\"accessory_sku_id\":\"sku-trailforge-four-season-gas\"}]}"));
        assertEquals("outdoor-camping-compatibility-v1", graph.get("graph_version"));
        assertEquals("compatible", ((Map<?, ?>) ((List<?>) graph.get("results")).get(0)).get("status"));
    }

    @Test
    void malformedRequestsFailClosed() {
        assertThrows(JavaDataPlaneValidationException.class, () -> plane.discovery(
                "normal-3c-v1", "search",
                json("{\"requested_categories\":[],\"unexpected\":true}")));
        assertThrows(JavaDataPlaneValidationException.class, () -> plane.bundles(
                "normal-3c-v1",
                json("{\"items\":[],\"requested_categories\":[],\"intent\":\"bundle\"}")));
        assertThrows(JavaDataPlaneValidationException.class, () ->
                plane.reviews("normal-3c-v1", json("{}")));
        assertThrows(JavaDataPlaneValidationException.class, () ->
                plane.quote("normal-3c-v1", json("{}")));
    }

    private JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private List<Map<String, Object>> asMaps(List<?> values) {
        return values.stream()
                .map(value -> mapper.convertValue(
                        value, new TypeReference<Map<String, Object>>() { }))
                .toList();
    }
}
