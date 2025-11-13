package com.buysense.sar.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommerceBusinessPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JavaRetailDataPlane plane = new JavaRetailDataPlane(mapper,
            Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void changingIdentityWithoutHistoryCannotInventPreferences() {
        ObjectNode request = phoneRequest();
        request.put("identity_id", "new-user-one");
        JsonNode first = search(request);
        request.put("identity_id", "new-user-two");
        assertThat(search(request)).isEqualTo(first);
    }

    @Test
    void observedHistoryAddsABoundedPreferenceAndOptOutRemovesIt() {
        ObjectNode request = phoneRequest();
        JsonNode baseline = search(request);
        request.putArray("recent_product_ids").add("spu-iphone-15");
        JsonNode personalized = search(request);
        assertThat(score(personalized, "sku-iphone-15-128-blue") - score(baseline, "sku-iphone-15-128-blue"))
                .isCloseTo(0.12, org.assertj.core.data.Offset.offset(0.000001));
        request.put("personalization_enabled", false);
        assertThat(score(search(request), "sku-iphone-15-128-blue"))
                .isEqualTo(score(baseline, "sku-iphone-15-128-blue"));
    }

    @Test
    void scoresRemainComparableWhenCandidateWindowChanges() {
        ObjectNode request = phoneRequest();
        request.put("limit", 100);
        JsonNode full = search(request);
        request.put("limit", 3);
        for (JsonNode item : search(request).path("items")) {
            assertThat(item.path("normalized_score").asDouble()).isEqualTo(
                    find(full, item.path("sku_id").asText()).path("normalized_score").asDouble());
            assertThat(item.path("channel_score").asDouble()).isBetween(0.0, 1.0);
        }
    }

    @Test
    void irrelevantAdsCannotBuyTheirWayThroughThePlacementGate() {
        ObjectNode request = phoneRequest();
        ObjectNode organic = (ObjectNode) search(request);
        ObjectNode ads = mapper.valueToTree(plane.discovery("normal-3c-v1", "ads", request));
        assertThat(ads.path("items").isEmpty()).isFalse();
        ObjectNode noise = ((ObjectNode) ads.path("items").get(0)).deepCopy();
        noise.put("channel_score", 1.0).put("normalized_score", 1.0)
                .put("ad_bid", 100000).put("ad_quality", 1.0).put("relevance_score", 0.0);
        ads.putArray("items").add(noise);
        var retained = mapper.createArrayNode();
        organic.path("items").forEach(item -> {
            if (!item.path("sku_id").asText().equals(noise.path("sku_id").asText())) retained.add(item);
        });
        organic.set("items", retained);
        JsonNode result = mapper.valueToTree(plane.fuse("normal-3c-v1", mapper.valueToTree(Map.of(
                "channels", List.of(organic, ads), "limit", 6))));
        assertThat(result.path("sponsored_count").asInt()).isZero();
    }

    @Test
    void specificUseCaseFiltersSameCategoryAdvertisingNoise() {
        ObjectNode request = phoneRequest().put("query", "长焦创作手机");
        request.putArray("use_cases").add("长焦").add("创作");
        JsonNode ads = mapper.valueToTree(plane.discovery("normal-3c-v1", "ads", request));
        for (JsonNode item : ads.path("items")) {
            assertThat(item.path("relevance_score").asDouble()).isGreaterThanOrEqualTo(0.5);
            assertThat(item.path("sku_id").asText()).isNotEqualTo("sku-xiaomi-14-256-black");
        }
    }

    @Test
    void equallySuitableCheaperVariantWinsWithoutHavingToSpendTheBudget() {
        JsonNode candidates = search(phoneRequest());
        var items = mapper.createArrayNode();
        candidates.path("items").forEach(item -> {
            if (item.path("product_id").asText().equals("spu-iphone-15")) items.add(item);
        });
        assertThat(items.size()).isEqualTo(2);
        JsonNode result = mapper.valueToTree(plane.bundles("normal-3c-v1", mapper.valueToTree(Map.of(
                "items", items, "requested_categories", List.of("phone"), "intent", "precise",
                "budget_max", 6000, "top_n", 2))));
        assertThat(result.path("bundles").get(0).path("sku_ids").get(0).asText())
                .isEqualTo("sku-iphone-15-128-blue");
    }

    @Test
    void globalTruncationPreservesEveryRequiredCategory() {
        ObjectNode request = phoneRequest();
        request.putArray("requested_categories").add("phone").add("headphones").add("charger");
        JsonNode organic = search(request);
        JsonNode rec = mapper.valueToTree(plane.discovery("normal-3c-v1", "recommendation", request));
        JsonNode result = mapper.valueToTree(plane.fuse("normal-3c-v1", mapper.valueToTree(Map.of(
                "channels", List.of(organic, rec), "limit", 3,
                "required_categories", List.of("phone", "headphones", "charger")))));
        var categories = new java.util.HashSet<String>();
        result.path("items").forEach(item -> categories.add(item.path("category").asText()));
        assertThat(categories).containsExactlyInAnyOrder("phone", "headphones", "charger");
    }

    @Test
    void rejectsOutOfRangeRelevanceInsteadOfTrustingAnImpossibleAdvertisingScore() {
        ObjectNode source = (ObjectNode) search(phoneRequest());
        ((ObjectNode) source.path("items").get(0)).put("relevance_score", 9);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> plane.fuse("normal-3c-v1",
                mapper.valueToTree(Map.of("channels", List.of(source), "limit", 6))))
                .isInstanceOfSatisfying(JavaDataPlaneValidationException.class,
                        error -> assertThat(error.field()).endsWith(".relevance_score"))
                .hasMessageContaining("between zero and one");
    }

    private ObjectNode phoneRequest() {
        ObjectNode request = mapper.createObjectNode().put("query", "手机").put("limit", 100);
        request.putArray("requested_categories").add("phone");
        request.put("sponsored_allowed", true);
        return request;
    }

    private JsonNode search(ObjectNode request) {
        return mapper.valueToTree(plane.discovery("normal-3c-v1", "search", request));
    }

    private JsonNode find(JsonNode response, String sku) {
        for (JsonNode item : response.path("items")) if (item.path("sku_id").asText().equals(sku)) return item;
        throw new AssertionError("missing " + sku);
    }

    private double score(JsonNode response, String sku) {
        return find(response, sku).path("channel_score").asDouble();
    }
}
