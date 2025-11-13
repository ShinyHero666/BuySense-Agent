package com.buysense.sar.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CatalogWeightSensitivityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final List<Scenario> CASES = List.of(
            scenario("video", "phone", "视频创作手机", List.of("视频", "创作"),
                    "spu-nova-creator-ultra", "spu-atlas-zoom-pro"),
            scenario("game", "phone", "低延迟游戏手机", List.of("游戏", "低延迟"),
                    "spu-gamecore-g1", "spu-xiaomi-14"),
            scenario("night", "phone", "夜景人像手机", List.of("夜景", "人像"),
                    "spu-lumina-portrait-x7-pro", "spu-nova-creator-ultra", "spu-honor-200"),
            scenario("travel", "phone", "旅行耐用手机", List.of("旅行", "耐用"),
                    "spu-endura-max", "spu-compact-s", "spu-atlas-zoom-pro"),
            scenario("audio-creator", "headphones", "视频创作低延迟耳机", List.of("视频", "创作", "低延迟"),
                    "spu-studiopods-creator"),
            scenario("audio-travel", "headphones", "旅行降噪耳机", List.of("旅行", "降噪"),
                    "spu-clearsound-travel-anc", "spu-airpods-pro-2", "spu-soundcore-liberty"),
            scenario("multiport", "charger", "USB-C多口旅行充电器", List.of("多口", "旅行"),
                    "spu-voltedge-gan-65w", "spu-ugreen-gan-65w", "spu-travelcube-45w"),
            scenario("portable", "charger", "便携旅行充电器", List.of("便携", "旅行"),
                    "spu-travelcube-45w", "spu-apple-charger-20w", "spu-ugreen-gan-65w"),
            outdoor("alpine", "camp_stove", "低温高海拔防风露营炉", List.of("低温", "高海拔", "防风"),
                    "spu-summitflow-regulator-stove", "spu-trailforge-alpine-stove"),
            outdoor("hiking", "camp_stove", "轻量徒步露营炉", List.of("轻量", "徒步"),
                    "spu-ridgewind-trek-stove", "spu-trailforge-alpine-stove", "spu-summitflow-regulator-stove"),
            outdoor("winter-fuel", "fuel_canister", "低温高海拔四季气罐", List.of("低温", "高海拔"),
                    "spu-summitfuel-four-season", "spu-trailforge-four-season-gas"),
            outdoor("hiking-pot", "cookware", "轻量徒步锅具", List.of("轻量", "徒步"),
                    "spu-alpinecook-titanium-pot", "spu-ridgecook-titanium-pot", "spu-duotitan-cookset"));

    @Test
    void comparesNearbyWeightsOnActualCatalogWithoutClaimingAnOptimalProductionWeight() throws Exception {
        long started = System.nanoTime();
        var profiles = new ArrayList<Map<String, Object>>();
        for (double weight : List.of(0.55, 0.9, 1.0, 1.35)) {
            JavaRetailDataPlane data = new JavaRetailDataPlane(mapper,
                    Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC),
                    new RrfWeightProfile("sensitivity-" + weight, "catalog-regression-20260905",
                            Map.of("search", 1.0, "recommendation", weight)));
            var outcomes = new ArrayList<Map<String, Object>>();
            for (Scenario scenario : CASES) {
                ObjectNode query = mapper.createObjectNode().put("query", scenario.query())
                        .put("limit", 100).put("sponsored_allowed", false).put("personalization_enabled", false);
                query.putArray("requested_categories").add(scenario.category());
                query.set("use_cases", mapper.valueToTree(scenario.useCases()));
                var search = data.discovery(scenario.pack(), "search", query);
                var rec = data.discovery(scenario.pack(), "recommendation", query);
                JsonNode fused = mapper.valueToTree(data.fuse(scenario.pack(),
                        mapper.valueToTree(Map.of("channels", List.of(search, rec), "limit", 50))));
                List<String> products = new ArrayList<>();
                fused.path("items").forEach(item -> {
                    String id = item.path("product_id").asText();
                    if (!products.contains(id)) products.add(id);
                    assertThat(item.path("category").asText()).isEqualTo(scenario.category());
                    assertThat(item.path("sponsored").asBoolean()).isFalse();
                    assertThat(item.path("stock").asInt()).isPositive();
                });
                assertThat(products).containsAll(scenario.relevant());
                List<String> top = products.stream().limit(3).toList();
                double recall = top.stream().filter(scenario.relevant()::contains).count()
                        / (double) scenario.relevant().size();
                double reciprocal = 0;
                for (int i = 0; i < top.size(); i++) {
                    if (scenario.relevant().contains(top.get(i))) { reciprocal = 1.0 / (i + 1); break; }
                }
                outcomes.add(Map.of("case", scenario.id(), "query", scenario.query(),
                        "top3Products", top, "referenceRelevantProducts", scenario.relevant(),
                        "recallAt3", recall, "reciprocalRank", reciprocal));
            }
            profiles.add(Map.of("searchWeight", 1.0, "recommendationWeight", weight,
                    "recallAt3", outcomes.stream().mapToDouble(row -> (double) row.get("recallAt3")).average().orElseThrow(),
                    "mrrAt3", outcomes.stream().mapToDouble(row -> (double) row.get("reciprocalRank")).average().orElseThrow(),
                    "cases", outcomes));
        }
        Path output = Path.of("target/evaluation/priority-weight-sensitivity.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                "scope", "12 catalog-derived regression queries, 4 RRF settings; no model or live traffic",
                "labels", "Explicit product-level suitability sets authored from the synthetic catalog; not user labels",
                "limitation", "Sensitivity check only. No claim of globally optimal weights or held-out generalization.",
                "elapsedMs", (System.nanoTime() - started) / 1_000_000, "profiles", profiles));
    }

    private static Scenario scenario(String id, String category, String query, List<String> uses, String... relevant) {
        return new Scenario(id, "normal-3c-v1", category, query, uses, Set.of(relevant));
    }
    private static Scenario outdoor(String id, String category, String query, List<String> uses, String... relevant) {
        return new Scenario(id, "outdoor-camping-v1", category, query, uses, Set.of(relevant));
    }
    private record Scenario(String id, String pack, String category, String query,
                            List<String> useCases, Set<String> relevant) {}
}
